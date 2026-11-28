// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.part;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.machine.spi.FullRun;
import org.maiwithu.maicraft.ability.machine.spi.RunRequest;
import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2MenuWork;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * ME IO 传输站的完整倒盘运行动作：
 * 打开 {@code ae2:ioport} 界面 → 把待倒盘存储元件或 {@code IBasicCellItem} 工具放入 6 格输入槽 →
 * 等待传输站完成转移并出现在 6 格输出槽 → Shift 收回角色背包 → 刷新缓存并关窗。
 */
final class CellTransferRun extends Ae2MenuWork implements FullRun {

    /** 默认倒盘等待上限（秒），未指定 maxSeconds 时使用。 */
    private static final int DEFAULT_MAX_SECONDS = 30;
    /** 每次最多连续送入的输入格数（ME IO 传输站输入区共 6 格）。F 游戏事实。 */
    private static final int MAX_INPUT_SLOTS = 6;

    private enum Step { SYNC, PICK_CELL, PUT_INPUT, WAIT_TRANSFER, COLLECT_OUTPUT }

    private final Ae2PartControl control;
    private final Ae2PartCache cache;
    private final BlockPos pos;
    private final RunRequest request;
    private final int targetCount;
    /** 倒盘整体的等待上限（刻），与单次点击的同步上限不同：倒盘本身要跑几十秒。 */
    private final int maxWaitTicks;
    private final List<String> notes = new ArrayList<>();

    private Step step = Step.SYNC;
    private int transferWaited;
    private boolean sent;
    private int loadedCount;
    private int collectedCount;
    private int pickSlot = -1;
    private int inputSlot = -1;
    private int collectSlot = -1;
    private String activeCellId;

    CellTransferRun(Ae2PartControl control, Ae2PartCache cache, PlayerServices services, BlockPos pos,
                    RunRequest request, Permissions permissions) {
        this(control, cache, pos, request, new ClientMenuOpening(pos, null, permissions, services.menuLayouts(),
                services.approach(), services.interactions(), services.toMainhand(), services.context()));
    }

    /** 离线测试用构造：允许注入菜单打开替身。 */
    CellTransferRun(Ae2PartControl control, Ae2PartCache cache, BlockPos pos, RunRequest request,
                    MenuOpening opening) {
        super(opening, "ME IO 传输站的界面没打开");
        this.control = Objects.requireNonNull(control, "control");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.pos = pos.immutable();
        this.request = Objects.requireNonNull(request, "request");
        this.targetCount = Math.max(1, Math.min(request.count() <= 0 ? 1 : request.count(), MAX_INPUT_SLOTS));
        int seconds = request.maxSeconds() > 0 ? request.maxSeconds() : DEFAULT_MAX_SECONDS;
        this.maxWaitTicks = seconds * 20;
    }

    @Override public int produced() {
        return collectedCount;
    }

    @Override public boolean complete() {
        return collectedCount >= targetCount;
    }

    @Override public List<String> notes() {
        return List.copyOf(notes);
    }

    @Override protected ActionStatus work(TickContext tick) {
        return switch (step) {
            case SYNC -> waitSync();
            case PICK_CELL -> pickCell();
            case PUT_INPUT -> putInput();
            case WAIT_TRANSFER -> waitTransfer();
            case COLLECT_OUTPUT -> collectOutput();
        };
    }

    private ActionStatus waitSync() {
        Optional<Ae2PartControl.OpenMenuSnapshot> snap = control.readOpenMenu();
        if (snap.isPresent() && menu().reading().isPresent()) {
            Ae2PartControl.OpenMenuSnapshot current = snap.get();
            // 装有红石卡且红石模式为 ignore 时，记录事实附注，提示 LLM 若有红石信号会暂停倒盘。
            if (current.hasRedstoneCard() && "ignore".equals(current.settings().get("redstone_mode"))) {
                notes.add(CellTransferOps.REDSTONE_IGNORE_NOTE);
            }
            step = Step.PICK_CELL;
            resetWait();
            return ActionStatus.progressed();
        }
        if (!syncWaitElapsed()) return ActionStatus.running();
        return stop(Problem.of(Problem.Kind.STUCK, "ME IO 传输站的界面一直没同步完成"));
    }

