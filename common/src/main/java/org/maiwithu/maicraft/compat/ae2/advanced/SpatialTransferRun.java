// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.advanced;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import org.maiwithu.maicraft.ability.machine.spi.FullRun;
import org.maiwithu.maicraft.ability.machine.spi.RunRequest;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 空间传输站的整段运行动作（{@link FullRun}）：
 * 打开 {@code ae2:spatialioport} → 腾空槽 1 并将目标空间存储元件放入槽 0（{@code MACHINE_INPUT}）→ 关窗
 * → 查找相邻拉杆或按钮触发低→高红石上升沿脉冲（无开关时放好元件并明确提示外部给脉冲）
 * → 等待切换后再次打开界面核对：元件移至槽 1 则收回背包成功，未切换则按实测读数如实回报原因。
 */
final class SpatialTransferRun implements FullRun {

    private enum Stage {
        CLEAR_HAND, OPEN_LOAD, PREPARE_SLOTS, WAIT_LOAD, CLOSE_LOAD,
        LOCATE_SWITCH, APPROACH_SWITCH, RESET_LEVER, PULSE_GAP, TRIGGER_SWITCH,
        WAIT_TRANSITION, OPEN_VERIFY, VERIFY_SLOT, WAIT_TAKE, CLOSE_VERIFY, FINISHED
    }

    /** 界面槽位同步与搬运确认等待上限（刻）。F 游戏事实。 */
    private static final int SYNC_WAIT_TICKS = 40;
    /** 空间切换红石上升沿触发后的等待结算刻数（AE2 通过 TickHandler 下刻执行）。F 游戏事实。 */
    private static final int TRANSITION_WAIT_TICKS = 20;
    /** 拉杆先拉低再拉高之间留出的间隔刻数，确保服务端观察到低→高上升沿。F 游戏事实。 */
    private static final int LEVER_RESET_GAP_TICKS = 4;

    private final BlockPos at;
    private final RunRequest request;
    private final Permissions permissions;
    private final Ae2Compat compat;
    private final Ae2AdvancedReads reads;
    private final PlayerServices services;
    private final Consumer<Ae2AdvancedReads.SpatialMenuStatus> cacheSink;

    private Stage stage = Stage.CLEAR_HAND;
    private Action current;
    private MenuOpening opening;
    private OpenedMenu menu;
    private int waited;
    private BlockPos switchPos;
    private boolean switchIsLever;
    private boolean leverNeedsReset;
    private Ae2AdvancedReads.SpatialMenuStatus lastMenuStatus;
    private Ae2AdvancedReads.SpatialCellSpec loadedCellSpec;
    private int produced;
    private boolean complete;
    private final List<String> notes = new ArrayList<>();
    private Problem problem;

    SpatialTransferRun(BlockPos at, RunRequest request, Permissions permissions, Ae2Compat compat,
                       Ae2AdvancedReads reads, PlayerServices services,
                       Consumer<Ae2AdvancedReads.SpatialMenuStatus> cacheSink) {
        this.at = at.immutable();
        this.request = Objects.requireNonNull(request, "request");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.compat = Objects.requireNonNull(compat, "compat");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.services = Objects.requireNonNull(services, "services");
        this.cacheSink = Objects.requireNonNull(cacheSink, "cacheSink");
    }

    @Override public int produced() { return produced; }

    @Override public boolean complete() { return complete; }

    @Override public List<String> notes() { return List.copyOf(notes); }

    @Override public ActionStatus tick(TickContext context) {
        PlayerContext player = context.player();
        if (stage != Stage.FINISHED && (player == null || !player.isCurrent() || player.localPlayer() == null)) {
            return ActionStatus.running();
        }
        return switch (stage) {
            case CLEAR_HAND -> clearHand(context);
            case OPEN_LOAD -> openMenu(context, Stage.PREPARE_SLOTS);
            case PREPARE_SLOTS -> prepareSlots(player);
            case WAIT_LOAD -> waitLoad(player);
            case CLOSE_LOAD -> closeMenu(context, problem == null ? Stage.LOCATE_SWITCH : Stage.FINISHED);
            case LOCATE_SWITCH -> locateSwitch(player.level());
            case APPROACH_SWITCH -> approachSwitch(context);
            case RESET_LEVER -> flipSwitch(context, Stage.PULSE_GAP);
            case PULSE_GAP -> waitTicks(LEVER_RESET_GAP_TICKS, Stage.TRIGGER_SWITCH);
            case TRIGGER_SWITCH -> flipSwitch(context, Stage.WAIT_TRANSITION);
            case WAIT_TRANSITION -> waitTicks(TRANSITION_WAIT_TICKS, Stage.OPEN_VERIFY);
            case OPEN_VERIFY -> openMenu(context, Stage.VERIFY_SLOT);
            case VERIFY_SLOT -> verifyAndTake(player);
            case WAIT_TAKE -> waitTake(player);
            case CLOSE_VERIFY -> closeMenu(context, Stage.FINISHED);
            case FINISHED -> problem == null ? ActionStatus.done() : ActionStatus.failed(problem);
        };
    }

