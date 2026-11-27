// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.advancedae;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 反应仓（advanced_ae:reaction_chamber）界面配置动作集合：
 * <ul>
 * <li>{@link AutoExportAction}：开窗发 AE2 ConfigButtonPacket 切换自动输出并核对回读；</li>
 * <li>{@link UpgradeSlotAction}：按 UPGRADE 槽位语义放取加速卡，免疫网络工具箱偏移；</li>
 * <li>{@link FlushFluidAction}：开窗调用 clearFluid / clearFluidOut 清空输入或输出流体槽；</li>
 * <li>{@link OutputSideAction}：开窗调用 configureOutput 进入 OutputDirectionMenu 子菜单切换相对六面输出并返回。</li>
 * </ul>
 */
final class ReactionChamberConfigActions {

    /** 单次界面同步等待上限（刻）。F 游戏事实。 */
    static final int SYNC_WAIT_TICKS = 40;
    /** 无服务端只读通道时清罐发包后的短暂观察窗口（刻），避免在未刷新的客户端 fluidInv 上死等超时。 */
    static final int CLIENT_ONLY_SETTLE_TICKS = 6;
    /** 服务端只读通道已开放但首次读数未归零时的重问间隔（刻）。 */
    static final int SERVER_RETRY_INTERVAL_TICKS = 10;

    private ReactionChamberConfigActions() {
    }

    /** 切换反应仓自动输出开关（yes / no）。 */
    static final class AutoExportAction implements Action {

        private enum Stage { OPEN, CHECK, WAIT_CHANGE, CLOSE, DONE }

        private final MenuOpening opening;
        private final AdvancedAeCompat compat;
        private final String wanted;
        private final Consumer<AdvancedAeReads.ChamberMenuView> cacheRecorder;

        private Stage stage = Stage.OPEN;
        private OpenedMenu menu;
        private Action closing;
        private String beforeSend;
        private int cycles;
        private int waited;
        private Problem problem;

        AutoExportAction(MenuOpening opening, AdvancedAeCompat compat, String wanted,
                         Consumer<AdvancedAeReads.ChamberMenuView> cacheRecorder) {
            this.opening = Objects.requireNonNull(opening, "opening");
            this.compat = Objects.requireNonNull(compat, "compat");
            this.wanted = Objects.requireNonNull(wanted, "wanted");
            this.cacheRecorder = Objects.requireNonNull(cacheRecorder, "cacheRecorder");
        }

        @Override public ActionStatus tick(TickContext tick) {
            return switch (stage) {
                case OPEN -> open(tick);
                case CHECK -> checkAndSend();
                case WAIT_CHANGE -> waitChange();
                case CLOSE -> closeMenu(tick);
                case DONE -> problem == null ? ActionStatus.done() : ActionStatus.failed(problem);
            };
        }

        private ActionStatus open(TickContext tick) {
            ActionStatus status = opening.tick(tick);
            if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
            if (!(status instanceof ActionStatus.Done)) return status;
            menu = opening.opened().orElse(null);
            if (menu == null) return finish(Problem.of(Problem.Kind.TARGET_GONE, "反应仓界面没打开"));
            stage = Stage.CHECK;
            waited = 0;
            return ActionStatus.progressed();
        }

        private ActionStatus checkAndSend() {
            Optional<AdvancedAeReads.ChamberMenuView> view = compat.openChamberMenu();
            if (view.isEmpty()) {
                if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
                return beginClose(Problem.of(Problem.Kind.STUCK, "反应仓界面已开但读不到自动输出状态"));
            }
            cacheRecorder.accept(view.get());
            if (view.get().autoExport().equalsIgnoreCase(wanted)) return beginClose(null);
            if (cycles >= 3) {
                return beginClose(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                        "反应仓自动输出连续切换后仍为 " + view.get().autoExport() + "，未切到 " + wanted));
            }
            if (!compat.sendAutoExportToggle()) {
                return beginClose(Problem.of(Problem.Kind.REFUSED_BY_GAME, "无法发送反应仓自动输出切换包"));
            }
            beforeSend = view.get().autoExport();
            cycles++;
            waited = 0;
            stage = Stage.WAIT_CHANGE;
            return ActionStatus.progressed();
        }

