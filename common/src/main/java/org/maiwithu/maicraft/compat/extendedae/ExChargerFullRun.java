// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.extendedae;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
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
 * 扩展充能器（{@code extendedae:ex_charger}）4 槽并发整段运行动作（{@link FullRun}）：
 * 支持 AE2 充能配方物品（一次最多并行放入 4 件，等全部变目标产物后依次空手右键收回）
 * 以及实现 {@code IAEItemPowerStorage} 的带电工具补电；也支持 {@code wait=false} 模式（放满首批即返回）。
 */
final class ExChargerFullRun implements FullRun {

    /** 扩展充能器固定并发槽位总数。F 游戏事实。 */
    static final int MAX_SLOTS = 4;

    enum Stage { INSPECT, PREPARE_BATCH, INSERT_SLOT, WAIT_CHARGING, EJECT_DONE, DONE }

    private final BlockPos at;
    private final RunRequest request;
    private final boolean waitForOutput;
    private final Permissions permissions;
    private final ExtendedAeCompat compat;
    private final PlayerServices services;

    private final List<String> notes = new ArrayList<>();
    private Stage stage = Stage.INSPECT;
    private Action step;
    private boolean toolRechargeMode;
    private String inputItemId;
    private int produced;
    private int batchToInsert;
    private int insertedInBatch;
    private boolean handedOff;
    private int totalTicks;

    ExChargerFullRun(BlockPos at, RunRequest request, Permissions permissions,
            ExtendedAeCompat compat, PlayerServices services) {
        // 透传 machine_run 的 wait 参数，让 wait=false 在放满首批后即可返回。
        this(at, request, request.waitForOutput(), permissions, compat, services);
    }

