// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.machine;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2MenuWork;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * AE2 工作方块与部件的通用界面操作支持：提供方块或部件菜单打开工厂、
 * 按 ConfigButtonPacket 循环切换枚举设置、按槽位语义放取物品或升级卡，
 * 以及打开 PriorityMenu 子界面修改优先级并返回关窗等通用界面动作。
 */
final class Ae2MenuActionSupport {

    /** 每次界面操作或同步的等待上限（刻）。数值定在 {@link Ae2MenuWork} 一处。 */
    static final int SYNC_WAIT_TICKS = Ae2MenuWork.SYNC_WAIT_TICKS;

    private Ae2MenuActionSupport() {
    }

    /** 打开整格方块或线缆某一面部件的通用菜单打开器。 */
    static MenuOpening openBlockOrPart(BlockPos host, Direction side, Ae2MachineReads reads,
            PlayerServices services, Permissions permissions) {
        if (side == null) {
            return new ClientMenuOpening(host, permissions, services.menuLayouts(), services.approach(),
                    services.interactions(), services.context());
        }
        PlayerContext current = services.context().get();
        AABB box = current == null || current.level() == null ? null
                : reads.partBox(current.level(), host, side).orElse(null);
        return new ClientMenuOpening(host, box, permissions, services.menuLayouts(), services.approach(),
                services.interactions(), services.context());
    }

    /**
     * 打开 AE2 界面并发送 ConfigButtonPacket 循环切至目标枚举值，核对 @GuiSync 读回命中后刷新缓存再关窗。
     */
    static final class ConfigCycleAction extends Ae2MenuWork {

        private enum Step { CHECK, WAIT_CHANGE }

        private final Ae2Compat compat;
        private final String settingName;
        private final String wantedValue;
        private final int maxCycles;
        private final Supplier<Optional<String>> currentReader;
        private final Runnable cacheRecorder;
        private final String label;

        private Step step = Step.CHECK;
        private String valueBeforeSend;
        private int cycles;

        ConfigCycleAction(MenuOpening opening, Ae2Compat compat, String settingName, String wantedValue,
                int maxCycles, Supplier<Optional<String>> currentReader, Runnable cacheRecorder, String label) {
            super(opening, label + " 界面没打开");
            this.compat = Objects.requireNonNull(compat, "compat");
            this.settingName = Objects.requireNonNull(settingName, "settingName");
            this.wantedValue = Objects.requireNonNull(wantedValue, "wantedValue");
            this.maxCycles = Math.max(1, maxCycles);
            this.currentReader = Objects.requireNonNull(currentReader, "currentReader");
            this.cacheRecorder = Objects.requireNonNull(cacheRecorder, "cacheRecorder");
            this.label = Objects.requireNonNull(label, "label");
        }

        @Override protected ActionStatus work(TickContext tick) {
            return switch (step) {
                case CHECK -> checkAndSend();
                case WAIT_CHANGE -> waitChange();
            };
        }