        private ActionStatus waitChange() {
            Optional<AdvancedAeReads.ChamberMenuView> view = compat.openChamberMenu();
            if (view.isPresent() && !view.get().autoExport().equalsIgnoreCase(beforeSend)) {
                cacheRecorder.accept(view.get());
                if (view.get().autoExport().equalsIgnoreCase(wanted)) return beginClose(null);
                stage = Stage.CHECK;
                waited = 0;
                return ActionStatus.progressed();
            }
            if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
            return beginClose(Problem.of(Problem.Kind.STUCK,
                    "发送自动输出切换后反应仓状态未变化（仍为 " + beforeSend + "）"));
        }

        private ActionStatus closeMenu(TickContext tick) {
            if (closing == null) closing = menu.closing();
            ActionStatus status = closing.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            closing = null;
            return finish(problem);
        }

        private ActionStatus beginClose(Problem cause) {
            problem = cause;
            stage = Stage.CLOSE;
            return ActionStatus.progressed();
        }

        private ActionStatus finish(Problem cause) {
            problem = cause;
            stage = Stage.DONE;
            return cause == null ? ActionStatus.done() : ActionStatus.failed(cause);
        }

        @Override public void pause() { if (closing != null) closing.pause(); else if (menu == null) opening.pause(); }

        @Override public void close() {
            if (closing != null) closing.close();
            if (menu != null && stage != Stage.DONE) menu.abandon();
            else if (menu == null) opening.close();
        }

        @Override public Interruptibility interruptibility() { return Interruptibility.WORKING; }