    ExChargerFullRun(BlockPos at, RunRequest request, boolean waitForOutput, Permissions permissions,
            ExtendedAeCompat compat, PlayerServices services) {
        this.at = at.immutable();
        this.request = Objects.requireNonNull(request, "request");
        this.waitForOutput = waitForOutput;
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public int produced() { return produced; }

    @Override public boolean complete() {
        return waitForOutput ? produced >= targetCount() : insertedInBatch > 0 || handedOff;
    }

    boolean waitsForOutput() { return waitForOutput; }

    @Override public List<String> notes() { return List.copyOf(notes); }

    private int targetCount() { return Math.max(1, request.count()); }

    /** 纯函数：计算本次并发批次最多可放入几件（上限为剩余需求与当前空槽数的较小值，最多 4 件）。 */
    static int batchSizeFor(int remaining, ExtendedAeReads.ExChargerView view) {
        if (remaining <= 0 || view == null) return 0;
        int emptySlots = (int) view.slots().stream().filter(ExtendedAeReads.ChargerSlotView::empty).count();
        return Math.min(MAX_SLOTS, Math.min(remaining, emptySlots));
    }

    @Override public ActionStatus tick(TickContext tick) {
        PlayerContext player = tick.player();
        if (player == null || player.level() == null || player.localPlayer() == null) {
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "角色不在世界里，无法操作扩展充能器"));
        }
        int maxTicks = Math.max(200, request.maxSeconds() > 0 ? request.maxSeconds() * 20 : 120 * 20);
        if (++totalTicks > maxTicks) {
            return ActionStatus.failed(Problem.of(Problem.Kind.STUCK,
                    "扩展充能器运行超过等待时限（已完成 " + produced + "/" + targetCount() + "）"));
        }
        return switch (stage) {
            case INSPECT -> inspect(player);
            case PREPARE_BATCH -> prepareBatch(tick, player);
            case INSERT_SLOT -> insertOne(tick, player);
            case WAIT_CHARGING -> waitCharging(player);
            case EJECT_DONE -> ejectDone(tick, player);
            case DONE -> ActionStatus.done();
        };
    }

    // 先检查是否有对应充能配方；没有配方再核对背包或机内是否有带电工具待补电，绝不盲点不接受的物品。
    private ActionStatus inspect(PlayerContext player) {
        Optional<ExtendedAeReads.ExChargerView> found = compat.exChargerAt(player.level(), at);
        if (found.isEmpty()) {
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "这里已经不是扩展充能器"));
        }
        ExtendedAeReads.ExChargerView view = found.get();
        Optional<ExtendedAeReads.ChargerRecipeView> recipe = compat.chargerRecipeForOutput(
                player.level(), request.item());
        if (recipe.isPresent()) {
            toolRechargeMode = false;
            inputItemId = recipe.get().inputItemId();
        } else {
            toolRechargeMode = true;
            inputItemId = request.item();
            if (!hasRechargeableTarget(player, view)) {
                return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM,
                        "既没有产出 " + request.item() + " 的充能配方，也没有待补电的带电物品 " + request.item()));
            }
        }
        if (countDoneSlots(view) > 0 && waitForOutput) {
            stage = Stage.EJECT_DONE;
            return ActionStatus.progressed();
        }
        int inProgress = countInProgressSlots(view);
        int remaining = Math.max(0, targetCount() - produced - inProgress);
        batchToInsert = batchSizeFor(remaining, view);
        insertedInBatch = 0;
        if (batchToInsert > 0) {
            stage = Stage.PREPARE_BATCH;
        } else if (inProgress > 0) {
            stage = waitForOutput ? Stage.WAIT_CHARGING : Stage.DONE;
            if (!waitForOutput) {
                handedOff = true;
                notes.add("扩展充能器内已有 " + inProgress + " 件正在充能，wait=false 不等待完成");
                return ActionStatus.done();
            }
        } else {
            return ActionStatus.failed(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    "扩展充能器 4 个槽位均已被其他物品占满，无法放入 " + inputItemId));
        }
        return ActionStatus.progressed();
    }

    private ActionStatus prepareBatch(TickContext tick, PlayerContext player) {
        int stillToInsert = batchToInsert - insertedInBatch;
        if (stillToInsert <= 0) {
            return afterBatchInserted();
        }
        if (step == null) {
            int carried = countCarried(player, inputItemId);
            if (carried > 0) {
                Optional<Action> hold = services.toMainhand().moveToMainhand(inputItemId);
                if (hold.isEmpty()) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "无法将 " + inputItemId + " 换到主手"));
                }
                step = hold.get();
            } else if (toolRechargeMode) {
                return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "身上没有待补电的 " + inputItemId));
            } else {
                step = services.needs().actionFor(
                        new ItemRequest(WantedItem.ofItem(inputItemId), stillToInsert, "扩展充能器原料"), permissions);
            }
        }
        ActionStatus status = step.tick(tick);
        if (status instanceof ActionStatus.Running) return status;
        finishStep();
        if (status instanceof ActionStatus.Failed) return status;
        if (!holdingItem(player, inputItemId)) return ActionStatus.progressed();
        stage = Stage.INSERT_SLOT;
        return ActionStatus.progressed();
    }

    private ActionStatus insertOne(TickContext tick, PlayerContext player) {
        if (step == null) {
            int occupiedBefore = compat.exChargerAt(player.level(), at)
                    .map(ExChargerFullRun::countOccupiedSlots).orElse(0);
            InteractionConfirmation inserted = ctx -> compat.exChargerAt(ctx.level(), at)
                    .map(v -> countOccupiedSlots(v) > occupiedBefore
                            ? InteractionConfirmation.Verdict.APPLIED
                            : InteractionConfirmation.Verdict.PENDING)
                    .orElse(InteractionConfirmation.Verdict.PENDING);
            step = new ApproachThenUse(at, permissions, services, () -> services.interactions().useBlock(at, inserted));
        }
        ActionStatus status = step.tick(tick);
        if (status instanceof ActionStatus.Running) return status;
        finishStep();
        if (status instanceof ActionStatus.Failed) return status;
        insertedInBatch++;
        if (insertedInBatch < batchToInsert) {
            stage = Stage.PREPARE_BATCH;
            return ActionStatus.progressed();
        }
        return afterBatchInserted();
    }

    ActionStatus afterBatchInserted() {
        if (!waitForOutput) {
            handedOff = true;
            notes.add("已向扩展充能器并发放入 " + insertedInBatch + " 件 " + inputItemId + "，wait=false 不等待充能完成");
            stage = Stage.DONE;
            return ActionStatus.done();
        }
        stage = Stage.WAIT_CHARGING;
        return ActionStatus.progressed();
    }

    private ActionStatus waitCharging(PlayerContext player) {
        Optional<ExtendedAeReads.ExChargerView> found = compat.exChargerAt(player.level(), at);
        if (found.isEmpty()) {
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "扩展充能器中途消失"));
        }
        ExtendedAeReads.ExChargerView view = found.get();
        int done = countDoneSlots(view);
        int inProgress = countInProgressSlots(view);
        if (done > 0 && inProgress == 0) {
            stage = Stage.EJECT_DONE;
            return ActionStatus.progressed();
        }
        if (done == 0 && inProgress == 0) {
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "扩展充能器内的物品中途被取走"));
        }
        return ActionStatus.running();
    }

    // 空手右键扩展充能器：TileExCharger.activate 会把非空槽物品直接放回玩家背包或掉落，确认非空槽数减少即计 1 件。
    private ActionStatus ejectDone(TickContext tick, PlayerContext player) {
        Optional<ExtendedAeReads.ExChargerView> found = compat.exChargerAt(player.level(), at);
        if (found.isEmpty()) {
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "扩展充能器中途消失"));
        }
        ExtendedAeReads.ExChargerView view = found.get();
        if (countDoneSlots(view) == 0) {
            if (produced >= targetCount()) {
                stage = Stage.DONE;
                return ActionStatus.done();
            }
            stage = Stage.INSPECT;
            return ActionStatus.progressed();
        }
        if (step == null) {
            Optional<Action> empty = services.toMainhand().emptyMainhand();
            if (empty.isPresent()) {
                step = empty.get();
                return ActionStatus.progressed();
            }
            int doneBefore = countDoneSlots(view);
            InteractionConfirmation ejected = ctx -> compat.exChargerAt(ctx.level(), at)
                    .map(v -> countDoneSlots(v) < doneBefore
                            ? InteractionConfirmation.Verdict.APPLIED
                            : InteractionConfirmation.Verdict.PENDING)
                    .orElse(InteractionConfirmation.Verdict.APPLIED);
            step = new ApproachThenUse(at, permissions, services, () -> services.interactions().useBlock(at, ejected));
        }
        ActionStatus status = step.tick(tick);
        if (status instanceof ActionStatus.Running) return status;
        finishStep();
        if (status instanceof ActionStatus.Failed) return status;
        produced++;
        if (produced >= targetCount()) {
            stage = Stage.DONE;
            return ActionStatus.done();
        }
        return ActionStatus.progressed();
    }

    private int countDoneSlots(ExtendedAeReads.ExChargerView view) {
        int count = 0;
        for (ExtendedAeReads.ChargerSlotView slot : view.slots()) {
            if (slot.empty()) continue;
            if (toolRechargeMode) {
                if (request.item().equals(slot.itemId()) && slot.fullyCharged()) count++;
            } else if (request.item().equals(slot.itemId())) {
                count++;
            }
        }
        return count;
    }

    private int countInProgressSlots(ExtendedAeReads.ExChargerView view) {
        int count = 0;
        for (ExtendedAeReads.ChargerSlotView slot : view.slots()) {
            if (slot.empty()) continue;
            if (toolRechargeMode) {
                if (request.item().equals(slot.itemId()) && slot.chargeable() && !slot.fullyCharged()) count++;
            } else if (inputItemId.equals(slot.itemId()) && !request.item().equals(slot.itemId())) {
                count++;
            }
        }
        return count;
    }

    private static int countOccupiedSlots(ExtendedAeReads.ExChargerView view) {
        int count = 0;
        for (ExtendedAeReads.ChargerSlotView slot : view.slots()) {
            if (!slot.empty()) count++;
        }
        return count;
    }

    private boolean hasRechargeableTarget(PlayerContext player, ExtendedAeReads.ExChargerView view) {
        for (ExtendedAeReads.ChargerSlotView slot : view.slots()) {
            if (!slot.empty() && request.item().equals(slot.itemId()) && slot.chargeable()) return true;
        }
        Inventory inv = player.localPlayer().getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if (!BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(request.item())) continue;
            Optional<ExtendedAeReads.ItemPowerView> power = compat.itemPower(stack);
            if (power.isPresent() && !power.get().full()) return true;
        }
        return false;
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

    @Override public void pause() { if (step != null) step.pause(); }

    @Override public void close() { finishStep(); }

    @Override public Interruptibility interruptibility() {
        return step != null ? step.interruptibility() : Interruptibility.WORKING;
    }

    @Override public String describe() {
        return "使用扩展充能器并发充能 " + request.item() + "（" + produced + "/" + targetCount() + "）";
    }

    private static final class ApproachThenUse implements Action {
        private final BlockPos targetPos;
        private final Permissions permissions;
        private final PlayerServices services;
        private final Supplier<Action> useFactory;
        private boolean approached;
        private Action current;

        ApproachThenUse(BlockPos targetPos, Permissions permissions, PlayerServices services,
                Supplier<Action> useFactory) {
            this.targetPos = targetPos;
            this.permissions = permissions;
            this.services = services;
            this.useFactory = useFactory;
        }

        @Override public ActionStatus tick(TickContext tick) {
            if (current == null) {
                current = approached ? useFactory.get()
                        : services.approach().toward(ApproachTarget.ofBlock(targetPos), permissions);
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
        @Override public String describe() { return "靠近并右键扩展充能器 " + targetPos.toShortString(); }
    }
}
