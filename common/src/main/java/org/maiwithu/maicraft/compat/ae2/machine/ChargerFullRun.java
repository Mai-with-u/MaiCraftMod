// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.machine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.ability.machine.spi.FullRun;
import org.maiwithu.maicraft.ability.machine.spi.RunRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 充能器整段运行动作（FullRun）：同时覆盖转化配方（如赛特斯石英变充能赛特斯石英）
 * 与任意随身未满电 AE2 带电工具补电。
 *
 * <p>执行顺序：清理槽 0 异物并正面捡回 → 备齐待充物品 → 手持右键放入 1 件 → 有外电时等其完成，
 * 无外电时按曲柄旋转节拍（rotation 小于 3，每约 16 刻一次）摇曲柄；满电或曲柄拒转立刻停摇绝不空摇销毁
 * → 空手右键弹出并在正面相邻格捡回，核对背包增量直至达到目标件数。
 */
final class ChargerFullRun implements FullRun {

    /** 充能器未工作多少刻后认为没有外部电网供电、开始找侧面木质曲柄手摇。C 玩家常识。 */
    static final int IDLE_BEFORE_CRANK_TICKS = 20;
    /** 木质曲柄每次转完 18 刻，rotation 小于 3 即第 16 刻可安全续摇下一圈。F 游戏事实。 */
    static final int CRANK_INTERVAL_TICKS = 16;
    /** 弹出后走到正面捡起掉落物的等待上限（刻）。C 玩家常识。 */
    static final int PICKUP_WAIT_TICKS = 60;
    /** 摇曲柄未旋转/被拒的连续安全试探上限（远低于 AE2 满电空摇超过 10 次无掉落毁曲柄的阈值）。F 游戏事实。 */
    static final int MAX_CONSECUTIVE_CRANK_REFUSALS = 2;

    enum Stage { INSPECT, CLEAR_FOREIGN, PREPARE_ITEM, INSERT, WAIT_OR_CRANK, EJECT, PICKUP, DONE }

    private final BlockPos at;
    private final RunRequest request;
    private final Permissions permissions;
    private final Ae2MachineReads reads;
    private final PlayerServices services;

    private final List<String> notes = new ArrayList<>();
    private Stage stage = Stage.INSPECT;
    private Action step;
    private boolean toolRechargeMode;
    private String inputItemId;
    private int produced;
    private int idleTicks;
    private int crankCooldown;
    private int waitTicks;
    private int totalTicks;
    private int carriedTargetBefore = -1;
    private double carriedToolEnergyBefore = -1;
    private Direction frontFace = Direction.NORTH;
    private BlockPos crankPos;
    private boolean collectingForeign;
    private boolean turningCrank;
    private boolean approachedFront;
    private int consecutiveCrankRefusals;