        @Override public String describe() { return "调整反应仓 auto_export 为 " + wanted; }
    }

    /** 按 UPGRADE 槽位语义向反应仓 0..3 号升级槽放取加速卡（ae2:speed_card / empty）。 */
    static final class UpgradeSlotAction implements Action {

        private enum Stage { OPEN, ACT, WAIT_CONFIRM, CLOSE, DONE }

        private final MenuOpening opening;
        private final AdvancedAeCompat compat;
        private final int upgradeIndex;
        private final String wantedItemId;
        private final Consumer<AdvancedAeReads.ChamberMenuView> cacheRecorder;

        private Stage stage = Stage.OPEN;
        private OpenedMenu menu;
        private Action closing;
        private int targetSlotId = -1;
        private boolean tookOut;
        private int waited;
        private Problem problem;

        UpgradeSlotAction(MenuOpening opening, AdvancedAeCompat compat, int upgradeIndex,
                          String wantedItemId, Consumer<AdvancedAeReads.ChamberMenuView> cacheRecorder) {
            this.opening = Objects.requireNonNull(opening, "opening");
            this.compat = Objects.requireNonNull(compat, "compat");
            this.upgradeIndex = upgradeIndex;
            this.wantedItemId = wantedItemId;
            this.cacheRecorder = Objects.requireNonNull(cacheRecorder, "cacheRecorder");
        }

        @Override public ActionStatus tick(TickContext tick) {
            return switch (stage) {
                case OPEN -> open(tick);
                case ACT -> act();
                case WAIT_CONFIRM -> waitConfirm();
                case CLOSE -> closeMenu(tick);
                case DONE -> problem == null ? ActionStatus.done() : ActionStatus.failed(problem);
            };
        }

        private ActionStatus open(TickContext tick) {
            ActionStatus status = opening.tick(tick);
            if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
            if (!(status instanceof ActionStatus.Done)) return status;
            menu = opening.opened().orElse(null);
            if (menu == null) return finish(Problem.of(Problem.Kind.TARGET_GONE, "反应仓界面没打开"));
            stage = Stage.ACT;
            waited = 0;
            return ActionStatus.progressed();
        }

        private ActionStatus act() {
            Optional<MenuContent.Reading> reading = menu.reading();
            if (reading.isEmpty()) {
                if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
                return beginClose(Problem.of(Problem.Kind.STUCK, "反应仓界面内容同步超时"));
            }
            List<Integer> upgradeSlots = compat.menuSlotsOf("UPGRADE");
            if (upgradeIndex < 0 || upgradeIndex >= upgradeSlots.size()) {
                return beginClose(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                        "反应仓没有第 " + upgradeIndex + " 号 UPGRADE 升级槽"));
            }
            targetSlotId = upgradeSlots.get(upgradeIndex);
            SlotSnapshot current = snapshotOf(reading.get(), targetSlotId);
            if (matchesGoal(current)) {
                recordMenuView();
                return beginClose(null);
            }
            if (!current.isEmpty()) {
                if (!menu.quickMove(targetSlotId)) {
                    if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
                    return beginClose(Problem.of(Problem.Kind.STUCK, "无法从反应仓升级槽 " + upgradeIndex + " 移出物品"));
                }
                tookOut = true;
                waited = 0;
                stage = Stage.WAIT_CONFIRM;
                return ActionStatus.progressed();
            }
            int playerSlot = findPlayerSlot(reading.get(), wantedItemId);
            if (playerSlot < 0) {
                recordMenuView();
                return beginClose(Problem.of(Problem.Kind.NEED_ITEM,
                        "背包里没有 " + wantedItemId + "，无法放入反应仓升级槽 " + upgradeIndex));
            }
            if (!menu.quickMove(playerSlot)) {
                if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
                return beginClose(Problem.of(Problem.Kind.STUCK, "无法将 " + wantedItemId + " 移入反应仓升级槽"));
            }
            tookOut = false;
            waited = 0;
            stage = Stage.WAIT_CONFIRM;
            return ActionStatus.progressed();
        }

        private ActionStatus waitConfirm() {
            Optional<MenuContent.Reading> reading = menu.reading();
            if (reading.isPresent()) {
                SlotSnapshot current = snapshotOf(reading.get(), targetSlotId);
                if (matchesGoal(current)) {
                    recordMenuView();
                    return beginClose(null);
                }
                if (tookOut && current.isEmpty() && wantedItemId != null) {
                    stage = Stage.ACT;
                    waited = 0;
                    return ActionStatus.progressed();
                }
            }
            if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
            recordMenuView();
            return beginClose(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                    "反应仓升级槽 " + upgradeIndex + " 未变成 " + (wantedItemId == null ? "空位" : wantedItemId)));
        }

        private boolean matchesGoal(SlotSnapshot snapshot) {
            if (wantedItemId == null) return snapshot.isEmpty();
            return !snapshot.isEmpty() && wantedItemId.equals(snapshot.key());
        }

        private void recordMenuView() {
            compat.openChamberMenu().ifPresent(cacheRecorder);
        }

        private static int findPlayerSlot(MenuContent.Reading reading, String itemId) {
            for (int i = 0; i < reading.playerSnapshots().size(); i++) {
                SlotSnapshot s = reading.playerSnapshots().get(i);
                if (!s.isEmpty() && itemId.equals(s.key())) return reading.playerSlotIds().get(i);
            }
            return -1;
        }

        private static SlotSnapshot snapshotOf(MenuContent.Reading reading, int slotId) {
            for (int i = 0; i < reading.containerSlotIds().size(); i++) {
                if (reading.containerSlotIds().get(i) == slotId) return reading.containerSnapshots().get(i);
            }
            return SlotSnapshot.empty();
        }

        private ActionStatus closeMenu(TickContext tick) {
            if (closing == null) closing = menu.closing();
            ActionStatus status = closing.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            closing = null;
            return finish(problem);
        }

        private ActionStatus beginClose(Problem cause) {
            problem = cause;
            stage = Stage.CLOSE;
            return ActionStatus.progressed();
        }

        private ActionStatus finish(Problem cause) {
            problem = cause;
            stage = Stage.DONE;
            return cause == null ? ActionStatus.done() : ActionStatus.failed(cause);
        }

        @Override public void pause() { if (closing != null) closing.pause(); else if (menu == null) opening.pause(); }

        @Override public void close() {
            if (closing != null) closing.close();
            if (menu != null && stage != Stage.DONE) menu.abandon();
            else if (menu == null) opening.close();
        }

        @Override public Interruptibility interruptibility() { return Interruptibility.WORKING; }

        @Override public String describe() {
            return "调整反应仓 upgrade." + upgradeIndex + " 为 " + (wantedItemId == null ? "empty" : wantedItemId);
        }
    }

    /** 开窗清空反应仓输入流体槽（槽 1）或输出流体槽（槽 0），并结合服务端权威读数或观察窗口稳妥收尾。 */
    static final class FlushFluidAction implements Action {

        private enum Stage { OPEN, SEND, WAIT_EMPTY, CLOSE, DONE }

        private final MenuOpening opening;
        private final AdvancedAeCompat compat;
        private final boolean inputTank;
        private final BooleanSupplier serverAvailable;
        private final Supplier<Optional<AdvancedAeReads.TankSlot>> serverTankReader;
        private final Runnable invalidateServer;
        private final Consumer<AdvancedAeReads.ChamberMenuView> cacheRecorder;
        private final Runnable markFlushed;

        private Stage stage = Stage.OPEN;
        private OpenedMenu menu;
        private Action closing;
        private boolean wasClientEmptyBeforeSend;
        private int waited;
        private Problem problem;

        FlushFluidAction(MenuOpening opening, AdvancedAeCompat compat, boolean inputTank,
                         BooleanSupplier serverAvailable,
                         Supplier<Optional<AdvancedAeReads.TankSlot>> serverTankReader,
                         Runnable invalidateServer,
                         Consumer<AdvancedAeReads.ChamberMenuView> cacheRecorder,
                         Runnable markFlushed) {
            this.opening = Objects.requireNonNull(opening, "opening");
            this.compat = Objects.requireNonNull(compat, "compat");
            this.inputTank = inputTank;
            this.serverAvailable = Objects.requireNonNull(serverAvailable, "serverAvailable");
            this.serverTankReader = Objects.requireNonNull(serverTankReader, "serverTankReader");
            this.invalidateServer = Objects.requireNonNull(invalidateServer, "invalidateServer");
            this.cacheRecorder = Objects.requireNonNull(cacheRecorder, "cacheRecorder");
            this.markFlushed = Objects.requireNonNull(markFlushed, "markFlushed");
        }

        @Override public ActionStatus tick(TickContext tick) {
            return switch (stage) {
                case OPEN -> open(tick);
                case SEND -> sendFlush();
                case WAIT_EMPTY -> waitEmpty();
                case CLOSE -> closeMenu(tick);
                case DONE -> problem == null ? ActionStatus.done() : ActionStatus.failed(problem);
            };
        }

        private ActionStatus open(TickContext tick) {
            ActionStatus status = opening.tick(tick);
            if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
            if (!(status instanceof ActionStatus.Done)) return status;
            menu = opening.opened().orElse(null);
            if (menu == null) return finish(Problem.of(Problem.Kind.TARGET_GONE, "反应仓界面没打开"));
            stage = Stage.SEND;
            waited = 0;
            return ActionStatus.progressed();
        }

        private ActionStatus sendFlush() {
            Optional<AdvancedAeReads.ChamberMenuView> view = compat.openChamberMenu();
            if (view.isEmpty()) {
                if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
                return beginClose(Problem.of(Problem.Kind.STUCK, "反应仓界面已开但读不到菜单状态"));
            }
            cacheRecorder.accept(view.get());
            wasClientEmptyBeforeSend = tankEmpty(view.get());
            // 客户端 ReactionChamberEntity.fluidInv 仅在有电运行触发 markForUpdate 时刷新，可能滞后呈空槽；
            // 服务端 clearFluid / clearFluidOut 幂等，因此不再凭滞后的客户端空槽跳过发包。
            boolean sent = inputTank ? compat.sendClearFluidIn() : compat.sendClearFluidOut();
            if (!sent) {
                return beginClose(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                        "无法发送清空反应仓" + (inputTank ? "输入" : "输出") + "流体槽动作"));
            }
            invalidateServer.run();
            waited = 0;
            stage = Stage.WAIT_EMPTY;
            return ActionStatus.progressed();
        }

        private ActionStatus waitEmpty() {
            // 有服务端只读通道时，以清罐后重问的服务端权威液量归零为准，不受未刷新的客户端 fluidInv 拖累。
            if (serverAvailable.getAsBoolean()) {
                Optional<AdvancedAeReads.TankSlot> serverTank = serverTankReader.get();
                if (serverTank.isPresent()) {
                    if (serverTank.get().isEmpty()) {
                        markFlushed.run();
                        return beginClose(null);
                    }
                    if (waited > 0 && waited % SERVER_RETRY_INTERVAL_TICKS == 0) {
                        invalidateServer.run();
                    }
                }
                if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
                return beginClose(Problem.of(Problem.Kind.STUCK,
                        "发送清空动作后服务端显示反应仓" + (inputTank ? "输入" : "输出") + "流体槽未在期限内归零"));
            }
            // 无服务端通道时：若客户端液槽由非空变为已空则立即确认；否则短暂观察后按已发包稳妥收尾，不死等 40 刻。
            Optional<AdvancedAeReads.ChamberMenuView> view = compat.openChamberMenu();
            if (!wasClientEmptyBeforeSend && view.isPresent() && tankEmpty(view.get())) {
                markFlushed.run();
                return beginClose(null);
            }
            if (++waited >= CLIENT_ONLY_SETTLE_TICKS) {
                markFlushed.run();
                return beginClose(null);
            }
            return ActionStatus.running();
        }

        private boolean tankEmpty(AdvancedAeReads.ChamberMenuView view) {
            return inputTank ? view.inputFluid().isEmpty() : view.outputFluid().isEmpty();
        }

        private ActionStatus closeMenu(TickContext tick) {
            if (closing == null) closing = menu.closing();
            ActionStatus status = closing.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            closing = null;
            return finish(problem);
        }

        private ActionStatus beginClose(Problem cause) {
            problem = cause;
            stage = Stage.CLOSE;
            return ActionStatus.progressed();
        }

        private ActionStatus finish(Problem cause) {
            problem = cause;
            stage = Stage.DONE;
            return cause == null ? ActionStatus.done() : ActionStatus.failed(cause);
        }

        @Override public void pause() { if (closing != null) closing.pause(); else if (menu == null) opening.pause(); }

        @Override public void close() {
            if (closing != null) closing.close();
            if (menu != null && stage != Stage.DONE) menu.abandon();
            else if (menu == null) opening.close();
        }

        @Override public Interruptibility interruptibility() { return Interruptibility.WORKING; }

        @Override public String describe() {
            return "清空反应仓" + (inputTank ? "输入" : "输出") + "流体槽";
        }
    }

    /** 开窗进入 OutputDirectionMenu 子菜单切换指定相对输出面并确认回读后返回关窗。 */
    static final class OutputSideAction implements Action {

        private enum Stage { OPEN, OPEN_SUB, WAIT_SUB, TOGGLE, WAIT_CONFIRM, WAIT_BACK, CLOSE, DONE }

        private final MenuOpening opening;
        private final AdvancedAeCompat compat;
        private final String relativeSide;
        private final boolean wantedEnabled;
        private final Consumer<Set<String>> sidesRecorder;

        private Stage stage = Stage.OPEN;
        private OpenedMenu menu;
        private Action closing;
        private int waited;
        private Problem problem;

        OutputSideAction(MenuOpening opening, AdvancedAeCompat compat, String relativeSide,
                         boolean wantedEnabled, Consumer<Set<String>> sidesRecorder) {
            this.opening = Objects.requireNonNull(opening, "opening");
            this.compat = Objects.requireNonNull(compat, "compat");
            this.relativeSide = Objects.requireNonNull(relativeSide, "relativeSide");
            this.wantedEnabled = wantedEnabled;
            this.sidesRecorder = Objects.requireNonNull(sidesRecorder, "sidesRecorder");
        }

        @Override public ActionStatus tick(TickContext tick) {
            return switch (stage) {
                case OPEN -> open(tick);
                case OPEN_SUB -> openSub();
                case WAIT_SUB -> waitSub();
                case TOGGLE -> toggleSide();
                case WAIT_CONFIRM -> waitConfirm();
                case WAIT_BACK -> waitBack();
                case CLOSE -> closeMenu(tick);
                case DONE -> problem == null ? ActionStatus.done() : ActionStatus.failed(problem);
            };
        }

        private ActionStatus open(TickContext tick) {
            ActionStatus status = opening.tick(tick);
            if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
            if (!(status instanceof ActionStatus.Done)) return status;
            menu = opening.opened().orElse(null);
            if (menu == null) return finish(Problem.of(Problem.Kind.TARGET_GONE, "反应仓界面没打开"));
            // 声明跟随主界面与输出方向子菜单之间的换页，防止子菜单切换触发界面丢失误判。
            menu.followPageSwaps((from, to) -> true);
            stage = Stage.OPEN_SUB;
            return ActionStatus.progressed();
        }

        private ActionStatus openSub() {
            if (!compat.sendOpenOutputDirection()) {
                return beginClose(Problem.of(Problem.Kind.REFUSED_BY_GAME, "无法打开反应仓输出方向子菜单"));
            }
            waited = 0;
            stage = Stage.WAIT_SUB;
            return ActionStatus.progressed();
        }

        private ActionStatus waitSub() {
            Optional<Set<String>> sides = compat.openOutputDirections();
            if (sides.isPresent()) {
                sidesRecorder.accept(sides.get());
                stage = Stage.TOGGLE;
                return ActionStatus.progressed();
            }
            if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
            return beginClose(Problem.of(Problem.Kind.STUCK, "打开反应仓输出方向子菜单同步超时"));
        }

        private ActionStatus toggleSide() {
            Optional<Set<String>> sides = compat.openOutputDirections();
            if (sides.isEmpty()) {
                return beginClose(Problem.of(Problem.Kind.TARGET_GONE, "反应仓输出方向子菜单已关闭"));
            }
            sidesRecorder.accept(sides.get());
            if (sides.get().contains(relativeSide) == wantedEnabled) {
                compat.sendReturnToParentMenu();
                waited = 0;
                stage = Stage.WAIT_BACK;
                return ActionStatus.progressed();
            }
            if (!compat.sendToggleOutputSide(relativeSide)) {
                return beginClose(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                        "无法在输出方向子菜单切换相对面 " + relativeSide));
            }
            waited = 0;
            stage = Stage.WAIT_CONFIRM;
            return ActionStatus.progressed();
        }

        private ActionStatus waitConfirm() {
            Optional<Set<String>> sides = compat.openOutputDirections();
            if (sides.isPresent()) {
                sidesRecorder.accept(sides.get());
                if (sides.get().contains(relativeSide) == wantedEnabled) {
                    compat.sendReturnToParentMenu();
                    waited = 0;
                    stage = Stage.WAIT_BACK;
                    return ActionStatus.progressed();
                }
            }
            if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
            return beginClose(Problem.of(Problem.Kind.STUCK,
                    "切换反应仓输出方向 " + relativeSide + " 为 " + wantedEnabled + " 后回读超时"));
        }

        private ActionStatus waitBack() {
            if (compat.openOutputDirections().isEmpty()) {
                return beginClose(null);
            }
            if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
            return beginClose(null);
        }

        private ActionStatus closeMenu(TickContext tick) {
            if (closing == null) closing = menu.closing();
            ActionStatus status = closing.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            closing = null;
            return finish(problem);
        }

        private ActionStatus beginClose(Problem cause) {
            problem = cause;
            stage = Stage.CLOSE;
            return ActionStatus.progressed();
        }

        private ActionStatus finish(Problem cause) {
            problem = cause;
            stage = Stage.DONE;
            return cause == null ? ActionStatus.done() : ActionStatus.failed(cause);
        }

        @Override public void pause() { if (closing != null) closing.pause(); else if (menu == null) opening.pause(); }

        @Override public void close() {
            if (closing != null) closing.close();
            if (menu != null && stage != Stage.DONE) menu.abandon();
            else if (menu == null) opening.close();
        }

        @Override public Interruptibility interruptibility() { return Interruptibility.WORKING; }

        @Override public String describe() {
            return "设置反应仓输出面 " + relativeSide + " 为 " + wantedEnabled;
        }
    }
}