    private ActionStatus clearHand(TickContext context) {
        if (current == null) {
            Optional<Action> empty = services.toMainhand().emptyMainhand();
            if (empty.isEmpty()) return enter(Stage.OPEN_LOAD);
            current = empty.get();
        }
        ActionStatus status = current.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        current.close();
        current = null;
        if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
        return enter(Stage.OPEN_LOAD);
    }

    private ActionStatus openMenu(TickContext context, Stage next) {
        if (opening == null) {
            opening = new ClientMenuOpening(at, permissions, services.menuLayouts(), services.approach(),
                    services.interactions(), services.context());
        }
        ActionStatus status = opening.tick(context);
        if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
        if (!(status instanceof ActionStatus.Done)) return status;
        menu = opening.opened().orElse(null);
        opening = null;
        if (menu == null) return finish(Problem.of(Problem.Kind.TARGET_GONE, "空间传输站界面没打开"));
        return enter(next);
    }

    // 打开菜单后先同步菜单读数；若槽 1 非空先收回背包，再把目标空间元件移进槽 0。
    private ActionStatus prepareSlots(PlayerContext player) {
        Optional<MenuContent.Reading> readingOpt = menu.reading();
        if (readingOpt.isEmpty()) {
            if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
            return closeLoadWith(Problem.of(Problem.Kind.STUCK, "空间传输站界面开了，槽位一直没同步"));
        }
        captureMenuStatus(player);
        MenuContent.Reading reading = readingOpt.get();
        int inputSlot = slotBySemantic(reading, "MACHINE_INPUT", 0);
        int outputSlot = slotBySemantic(reading, "MACHINE_OUTPUT", 1);
        if (inputSlot < 0 || outputSlot < 0) {
            return closeLoadWith(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "认不出空间传输站的输入槽与输出槽"));
        }
        SlotSnapshot outSnap = snapshotFor(reading, outputSlot);
        if (outSnap != null && !outSnap.isEmpty()) {
            if (!menu.quickMove(outputSlot)) return ActionStatus.running();
            waited = 0;
            return ActionStatus.progressed();
        }
        SlotSnapshot inSnap = snapshotFor(reading, inputSlot);
        if (inSnap != null && !inSnap.isEmpty() && matchesWantedCell(inSnap)) {
            loadedCellSpec = reads.spatialCell(inSnap.stack()).orElse(null);
            return enter(Stage.CLOSE_LOAD);
        }
        if (inSnap != null && !inSnap.isEmpty()) {
            // 槽 0 占着其他物品：先收回背包，下一刻再放目标元件。
            if (!menu.quickMove(inputSlot)) return ActionStatus.running();
            waited = 0;
            return ActionStatus.progressed();
        }
        int carriedCellSlot = findCarriedCellSlot(reading);
        if (carriedCellSlot < 0) {
            String wanted = request.item() == null || request.item().isBlank()
                    ? "空间存储元件（ae2:spatial_storage_cell_2 / 16 / 128）" : request.item();
            return closeLoadWith(Problem.of(Problem.Kind.NEED_ITEM, "背包里没有 " + wanted));
        }
        if (!menu.quickMove(carriedCellSlot)) return ActionStatus.running();
        return enter(Stage.WAIT_LOAD);
    }

    private ActionStatus waitLoad(PlayerContext player) {
        Optional<MenuContent.Reading> readingOpt = menu.reading();
        if (readingOpt.isPresent()) {
            captureMenuStatus(player);
            MenuContent.Reading reading = readingOpt.get();
            int inputSlot = slotBySemantic(reading, "MACHINE_INPUT", 0);
            SlotSnapshot inSnap = snapshotFor(reading, inputSlot);
            if (inSnap != null && !inSnap.isEmpty() && matchesWantedCell(inSnap)) {
                loadedCellSpec = reads.spatialCell(inSnap.stack()).orElse(null);
                return enter(Stage.CLOSE_LOAD);
            }
        }
        if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
        return closeLoadWith(Problem.of(Problem.Kind.STUCK, "把空间元件放入槽 0 后一直没看到槽位变化"));
    }

    // 扫描空间传输站 6 个相邻格寻找可用拉杆或按钮；没有开关时明确告知需外部红石上升沿，不伪造切换。
    private ActionStatus locateSwitch(ClientLevel level) {
        for (Direction dir : Direction.values()) {
            BlockPos adj = at.relative(dir);
            if (!level.isLoaded(adj)) continue;
            BlockState state = level.getBlockState(adj);
            if (state.getBlock() instanceof LeverBlock) {
                switchPos = adj.immutable();
                switchIsLever = true;
                leverNeedsReset = state.hasProperty(BlockStateProperties.POWERED)
                        && state.getValue(BlockStateProperties.POWERED);
                return enter(Stage.APPROACH_SWITCH);
            }
            if (state.getBlock() instanceof ButtonBlock) {
                switchPos = adj.immutable();
                switchIsLever = false;
                leverNeedsReset = false;
                return enter(Stage.APPROACH_SWITCH);
            }
        }
        notes.add("已将空间存储元件放入槽 0（槽 1 为空），周围未发现拉杆或按钮；请对 "
                + at.toShortString() + " 提供一次外部红石低→高上升沿脉冲触发空间互换");
        return finish(null);
    }

    private ActionStatus approachSwitch(TickContext context) {
        if (current == null) {
            current = services.approach().toward(ApproachTarget.ofBlock(switchPos), permissions);
        }
        ActionStatus status = current.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        current.close();
        current = null;
        if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
        return enter(switchIsLever && leverNeedsReset ? Stage.RESET_LEVER : Stage.TRIGGER_SWITCH);
    }

    private ActionStatus flipSwitch(TickContext context, Stage next) {
        if (current == null) {
            BlockState before = context.player().level().getBlockState(switchPos);
            InteractionConfirmation confirmation = InteractionConfirmation.expiresAsNotApplied(
                    InteractionConfirmation.blockChanged(switchPos, before));
            current = services.interactions().useBlock(switchPos, confirmation);
        }
        ActionStatus status = current.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        current.close();
        current = null;
        if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
        return enter(next);
    }

    private ActionStatus waitTicks(int targetTicks, Stage next) {
        if (++waited < targetTicks) return ActionStatus.running();
        return enter(next);
    }

    // 脉冲后重新核对界面：元件移入槽 1 说明切换成功，收回背包；仍在槽 0 则如实回报实测读数。
    private ActionStatus verifyAndTake(PlayerContext player) {
        Optional<MenuContent.Reading> readingOpt = menu.reading();
        if (readingOpt.isEmpty()) {
            if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
            return closeVerifyWith(Problem.of(Problem.Kind.STUCK, "脉冲后重开空间传输站界面，槽位一直没同步"));
        }
        captureMenuStatus(player);
        MenuContent.Reading reading = readingOpt.get();
        int inputSlot = slotBySemantic(reading, "MACHINE_INPUT", 0);
        int outputSlot = slotBySemantic(reading, "MACHINE_OUTPUT", 1);
        SlotSnapshot outSnap = snapshotFor(reading, outputSlot);
        SlotSnapshot inSnap = snapshotFor(reading, inputSlot);
        if (outSnap != null && !outSnap.isEmpty()) {
            if (!menu.quickMove(outputSlot)) return ActionStatus.running();
            return enter(Stage.WAIT_TAKE);
        }
        // 游戏未把元件移到槽 1：绝不伪造成功，按实测读数如实回报原因。
        boolean online = reads.spatialTransfer(player.level(), at)
                .map(Ae2AdvancedReads.SpatialTransferCell::active).orElse(false);
        Ae2AdvancedReads.SpatialCellSpec currentSpec = inSnap != null && !inSnap.isEmpty()
                ? reads.spatialCell(inSnap.stack()).orElse(loadedCellSpec) : loadedCellSpec;
        List<String> diagnosis = SpatialTransfer.diagnoseUnchanged(
                Optional.ofNullable(lastMenuStatus), Optional.ofNullable(currentSpec), online);
        notes.addAll(diagnosis);
        return closeVerifyWith(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                "触发红石上升沿后空间传输站未执行切换（元件仍停在槽 0）：" + String.join("；", diagnosis),
                "请对照实测区域尺寸、电量与空间元件绑定尺寸修正后再试"));
    }

    private ActionStatus waitTake(PlayerContext player) {
        Optional<MenuContent.Reading> readingOpt = menu.reading();
        if (readingOpt.isPresent()) {
            captureMenuStatus(player);
            MenuContent.Reading reading = readingOpt.get();
            int outputSlot = slotBySemantic(reading, "MACHINE_OUTPUT", 1);
            SlotSnapshot outSnap = snapshotFor(reading, outputSlot);
            if (outSnap == null || outSnap.isEmpty()) {
                produced = 1;
                complete = true;
                notes.add("空间互换已完成并收回空间存储元件");
                return enter(Stage.CLOSE_VERIFY);
            }
        }
        if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
        return closeVerifyWith(Problem.of(Problem.Kind.STUCK, "空间互换已生效，但未能从槽 1 收回完成元件"));
    }

    private ActionStatus closeMenu(TickContext context, Stage next) {
        if (current == null) current = menu.closing();
        ActionStatus status = current.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        current = null;
        menu = null;
        if (next == Stage.FINISHED) return finish(problem);
        return enter(next);
    }

    private void captureMenuStatus(PlayerContext player) {
        reads.openSpatialTransferMenu(player.localPlayer().containerMenu).ifPresent(status -> {
            lastMenuStatus = status;
            cacheSink.accept(status);
        });
    }

    private int slotBySemantic(MenuContent.Reading reading, String semantic, int fallbackIndex) {
        List<Integer> semanticSlots = compat.menuSlotsOf(semantic);
        for (int id : semanticSlots) {
            if (reading.containerSlotIds().contains(id)) return id;
        }
        return fallbackIndex < reading.containerSlotIds().size() ? reading.containerSlotIds().get(fallbackIndex) : -1;
    }

    private static SlotSnapshot snapshotFor(MenuContent.Reading reading, int slotId) {
        for (int i = 0; i < reading.containerSlotIds().size(); i++) {
            if (reading.containerSlotIds().get(i) == slotId) return reading.containerSnapshots().get(i);
        }
        return null;
    }

    private int findCarriedCellSlot(MenuContent.Reading reading) {
        for (int i = 0; i < reading.playerSnapshots().size(); i++) {
            SlotSnapshot snap = reading.playerSnapshots().get(i);
            if (!snap.isEmpty() && matchesWantedCell(snap)) {
                return reading.playerSlotIds().get(i);
            }
        }
        return -1;
    }

    private boolean matchesWantedCell(SlotSnapshot snap) {
        String id = BuiltInRegistries.ITEM.getKey(snap.stack().getItem()).toString();
        if (request.item() != null && !request.item().isBlank()) {
            return request.item().equals(id);
        }
        return SpatialTransfer.SPATIAL_CELL_ITEMS.contains(id) || reads.spatialCell(snap.stack()).isPresent();
    }

    private ActionStatus closeLoadWith(Problem reason) {
        problem = reason;
        return enter(Stage.CLOSE_LOAD);
    }

    private ActionStatus closeVerifyWith(Problem reason) {
        problem = reason;
        return enter(Stage.CLOSE_VERIFY);
    }

    private ActionStatus enter(Stage next) {
        if (current != null) current.close();
        current = null;
        stage = next;
        waited = 0;
        return ActionStatus.progressed();
    }

    private ActionStatus finish(Problem reason) {
        if (current != null) current.close();
        current = null;
        problem = reason;
        stage = Stage.FINISHED;
        return reason == null ? ActionStatus.done() : ActionStatus.failed(reason);
    }

    @Override public void pause() {
        if (current != null) current.pause();
        if (opening != null && menu == null) opening.pause();
    }

    @Override public void close() {
        if (current != null) current.close();
        if (menu != null && stage != Stage.FINISHED) menu.abandon();
        else if (opening != null && menu == null) opening.close();
    }

    @Override public Interruptibility interruptibility() {
        return current != null ? current.interruptibility() : Interruptibility.WORKING;
    }

    @Override public String describe() {
        return switch (stage) {
            case CLEAR_HAND, OPEN_LOAD, PREPARE_SLOTS, WAIT_LOAD, CLOSE_LOAD -> "向空间传输站装入空间元件";
            case LOCATE_SWITCH, APPROACH_SWITCH, RESET_LEVER, PULSE_GAP, TRIGGER_SWITCH -> "触发空间传输站红石上升沿脉冲";
            case WAIT_TRANSITION, OPEN_VERIFY, VERIFY_SLOT, WAIT_TAKE, CLOSE_VERIFY -> "核对空间互换结果并收回空间元件";
            case FINISHED -> "空间传输站操作结束";
        };
    }
}