    ChargerFullRun(BlockPos at, RunRequest request, Permissions permissions,
            Ae2MachineReads reads, PlayerServices services) {
        this.at = at.immutable();
        this.request = Objects.requireNonNull(request, "request");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public int produced() { return produced; }

    @Override public boolean complete() { return produced >= targetCount(); }

    @Override public List<String> notes() { return List.copyOf(notes); }

    private int targetCount() { return Math.max(1, request.count()); }

    @Override public ActionStatus tick(TickContext tick) {
        PlayerContext player = tick.player();
        if (player == null || player.level() == null || player.localPlayer() == null) {
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "角色不在世界里，无法操作充能器"));
        }
        int maxTicks = Math.max(200, request.maxSeconds() > 0 ? request.maxSeconds() * 20 : 120 * 20);
        if (++totalTicks > maxTicks) {
            return ActionStatus.failed(Problem.of(Problem.Kind.STUCK,
                    "充能器运行超过等待时限（已完成 " + produced + "/" + targetCount() + "）"));
        }
        return switch (stage) {
            case INSPECT -> inspect(player);
            case CLEAR_FOREIGN -> clearForeign(tick, player);
            case PREPARE_ITEM -> prepareItem(tick, player);
            case INSERT -> insertItem(tick, player);
            case WAIT_OR_CRANK -> waitOrCrank(tick, player);
            case EJECT -> ejectItem(tick, player);
            case PICKUP -> pickupFront(tick, player);
            case DONE -> ActionStatus.done();
        };
    }

    // 第一步先看充能器现场并确认用途：有转化配方走转化，否则看背包里有没有未满电的带电工具需要补电。
    private ActionStatus inspect(PlayerContext player) {
        Optional<Ae2MachineReads.ChargerView> found = reads.chargerAt(player.level(), at);
        if (found.isEmpty()) {
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "这里已经不是充能器"));
        }
        Ae2MachineReads.ChargerView view = found.get();
        frontFace = view.front() == null ? Direction.NORTH : view.front();
        crankPos = findAttachedCrank(player.level()).orElse(null);

        Optional<Ae2MachineReads.ChargerRecipeView> recipe = reads.chargerRecipeForOutput(player.level(), request.item());
        if (recipe.isPresent()) {
            toolRechargeMode = false;
            inputItemId = recipe.get().inputItemId();
        } else {
            toolRechargeMode = true;
            inputItemId = request.item();
            if (!hasRechargeableTarget(player, view)) {
                return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM,
                        "既没有产出 " + request.item() + " 的充能配方，背包或充能器里也没有需要补电的 " + request.item()));
            }
        }
        return decideAfterInspect(player, view);
    }

    private ActionStatus decideAfterInspect(PlayerContext player, Ae2MachineReads.ChargerView view) {
        if (!view.slotEmpty()) {
            if (isDoneSlot(view)) {
                recordBaseline(player);
                collectingForeign = false;
                stage = Stage.EJECT;
                return ActionStatus.progressed();
            }
            if (view.slotItemId().equals(inputItemId)) {
                recordBaseline(player);
                idleTicks = 0;
                crankCooldown = 0;
                stage = Stage.WAIT_OR_CRANK;
                return ActionStatus.progressed();
            }
            // 槽 0 占着无关异物：先空手弹出并在正面地面捡回，不强行顶掉。
            notes.add("充能器槽内先占着 " + view.slotItemId() + "，先弹出捡回再继续");
            collectingForeign = true;
            stage = Stage.CLEAR_FOREIGN;
            return ActionStatus.progressed();
        }
        stage = Stage.PREPARE_ITEM;
        return ActionStatus.progressed();
    }

    private ActionStatus clearForeign(TickContext tick, PlayerContext player) {
        return ejectAndStep(tick, player, Stage.PICKUP);
    }

    // 备料：转化配方从拿东西引擎取原料；带电工具补电直接从身上切到主手，绝不另造新件。
    private ActionStatus prepareItem(TickContext tick, PlayerContext player) {
        if (step == null) {
            if (countCarried(player, inputItemId) > 0) {
                Optional<Action> hold = services.toMainhand().moveToMainhand(inputItemId);
                if (hold.isEmpty()) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "无法将 " + inputItemId + " 换到主手"));
                }
                step = hold.get();
            } else if (toolRechargeMode) {
                return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM,
                        "身上没有待补电的 " + inputItemId));
            } else {
                step = services.needs().actionFor(
                        new ItemRequest(WantedItem.ofItem(inputItemId), 1, "充能器充能原料"), permissions);
            }
        }
        ActionStatus status = step.tick(tick);
        if (status instanceof ActionStatus.Running) return status;
        finishStep();
        if (status instanceof ActionStatus.Failed failed) return status;
        if (!holdingItem(player, inputItemId)) {
            return ActionStatus.progressed();
        }
        recordBaseline(player);
        stage = Stage.INSERT;
        return ActionStatus.progressed();
    }

    private ActionStatus insertItem(TickContext tick, PlayerContext player) {
        if (step == null) {
            InteractionConfirmation inserted = ctx -> reads.chargerAt(ctx.level(), at)
                    .map(v -> v.slotEmpty()
                            ? InteractionConfirmation.Verdict.PENDING
                            : InteractionConfirmation.Verdict.APPLIED)
                    .orElse(InteractionConfirmation.Verdict.PENDING);
            step = new ApproachThenUse(at, ApproachTarget.ofBlock(at), permissions, services,
                    () -> services.interactions().useBlock(at, inserted));
        }
        ActionStatus status = step.tick(tick);
        if (status instanceof ActionStatus.Running) return status;
        finishStep();
        if (status instanceof ActionStatus.Failed) return status;
        idleTicks = 0;
        crankCooldown = 0;
        stage = Stage.WAIT_OR_CRANK;
        return ActionStatus.progressed();
    }

    // 监测充能完成或无外电摇曲柄：每次摇曲柄前必核对尚未满电且曲柄 canTurn 为真，绝不空摇毁曲柄。
    private ActionStatus waitOrCrank(TickContext tick, PlayerContext player) {
        Optional<Ae2MachineReads.ChargerView> found = reads.chargerAt(player.level(), at);
        if (found.isEmpty()) {
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "充能器中途消失"));
        }
        Ae2MachineReads.ChargerView view = found.get();
        if (view.slotEmpty()) {
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "充能器里的物品中途不见了"));
        }
        if (isDoneSlot(view)) {
            finishStep();
            collectingForeign = false;
            stage = Stage.EJECT;
            return ActionStatus.progressed();
        }
        if (step != null) {
            ActionStatus status = step.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            finishStep();
            if (turningCrank) {
                turningCrank = false;
                // 服务端满电拒转时曲柄不旋转且递增 hits（超过 10 次无掉落销毁），绝不可吞掉 Failed 反复空摇。
                if (status instanceof ActionStatus.Failed) {
                    if (++consecutiveCrankRefusals >= MAX_CONSECUTIVE_CRANK_REFUSALS) {
                        return ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                                "木质曲柄连续 " + consecutiveCrankRefusals
                                        + " 次未能旋转（充能器可能已满电或拒转），为防空摇销毁曲柄已立刻停止"));
                    }
                    crankCooldown = CRANK_INTERVAL_TICKS;
                    return ActionStatus.progressed();
                }
                consecutiveCrankRefusals = 0;
                crankCooldown = CRANK_INTERVAL_TICKS;
            }
            return ActionStatus.progressed();
        }
        if (crankCooldown > 0) crankCooldown--;
        if (view.working()) {
            idleTicks = 0;
            consecutiveCrankRefusals = 0;
            return ActionStatus.running();
        }
        if (++idleTicks < IDLE_BEFORE_CRANK_TICKS) return ActionStatus.running();
        // 持续未工作且侧面装有木质曲柄：严格校验曲柄可转且未达连续拒转上限再摇一下。
        crankPos = findAttachedCrank(player.level()).orElse(null);
        if (crankPos == null) return ActionStatus.running();
        Optional<Ae2MachineReads.CrankView> crank = reads.crankAt(player.level(), crankPos);
        if (crank.isEmpty() || !shouldTurnCrank(view, crank.get(), crankCooldown, consecutiveCrankRefusals)) {
            return ActionStatus.running();
        }
        return startCrankTurn(crankPos, player);
    }

    /** 纯判定：给定当前充能器与曲柄快照、节拍冷却与连续拒转次数，这一刻是否允许安全摇动曲柄。 */
    static boolean shouldTurnCrank(Ae2MachineReads.ChargerView charger, Ae2MachineReads.CrankView crank,
            int crankCooldown) {
        return shouldTurnCrank(charger, crank, crankCooldown, 0);
    }

    /** 纯判定：同时把连续未旋转/拒转次数纳入防毁曲柄红线。 */
    static boolean shouldTurnCrank(Ae2MachineReads.ChargerView charger, Ae2MachineReads.CrankView crank,
            int crankCooldown, int consecutiveRefusals) {
        if (charger == null || crank == null || crankCooldown > 0) return false;
        if (consecutiveRefusals >= MAX_CONSECUTIVE_CRANK_REFUSALS) return false;
        if (charger.slotEmpty() || charger.fullyCharged()) return false;
        return crank.canTurn();
    }

    private ActionStatus startCrankTurn(BlockPos targetCrank, PlayerContext player) {
        Optional<Action> empty = services.toMainhand().emptyMainhand();
        if (empty.isPresent()) {
            turningCrank = false;
            step = empty.get();
            return ActionStatus.progressed();
        }
        float rotationBefore = reads.crankAt(player.level(), targetCrank)
                .map(Ae2MachineReads.CrankView::visibleRotation).orElse(0f);
        // 确认窗到期曲柄仍未旋转必须按未生效（NOT_APPLIED）收场，让外层计入拒转并及时停摇。
        InteractionConfirmation turned = InteractionConfirmation.expiresAsNotApplied(
                ctx -> reads.crankAt(ctx.level(), targetCrank)
                        .map(v -> Math.abs(v.visibleRotation() - rotationBefore) > 0.1f || !v.canTurn()
                                ? InteractionConfirmation.Verdict.APPLIED
                                : InteractionConfirmation.Verdict.PENDING)
                        .orElse(InteractionConfirmation.Verdict.APPLIED));
        turningCrank = true;
        step = new ApproachThenUse(targetCrank, ApproachTarget.ofBlock(targetCrank), permissions, services,
                () -> services.interactions().useBlock(targetCrank, turned));
        return ActionStatus.progressed();
    }

    private ActionStatus ejectItem(TickContext tick, PlayerContext player) {
        return ejectAndStep(tick, player, Stage.PICKUP);
    }

    private ActionStatus ejectAndStep(TickContext tick, PlayerContext player, Stage next) {
        if (step == null) {
            Optional<Action> empty = services.toMainhand().emptyMainhand();
            if (empty.isPresent()) {
                step = empty.get();
                return ActionStatus.progressed();
            }
            InteractionConfirmation ejected = ctx -> reads.chargerAt(ctx.level(), at)
                    .map(v -> v.slotEmpty()
                            ? InteractionConfirmation.Verdict.APPLIED
                            : InteractionConfirmation.Verdict.PENDING)
                    .orElse(InteractionConfirmation.Verdict.APPLIED);
            step = new ApproachThenUse(at, ApproachTarget.ofBlock(at), permissions, services,
                    () -> services.interactions().useBlock(at, ejected));
        }
        ActionStatus status = step.tick(tick);
        if (status instanceof ActionStatus.Running) return status;
        finishStep();
        if (status instanceof ActionStatus.Failed) return status;
        waitTicks = 0;
        approachedFront = false;
        stage = next;
        return ActionStatus.progressed();
    }

    // 弹出物落在充能器正面相邻格（at.relative(front)），走过去靠近正面捡起并核对背包增量。
    private ActionStatus pickupFront(TickContext tick, PlayerContext player) {
        BlockPos dropSpot = at.relative(frontFace);
        if (!approachedFront) {
            if (step == null) {
                step = services.approach().toward(ApproachTarget.ofBlock(dropSpot), permissions);
            }
            ActionStatus status = step.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            finishStep();
            if (status instanceof ActionStatus.Failed) return status;
            approachedFront = true;
        }
        if (collectingForeign) {
            collectingForeign = false;
            stage = Stage.PREPARE_ITEM;
            return ActionStatus.progressed();
        }
        if (confirmedCollected(player)) {
            produced++;
            if (produced >= targetCount()) {
                stage = Stage.DONE;
                return ActionStatus.done();
            }
            stage = Stage.PREPARE_ITEM;
            return ActionStatus.progressed();
        }
        if (++waitTicks <= PICKUP_WAIT_TICKS) return ActionStatus.running();
        return ActionStatus.failed(Problem.of(Problem.Kind.STUCK,
                "已从充能器正面弹出 " + request.item() + "，但等待拾取进背包超时（掉落格 " + dropSpot.toShortString() + "）"));
    }

    private boolean isDoneSlot(Ae2MachineReads.ChargerView view) {
        if (view.slotEmpty()) return false;
        if (toolRechargeMode) {
            return view.slotItemId().equals(request.item()) && view.fullyCharged();
        }
        return view.slotItemId().equals(request.item());
    }

    private boolean hasRechargeableTarget(PlayerContext player, Ae2MachineReads.ChargerView view) {
        if (!view.slotEmpty() && view.slotItemId().equals(request.item()) && view.chargeable()) {
            return true;
        }
        Inventory inv = player.localPlayer().getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if (!BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(request.item())) continue;
            Optional<Ae2MachineReads.ItemPowerView> power = reads.itemPower(stack);
            if (power.isPresent() && !power.get().full()) return true;
        }
        return false;
    }

    private Optional<BlockPos> findAttachedCrank(ClientLevel level) {
        for (Direction side : Direction.values()) {
            if (side == frontFace) continue;
            BlockPos candidate = at.relative(side);
            Optional<Ae2MachineReads.CrankView> crank = reads.crankAt(level, candidate);
            if (crank.isPresent() && at.equals(crank.get().attachedTo())) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private void recordBaseline(PlayerContext player) {
        carriedTargetBefore = countCarried(player, request.item());
        carriedToolEnergyBefore = maxToolEnergy(player, request.item());
    }

    private boolean confirmedCollected(PlayerContext player) {
        int nowCount = countCarried(player, request.item());
        if (nowCount > carriedTargetBefore) return true;
        return toolRechargeMode && maxToolEnergy(player, request.item()) > carriedToolEnergyBefore + 0.01;
    }

    private double maxToolEnergy(PlayerContext player, String itemId) {
        double best = -1;
        Inventory inv = player.localPlayer().getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if (!BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId)) continue;
            Optional<Ae2MachineReads.ItemPowerView> power = reads.itemPower(stack);
            if (power.isPresent()) best = Math.max(best, power.get().current());
        }
        return best;
    }

    private static int countCarried(PlayerContext player, String itemId) {
        int total = 0;
        Inventory inv = player.localPlayer().getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static boolean holdingItem(PlayerContext player, String itemId) {
        ItemStack held = player.localPlayer().getMainHandItem();
        return !held.isEmpty() && BuiltInRegistries.ITEM.getKey(held.getItem()).toString().equals(itemId);
    }

    private void finishStep() {
        if (step != null) {
            step.close();
            step = null;
        }
    }

    @Override public void pause() {
        if (step != null) step.pause();
    }

    @Override public void close() {
        finishStep();
    }

    @Override public Interruptibility interruptibility() {
        return step != null ? step.interruptibility() : Interruptibility.WORKING;
    }

    @Override public String describe() {
        return "使用充能器充能 " + request.item() + "（" + produced + "/" + targetCount() + "）";
    }

    /** 先靠近目标再执行一次交互动作的小组合。 */
    private static final class ApproachThenUse implements Action {
        private final BlockPos targetPos;
        private final ApproachTarget target;
        private final Permissions permissions;
        private final PlayerServices services;
        private final Supplier<Action> useFactory;
        private boolean approached;
        private Action current;

        ApproachThenUse(BlockPos targetPos, ApproachTarget target, Permissions permissions,
                PlayerServices services, Supplier<Action> useFactory) {
            this.targetPos = targetPos;
            this.target = target;
            this.permissions = permissions;
            this.services = services;
            this.useFactory = useFactory;
        }

        @Override public ActionStatus tick(TickContext tick) {
            if (current == null) {
                current = approached ? useFactory.get() : services.approach().toward(target, permissions);
            }
            ActionStatus status = current.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            current.close();
            current = null;
            if (status instanceof ActionStatus.Failed) return status;
            if (!approached) {
                approached = true;
                return ActionStatus.progressed();
            }
            return ActionStatus.done();
        }

        @Override public void pause() { if (current != null) current.pause(); }
        @Override public void close() { if (current != null) { current.close(); current = null; } }
        @Override public String describe() { return "靠近并右键 " + targetPos.toShortString(); }
    }
}