    // 在背包找一块待倒盘元件或 IBasicCellItem 工具，并配对传输站输入区里的空闲格。
    private ActionStatus pickCell() {
        if (menu().busy()) return ActionStatus.running();
        Optional<Ae2PartControl.OpenMenuSnapshot> opt = control.readOpenMenu();
        Optional<MenuContent.Reading> reading = menu().reading();
        if (opt.isEmpty() || reading.isEmpty()) {
            return stop(Problem.of(Problem.Kind.TARGET_GONE, "ME IO 传输站的界面被关掉了"));
        }
        if (loadedCount >= targetCount) {
            return enterWaitTransfer();
        }
        Optional<Ae2PartControl.MenuSlotEntry> freeInput = opt.get().inputSlots().stream()
                .filter(Ae2PartControl.MenuSlotEntry::isEmpty).findFirst();
        if (freeInput.isEmpty()) {
            if (loadedCount > 0) return enterWaitTransfer();
            return stop(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "ME IO 传输站的 6 个输入槽已满"));
        }
        if (!menu().cursorEmpty() && activeCellId != null && activeCellId.equals(menu().cursor().key())) {
            inputSlot = freeInput.get().menuIndex();
            step = Step.PUT_INPUT;
            sent = false;
            resetWait();
            return ActionStatus.progressed();
        }
        if (!sent) {
            int found = findCarryCellSlot(reading.get());
            if (found < 0) {
                if (loadedCount > 0) return enterWaitTransfer();
                String want = request.item() == null ? "可倒盘的存储元件或带元件工具" : request.item();
                return stop(Problem.of(Problem.Kind.NEED_ITEM, "背包里没有找到 " + want));
            }
            pickSlot = found;
            activeCellId = slotItemId(reading.get(), found);
            if (!menu().click(pickSlot, 0)) return ActionStatus.running();
            menu().noteCursorTakenFrom(pickSlot);
            sent = true;
            resetWait();
            return ActionStatus.progressed();
        }
        if (!syncWaitElapsed()) return ActionStatus.running();
        return stop(Problem.of(Problem.Kind.STUCK, "从背包拿起待倒盘元件超时"));
    }

    private ActionStatus putInput() {
        if (menu().busy()) return ActionStatus.running();
        Optional<Ae2PartControl.OpenMenuSnapshot> opt = control.readOpenMenu();
        if (opt.isEmpty()) return stop(Problem.of(Problem.Kind.TARGET_GONE, "ME IO 传输站的界面被关掉了"));
        if (menu().cursorEmpty()) {
            loadedCount++;
            activeCellId = null;
            sent = false;
            step = loadedCount >= targetCount ? Step.WAIT_TRANSFER : Step.PICK_CELL;
            transferWaited = 0;
            resetWait();
            return ActionStatus.progressed();
        }
        if (!sent) {
            if (!menu().click(inputSlot, 0)) return ActionStatus.running();
            sent = true;
            resetWait();
            return ActionStatus.progressed();
        }
        if (!syncWaitElapsed()) return ActionStatus.running();
        return stop(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                "ME IO 传输站输入槽拒收 " + activeCellId + "（不是支持的存储元件）"));
    }

    // 等待传输站把元件从输入槽倒盘并移动到输出槽；只要输出槽出现完成元件就立刻收回背包。
    private ActionStatus waitTransfer() {
        Optional<Ae2PartControl.OpenMenuSnapshot> opt = control.readOpenMenu();
        if (opt.isEmpty()) return stop(Problem.of(Problem.Kind.TARGET_GONE, "ME IO 传输站的界面被关掉了"));
        Ae2PartControl.OpenMenuSnapshot snap = opt.get();
        Optional<Ae2PartControl.MenuSlotEntry> finished = snap.outputSlots().stream()
                .filter(slot -> !slot.isEmpty() && matchesTarget(slot.itemId())).findFirst();
        if (finished.isPresent()) {
            collectSlot = finished.get().menuIndex();
            step = Step.COLLECT_OUTPUT;
            sent = false;
            resetWait();
            return ActionStatus.progressed();
        }
        if (++transferWaited > maxWaitTicks) {
            notes.add("已放入 " + loadedCount + " 块元件，收回 " + collectedCount + " 块；其余仍停在输入槽"
                    + "（请检查 ME 网络供电、网络剩余空间、方向模式与红石信号）");
            return stop(Problem.of(Problem.Kind.STUCK,
                    "等待 ME IO 传输站倒盘超时（已完成 " + collectedCount + "/" + loadedCount + "）"));
        }
        return ActionStatus.running();
    }

    private ActionStatus collectOutput() {
        if (menu().busy()) return ActionStatus.running();
        Optional<Ae2PartControl.OpenMenuSnapshot> opt = control.readOpenMenu();
        if (opt.isEmpty()) return stop(Problem.of(Problem.Kind.TARGET_GONE, "ME IO 传输站的界面被关掉了"));
        boolean stillPresent = opt.get().outputSlots().stream()
                .anyMatch(slot -> slot.menuIndex() == collectSlot && !slot.isEmpty());
        if (!stillPresent) {
            collectedCount++;
            sent = false;
            resetWait();
            if (collectedCount >= loadedCount) {
                notes.add("已完成 " + collectedCount + " 块元件倒盘并收回角色背包");
                return stop(null);
            }
            step = Step.WAIT_TRANSFER;
            return ActionStatus.progressed();
        }
        if (!sent) {
            if (!menu().quickMove(collectSlot)) return ActionStatus.running();
            sent = true;
            resetWait();
            return ActionStatus.progressed();
        }
        if (!syncWaitElapsed()) return ActionStatus.running();
        return stop(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "无法把倒盘完成的元件从输出槽收回背包（背包可能已满）"));
    }

    // 进等待倒盘段：传输倒计时从头计，单次点击的同步计时也重新计。
    private ActionStatus enterWaitTransfer() {
        step = Step.WAIT_TRANSFER;
        transferWaited = 0;
        resetWait();
        return ActionStatus.progressed();
    }

    private int findCarryCellSlot(MenuContent.Reading reading) {
        List<SlotSnapshot> snaps = reading.playerSnapshots();
        List<Integer> ids = reading.playerSlotIds();
        for (int i = 0; i < snaps.size(); i++) {
            SlotSnapshot snap = snaps.get(i);
            if (snap.isEmpty()) continue;
            if (request.item() != null && !request.item().isBlank()) {
                if (request.item().equals(snap.key())) return ids.get(i);
            } else if (control.isTransferableCell(snap.stack())) {
                return ids.get(i);
            }
        }
        return -1;
    }

    private static String slotItemId(MenuContent.Reading reading, int menuSlot) {
        for (int i = 0; i < reading.playerSlotIds().size(); i++) {
            if (reading.playerSlotIds().get(i) == menuSlot) return reading.playerSnapshots().get(i).key();
        }
        return "";
    }

    private boolean matchesTarget(String itemId) {
        return request.item() == null || request.item().isBlank() || request.item().equals(itemId);
    }

    // 关窗前把传输站界面最新状态刷进缓存，配置任务下一刻读回立即命中。
    private ActionStatus stop(Problem reason) {
        control.readOpenMenu().ifPresent(snap -> Ae2PartSnapshots.flushToCache(cache, pos, null, snap, null, null));
        return closeWith(reason);
    }

    // 光标上拿着待倒盘元件时停不得：往输入槽放的那几下被关掉会落在流程外。
    @Override public Interruptibility interruptibility() {
        return step == Step.PICK_CELL || step == Step.PUT_INPUT
                ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
    }

    @Override protected String doing() {
        return "使用 ME IO 传输站倒盘（已收回 " + collectedCount + "/" + targetCount + "）";
    }
}