        // 先等菜单字段同步就绪；已是目标值则记缓存关窗，否则发一次 ConfigButtonPacket 并等新值回传。
        private ActionStatus checkAndSend() {
            Optional<String> current = currentReader.get();
            if (current.isEmpty()) {
                if (!syncWaitElapsed()) return ActionStatus.running();
                return closeWith(Problem.of(Problem.Kind.STUCK, label + " 界面已开但读不到配置字段"));
            }
            cacheRecorder.run();
            if (current.get().equalsIgnoreCase(wantedValue)) {
                return closeWith(null);
            }
            if (cycles >= maxCycles) {
                return closeWith(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                        label + " 循环切换 " + maxCycles + " 次仍未切到 " + wantedValue + "（当前为 " + current.get() + "）"));
            }
            if (!compat.sendConfigButton(settingName, false)) {
                return closeWith(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                        label + " 无法发送配置按钮包 " + settingName));
            }
            valueBeforeSend = current.get();
            cycles++;
            resetWait();
            step = Step.WAIT_CHANGE;
            return ActionStatus.progressed();
        }

        private ActionStatus waitChange() {
            Optional<String> current = currentReader.get();
            if (current.isPresent() && !current.get().equalsIgnoreCase(valueBeforeSend)) {
                cacheRecorder.run();
                if (current.get().equalsIgnoreCase(wantedValue)) return closeWith(null);
                step = Step.CHECK;
                resetWait();
                return ActionStatus.progressed();
            }
            if (!syncWaitElapsed()) return ActionStatus.running();
            return closeWith(Problem.of(Problem.Kind.STUCK,
                    label + " 发送配置切换后界面数值一直没变化（仍为 " + valueBeforeSend + "）"));
        }

        @Override protected String doing() {
            return "调整 " + label + " 配置为 " + wantedValue;
        }
    }

    /**
     * 按 AE2 槽位语义往指定槽（如 UPGRADE[i]、STORAGE_CELL[0]、ENCODED_PATTERN[0]）放入或取出物品：
     * 免疫网络工具箱动态插槽偏移，核对目标槽变化后刷新缓存再关窗。
     */
    static final class SemanticSlotSwapAction extends Ae2MenuWork {

        private enum Step { ACT, WAIT_CONFIRM }

        private final Ae2Compat compat;
        private final String semanticId;
        private final int semanticIndex;
        /** 期望放入的物品匹配器；为空表示取出该槽原有物品。 */
        private final BiPredicate<SlotSnapshot, PlayerContext> wantedMatcher;
        private final String wantedLabel;
        private final Runnable cacheRecorder;
        private final String label;

        private Step step = Step.ACT;
        private int targetSlotId = -1;
        private boolean clickedTakeOut;

        SemanticSlotSwapAction(MenuOpening opening, Ae2Compat compat, String semanticId, int semanticIndex,
                BiPredicate<SlotSnapshot, PlayerContext> wantedMatcher, String wantedLabel,
                Runnable cacheRecorder, String label) {
            super(opening, label + " 界面没打开");
            this.compat = Objects.requireNonNull(compat, "compat");
            this.semanticId = Objects.requireNonNull(semanticId, "semanticId");
            this.semanticIndex = semanticIndex;
            this.wantedMatcher = wantedMatcher;
            this.wantedLabel = Objects.requireNonNull(wantedLabel, "wantedLabel");
            this.cacheRecorder = Objects.requireNonNull(cacheRecorder, "cacheRecorder");
            this.label = Objects.requireNonNull(label, "label");
        }

        @Override protected ActionStatus work(TickContext tick) {
            return switch (step) {
                case ACT -> act(tick.player());
                case WAIT_CONFIRM -> waitConfirm(tick.player());
            };
        }

        // 先定位语义槽：若是取出或槽内已有其他物，先快速移回背包；再把背包匹配物快速移入机器。
        private ActionStatus act(PlayerContext player) {
            Optional<MenuContent.Reading> reading = menu().reading();
            if (reading.isEmpty()) {
                if (!syncWaitElapsed()) return ActionStatus.running();
                return closeWith(Problem.of(Problem.Kind.STUCK, label + " 界面内容同步超时"));
            }
            List<Integer> semanticSlots = compat.menuSlotsOf(semanticId);
            if (semanticIndex < 0 || semanticIndex >= semanticSlots.size()) {
                return closeWith(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                        label + " 没有第 " + semanticIndex + " 个 " + semanticId + " 槽位"));
            }
            targetSlotId = semanticSlots.get(semanticIndex);
            SlotSnapshot current = snapshotOf(reading.get(), targetSlotId);
            if (matchesGoal(current, player)) {
                cacheRecorder.run();
                return closeWith(null);
            }
            if (!current.isEmpty()) {
                if (!menu().quickMove(targetSlotId)) {
                    if (!syncWaitElapsed()) return ActionStatus.running();
                    return closeWith(Problem.of(Problem.Kind.STUCK, "无法从 " + label + " 移出原占位物品"));
                }
                clickedTakeOut = true;
                resetWait();
                step = Step.WAIT_CONFIRM;
                return ActionStatus.progressed();
            }
            // 目标槽已空且我们要放入指定物品：在玩家背包找一件匹配物移进去。
            int playerSlot = findMatchingPlayerSlot(reading.get(), player);
            if (playerSlot < 0) {
                cacheRecorder.run();
                return closeWith(Problem.of(Problem.Kind.NEED_ITEM,
                        "背包里没有 " + wantedLabel + "，无法放入 " + label));
            }
            if (!menu().quickMove(playerSlot)) {
                if (!syncWaitElapsed()) return ActionStatus.running();
                return closeWith(Problem.of(Problem.Kind.STUCK, "无法将 " + wantedLabel + " 移入 " + label));
            }
            clickedTakeOut = false;
            resetWait();
            step = Step.WAIT_CONFIRM;
            return ActionStatus.progressed();
        }

        private ActionStatus waitConfirm(PlayerContext player) {
            Optional<MenuContent.Reading> reading = menu().reading();
            if (reading.isPresent()) {
                SlotSnapshot current = snapshotOf(reading.get(), targetSlotId);
                if (matchesGoal(current, player)) {
                    cacheRecorder.run();
                    return closeWith(null);
                }
                // 先清掉旧占位物后槽变空了，若还要放新物则回到放入步骤把新物移入。
                if (clickedTakeOut && current.isEmpty() && wantedMatcher != null) {
                    step = Step.ACT;
                    resetWait();
                    return ActionStatus.progressed();
                }
            }
            if (!syncWaitElapsed()) return ActionStatus.running();
            cacheRecorder.run();
            return closeWith(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                    label + " 的 " + semanticId + " 槽未变成 " + wantedLabel + "（可能背包满或该槽不接受此物）"));
        }

        private boolean matchesGoal(SlotSnapshot snapshot, PlayerContext player) {
            if (wantedMatcher == null) return snapshot.isEmpty();
            return !snapshot.isEmpty() && wantedMatcher.test(snapshot, player);
        }

        private int findMatchingPlayerSlot(MenuContent.Reading reading, PlayerContext player) {
            for (int i = 0; i < reading.playerSnapshots().size(); i++) {
                SlotSnapshot s = reading.playerSnapshots().get(i);
                if (!s.isEmpty() && wantedMatcher.test(s, player)) {
                    return reading.playerSlotIds().get(i);
                }
            }
            return -1;
        }

        private static SlotSnapshot snapshotOf(MenuContent.Reading reading, int slotId) {
            for (int i = 0; i < reading.containerSlotIds().size(); i++) {
                if (reading.containerSlotIds().get(i) == slotId) {
                    return reading.containerSnapshots().get(i);
                }
            }
            return SlotSnapshot.empty();
        }

        @Override protected String doing() {
            return "调整 " + label + " 槽位为 " + wantedLabel;
        }
    }

    /**
     * 打开主界面 → 切入 ae2:priority 子界面 → 写优先级 → 返回主界面并关窗。
     * 注意 PriorityMenu 不含 36 格玩家背包，绝不走 MenuLayout，切页期间直接经 reads 读回确认。
     */
    static final class PrioritySubMenuAction extends Ae2MenuWork {

        private enum Step { SWITCH_SUB, WAIT_SUB, SET_VALUE, WAIT_BACK }

        private final Ae2Compat compat;
        private final Ae2MachineReads reads;
        private final int wantedPriority;
        private final IntConsumer cacheRecorder;
        private final String label;

        private Step step = Step.SWITCH_SUB;

        PrioritySubMenuAction(MenuOpening opening, Ae2Compat compat, Ae2MachineReads reads,
                int wantedPriority, IntConsumer cacheRecorder, String label) {
            super(opening, label + " 界面没打开");
            this.compat = Objects.requireNonNull(compat, "compat");
            this.reads = Objects.requireNonNull(reads, "reads");
            this.wantedPriority = wantedPriority;
            this.cacheRecorder = Objects.requireNonNull(cacheRecorder, "cacheRecorder");
            this.label = Objects.requireNonNull(label, "label");
        }

        // 声明跟随服务端换页至优先级子界面与返回主界面，避免关闭时误判界面丢失。
        @Override protected void menuOpened(OpenedMenu menu) {
            menu.followPageSwaps((from, to) -> true);
        }

        @Override protected ActionStatus work(TickContext tick) {
            return switch (step) {
                case SWITCH_SUB -> switchSub();
                case WAIT_SUB -> waitSub();
                case SET_VALUE -> setValue();
                case WAIT_BACK -> waitBack();
            };
        }

        private ActionStatus switchSub() {
            if (!compat.openSubMenu("ae2:priority")) {
                return closeWith(Problem.of(Problem.Kind.REFUSED_BY_GAME, label + " 无法打开优先级子界面"));
            }
            resetWait();
            step = Step.WAIT_SUB;
            return ActionStatus.progressed();
        }

        private ActionStatus waitSub() {
            Optional<Integer> current = reads.openPriorityMenu();
            if (current.isPresent()) {
                step = Step.SET_VALUE;
                return ActionStatus.progressed();
            }
            if (!syncWaitElapsed()) return ActionStatus.running();
            return closeWith(Problem.of(Problem.Kind.STUCK, label + " 切换到优先级子界面超时"));
        }

        private ActionStatus setValue() {
            Optional<Integer> current = reads.openPriorityMenu();
            if (current.isEmpty()) {
                return closeWith(Problem.of(Problem.Kind.TARGET_GONE, label + " 优先级子界面已关闭"));
            }
            if (current.get() != wantedPriority && !reads.sendSetPriority(wantedPriority)) {
                return closeWith(Problem.of(Problem.Kind.REFUSED_BY_GAME, label + " 发送优先级修改失败"));
            }
            cacheRecorder.accept(wantedPriority);
            compat.returnToParentMenu();
            resetWait();
            step = Step.WAIT_BACK;
            return ActionStatus.progressed();
        }

        private ActionStatus waitBack() {
            if (reads.openPriorityMenu().isEmpty()) {
                return closeWith(null);
            }
            if (!syncWaitElapsed()) return ActionStatus.running();
            return closeWith(null);
        }

        @Override protected String doing() {
            return "设置 " + label + " 优先级为 " + wantedPriority;
        }
    }
}
