// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.extendedae;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * ExtendedAE 设备的通用界面配置动作集合：包含 AE2 配置按钮循环切换、
 * ExtendedAE 通用动作包发包与回读确认、按语义放取升级卡以及优先级子界面修改。
 */
final class ExtendedAeMenuActionSupport {

    /** 界面状态同步与包回传的等待上限（刻）。F 游戏事实。 */
    static final int SYNC_WAIT_TICKS = 40;

    private ExtendedAeMenuActionSupport() {}

    /** 打开整格机器方块的通用菜单打开器。 */
    static MenuOpening openBlock(BlockPos target, PlayerServices services, Permissions permissions) {
        return new ClientMenuOpening(target.immutable(), permissions, services.menuLayouts(),
                services.approach(), services.interactions(), services.context());
    }

    /** 解析 {@code upgrade.<index>} 设置键；不合法或越界时返回 -1。 */
    static int parseUpgradeSlot(String key, int maxSlots) {
        if (key == null || !key.startsWith("upgrade.")) return -1;
        try {
            int index = Integer.parseInt(key.substring("upgrade.".length()));
            return index >= 0 && index < maxSlots ? index : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    /** 打开界面并发送 AE2 {@code ConfigButtonPacket} 循环切换至目标值，确认回读后关窗。 */
    static final class ConfigCycleAction implements Action {

        private enum Stage { OPEN, CHECK, WAIT_CHANGE, CLOSE, DONE }

        private final MenuOpening opening;
        private final ExtendedAeCompat compat;
        private final String settingName;
        private final String wantedValue;
        private final int maxCycles;
        private final Supplier<Optional<String>> currentReader;
        private final Runnable cacheRecorder;
        private final String label;

        private Stage stage = Stage.OPEN;
        private OpenedMenu menu;
        private Action closing;
        private String valueBeforeSend;
        private int cycles;
        private int waited;
        private Problem problem;

        ConfigCycleAction(MenuOpening opening, ExtendedAeCompat compat, String settingName, String wantedValue,
                int maxCycles, Supplier<Optional<String>> currentReader, Runnable cacheRecorder, String label) {
            this.opening = Objects.requireNonNull(opening, "opening");
            this.compat = Objects.requireNonNull(compat, "compat");
            this.settingName = Objects.requireNonNull(settingName, "settingName");
            this.wantedValue = Objects.requireNonNull(wantedValue, "wantedValue");
            this.maxCycles = Math.max(1, maxCycles);
            this.currentReader = Objects.requireNonNull(currentReader, "currentReader");
            this.cacheRecorder = Objects.requireNonNull(cacheRecorder, "cacheRecorder");
            this.label = Objects.requireNonNull(label, "label");
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
            if (menu == null) return finish(Problem.of(Problem.Kind.TARGET_GONE, label + " 界面没打开"));
            stage = Stage.CHECK;
            waited = 0;
            return ActionStatus.progressed();
        }

        private ActionStatus checkAndSend() {
            Optional<String> current = currentReader.get();
            if (current.isEmpty()) {
                if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
                return beginClose(Problem.of(Problem.Kind.STUCK, label + " 界面已开但读不到配置项"));
            }
            cacheRecorder.run();
            if (current.get().equalsIgnoreCase(wantedValue)) return beginClose(null);
            if (cycles >= maxCycles) {
                return beginClose(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                        label + " 循环切换 " + maxCycles + " 次仍未切到 " + wantedValue + "（当前 " + current.get() + "）"));
            }
            if (!compat.sendConfigButton(settingName, false)) {
                return beginClose(Problem.of(Problem.Kind.REFUSED_BY_GAME, label + " 发送配置按钮包失败"));
            }
            valueBeforeSend = current.get();
            cycles++;
            waited = 0;
            stage = Stage.WAIT_CHANGE;
            return ActionStatus.progressed();
        }

        private ActionStatus waitChange() {
            Optional<String> current = currentReader.get();
            if (current.isPresent() && !current.get().equalsIgnoreCase(valueBeforeSend)) {
                cacheRecorder.run();
                if (current.get().equalsIgnoreCase(wantedValue)) return beginClose(null);
                stage = Stage.CHECK;
                waited = 0;
                return ActionStatus.progressed();
            }
            if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
            return beginClose(Problem.of(Problem.Kind.STUCK,
                    label + " 发送配置切换后界面值一直未变化（仍为 " + valueBeforeSend + "）"));
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

        @Override public void pause() {
            if (closing != null) closing.pause();
            else if (menu == null) opening.pause();
        }

        @Override public void close() {
            if (closing != null) closing.close();
            if (menu != null && stage != Stage.DONE) menu.abandon();
            else if (menu == null) opening.close();
        }

        @Override public Interruptibility interruptibility() { return Interruptibility.WORKING; }

        @Override public String describe() { return "调整 " + label + " 为 " + wantedValue; }
    }

    /** 打开界面后发送 ExtendedAE 通用动作包（{@code CEAEGenericPacket}），按条件核对生效后关窗。 */
    static final class GenericPacketAction implements Action {

        private enum Stage { OPEN, SEND, WAIT_CONFIRM, CLOSE, DONE }

        private final MenuOpening opening;
        private final BooleanSupplier readyCheck;
        private final BooleanSupplier alreadyDoneCheck;
        private final BooleanSupplier sender;
        private final BooleanSupplier confirmCheck;
        private final Runnable cacheRecorder;
        private final String label;

        private Stage stage = Stage.OPEN;
        private OpenedMenu menu;
        private Action closing;
        private int waited;
        private Problem problem;

        GenericPacketAction(MenuOpening opening, BooleanSupplier readyCheck, BooleanSupplier alreadyDoneCheck,
                BooleanSupplier sender, BooleanSupplier confirmCheck, Runnable cacheRecorder, String label) {
            this.opening = Objects.requireNonNull(opening, "opening");
            this.readyCheck = Objects.requireNonNull(readyCheck, "readyCheck");
            this.alreadyDoneCheck = Objects.requireNonNull(alreadyDoneCheck, "alreadyDoneCheck");
            this.sender = Objects.requireNonNull(sender, "sender");
            this.confirmCheck = Objects.requireNonNull(confirmCheck, "confirmCheck");
            this.cacheRecorder = Objects.requireNonNull(cacheRecorder, "cacheRecorder");
            this.label = Objects.requireNonNull(label, "label");
        }

        @Override public ActionStatus tick(TickContext tick) {
            return switch (stage) {
                case OPEN -> open(tick);
                case SEND -> send();
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
            if (menu == null) return finish(Problem.of(Problem.Kind.TARGET_GONE, label + " 界面没打开"));
            stage = Stage.SEND;
            waited = 0;
            return ActionStatus.progressed();
        }

        private ActionStatus send() {
            if (!readyCheck.getAsBoolean()) {
                if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
                return beginClose(Problem.of(Problem.Kind.STUCK, label + " 界面已开但同步状态未就绪"));
            }
            cacheRecorder.run();
            if (alreadyDoneCheck.getAsBoolean()) return beginClose(null);
            if (!sender.getAsBoolean()) {
                return beginClose(Problem.of(Problem.Kind.REFUSED_BY_GAME, label + " 发包失败"));
            }
            waited = 0;
            stage = Stage.WAIT_CONFIRM;
            return ActionStatus.progressed();
        }

        private ActionStatus waitConfirm() {
            if (confirmCheck.getAsBoolean()) {
                cacheRecorder.run();
                return beginClose(null);
            }
            if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
            cacheRecorder.run();
            return beginClose(Problem.of(Problem.Kind.STUCK, label + " 发包后未等到界面状态更新"));
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

        @Override public void pause() {
            if (closing != null) closing.pause();
            else if (menu == null) opening.pause();
        }

        @Override public void close() {
            if (closing != null) closing.close();
            if (menu != null && stage != Stage.DONE) menu.abandon();
            else if (menu == null) opening.close();
        }

        @Override public Interruptibility interruptibility() { return Interruptibility.WORKING; }

        @Override public String describe() { return label; }
    }

    /** 按语义槽（如 {@code UPGRADE}）放入或取出物品：自动跳过工具箱偏移并确认槽位变化后关窗。 */
    static final class SemanticSlotSwapAction implements Action {

        private enum Stage { OPEN, ACT, WAIT_CONFIRM, CLOSE, DONE }

        private final MenuOpening opening;
        private final ExtendedAeCompat compat;
        private final String semanticId;
        private final int semanticIndex;
        private final Predicate<SlotSnapshot> wantedMatcher;
        private final String wantedLabel;
        private final Runnable cacheRecorder;
        private final String label;

        private Stage stage = Stage.OPEN;
        private OpenedMenu menu;
        private Action closing;
        private int targetSlotId = -1;
        private boolean clickedTakeOut;
        private int waited;
        private Problem problem;

        SemanticSlotSwapAction(MenuOpening opening, ExtendedAeCompat compat, String semanticId, int semanticIndex,
                Predicate<SlotSnapshot> wantedMatcher, String wantedLabel, Runnable cacheRecorder, String label) {
            this.opening = Objects.requireNonNull(opening, "opening");
            this.compat = Objects.requireNonNull(compat, "compat");
            this.semanticId = Objects.requireNonNull(semanticId, "semanticId");
            this.semanticIndex = semanticIndex;
            this.wantedMatcher = wantedMatcher;
            this.wantedLabel = Objects.requireNonNull(wantedLabel, "wantedLabel");
            this.cacheRecorder = Objects.requireNonNull(cacheRecorder, "cacheRecorder");
            this.label = Objects.requireNonNull(label, "label");
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
            if (menu == null) return finish(Problem.of(Problem.Kind.TARGET_GONE, label + " 界面没打开"));
            stage = Stage.ACT;
            waited = 0;
            return ActionStatus.progressed();
        }

        private ActionStatus act() {
            Optional<MenuContent.Reading> reading = menu.reading();
            if (reading.isEmpty()) {
                if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
                return beginClose(Problem.of(Problem.Kind.STUCK, label + " 界面内容同步超时"));
            }
            List<Integer> semanticSlots = compat.enabledMenuSlotsOf(semanticId);
            if (semanticIndex < 0 || semanticIndex >= semanticSlots.size()) {
                return beginClose(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                        label + " 没有第 " + semanticIndex + " 个 " + semanticId + " 槽位"));
            }
            targetSlotId = semanticSlots.get(semanticIndex);
            SlotSnapshot current = snapshotOf(reading.get(), targetSlotId);
            if (matchesGoal(current)) {
                cacheRecorder.run();
                return beginClose(null);
            }
            if (!current.isEmpty()) {
                if (!menu.quickMove(targetSlotId)) {
                    if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
                    return beginClose(Problem.of(Problem.Kind.STUCK, "无法从 " + label + " 移出原占位物品"));
                }
                clickedTakeOut = true;
                waited = 0;
                stage = Stage.WAIT_CONFIRM;
                return ActionStatus.progressed();
            }
            int playerSlot = findMatchingPlayerSlot(reading.get());
            if (playerSlot < 0) {
                cacheRecorder.run();
                return beginClose(Problem.of(Problem.Kind.NEED_ITEM,
                        "背包里没有 " + wantedLabel + "，无法放入 " + label));
            }
            if (!menu.quickMove(playerSlot)) {
                if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
                return beginClose(Problem.of(Problem.Kind.STUCK, "无法将 " + wantedLabel + " 移入 " + label));
            }
            clickedTakeOut = false;
            waited = 0;
            stage = Stage.WAIT_CONFIRM;
            return ActionStatus.progressed();
        }

        private ActionStatus waitConfirm() {
            Optional<MenuContent.Reading> reading = menu.reading();
            if (reading.isPresent()) {
                SlotSnapshot current = snapshotOf(reading.get(), targetSlotId);
                if (matchesGoal(current)) {
                    cacheRecorder.run();
                    return beginClose(null);
                }
                if (clickedTakeOut && current.isEmpty() && wantedMatcher != null) {
                    stage = Stage.ACT;
                    waited = 0;
                    return ActionStatus.progressed();
                }
            }
            if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
            cacheRecorder.run();
            return beginClose(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                    label + " 未变成 " + wantedLabel + "（可能背包满或该槽不接受此物）"));
        }

        private boolean matchesGoal(SlotSnapshot snapshot) {
            if (wantedMatcher == null) return snapshot.isEmpty();
            return !snapshot.isEmpty() && wantedMatcher.test(snapshot);
        }

        private int findMatchingPlayerSlot(MenuContent.Reading reading) {
            for (int i = 0; i < reading.playerSnapshots().size(); i++) {
                SlotSnapshot snap = reading.playerSnapshots().get(i);
                if (!snap.isEmpty() && wantedMatcher.test(snap)) {
                    return reading.playerSlotIds().get(i);
                }
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

        @Override public void pause() {
            if (closing != null) closing.pause();
            else if (menu == null) opening.pause();
        }

        @Override public void close() {
            if (closing != null) closing.close();
            if (menu != null && stage != Stage.DONE) menu.abandon();
            else if (menu == null) opening.close();
        }

        @Override public Interruptibility interruptibility() { return Interruptibility.WORKING; }

        @Override public String describe() { return "调整 " + label + " 为 " + wantedLabel; }
    }

    /** 打开主界面 → 切入 {@code ae2:priority} 子界面 → 写入优先级 → 返回并关窗。 */
    static final class PrioritySubMenuAction implements Action {

        private enum Stage { OPEN, SWITCH_SUB, WAIT_SUB, SET_VALUE, WAIT_BACK, CLOSE, DONE }

        private final MenuOpening opening;
        private final ExtendedAeCompat compat;
        private final int wantedPriority;
        private final IntConsumer cacheRecorder;
        private final String label;

        private Stage stage = Stage.OPEN;
        private OpenedMenu menu;
        private Action closing;
        private int waited;
        private Problem problem;

        PrioritySubMenuAction(MenuOpening opening, ExtendedAeCompat compat, int wantedPriority,
                IntConsumer cacheRecorder, String label) {
            this.opening = Objects.requireNonNull(opening, "opening");
            this.compat = Objects.requireNonNull(compat, "compat");
            this.wantedPriority = wantedPriority;
            this.cacheRecorder = Objects.requireNonNull(cacheRecorder, "cacheRecorder");
            this.label = Objects.requireNonNull(label, "label");
        }

        @Override public ActionStatus tick(TickContext tick) {
            return switch (stage) {
                case OPEN -> open(tick);
                case SWITCH_SUB -> switchSub();
                case WAIT_SUB -> waitSub();
                case SET_VALUE -> setValue();
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
            if (menu == null) return finish(Problem.of(Problem.Kind.TARGET_GONE, label + " 界面没打开"));
            menu.followPageSwaps((from, to) -> true);
            stage = Stage.SWITCH_SUB;
            return ActionStatus.progressed();
        }

        private ActionStatus switchSub() {
            if (!compat.openSubMenu("ae2:priority")) {
                return beginClose(Problem.of(Problem.Kind.REFUSED_BY_GAME, label + " 无法打开优先级子界面"));
            }
            waited = 0;
            stage = Stage.WAIT_SUB;
            return ActionStatus.progressed();
        }

        private ActionStatus waitSub() {
            if (compat.openPriorityMenu().isPresent()) {
                stage = Stage.SET_VALUE;
                return ActionStatus.progressed();
            }
            if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
            return beginClose(Problem.of(Problem.Kind.STUCK, label + " 切换优先级子界面超时"));
        }

        private ActionStatus setValue() {
            Optional<Integer> current = compat.openPriorityMenu();
            if (current.isEmpty()) {
                return beginClose(Problem.of(Problem.Kind.TARGET_GONE, label + " 优先级子界面已关闭"));
            }
            if (current.get() != wantedPriority && !compat.sendSetPriority(wantedPriority)) {
                return beginClose(Problem.of(Problem.Kind.REFUSED_BY_GAME, label + " 发送优先级修改失败"));
            }
            cacheRecorder.accept(wantedPriority);
            compat.returnToParentMenu();
            waited = 0;
            stage = Stage.WAIT_BACK;
            return ActionStatus.progressed();
        }

        private ActionStatus waitBack() {
            if (compat.openPriorityMenu().isEmpty() || ++waited > SYNC_WAIT_TICKS) {
                return beginClose(null);
            }
            return ActionStatus.running();
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

        @Override public void pause() {
            if (closing != null) closing.pause();
            else if (menu == null) opening.pause();
        }

        @Override public void close() {
            if (closing != null) closing.close();
            if (menu != null && stage != Stage.DONE) menu.abandon();
            else if (menu == null) opening.close();
        }

        @Override public Interruptibility interruptibility() { return Interruptibility.WORKING; }

        @Override public String describe() { return "设置 " + label + " 优先级为 " + wantedPriority; }
    }
}
