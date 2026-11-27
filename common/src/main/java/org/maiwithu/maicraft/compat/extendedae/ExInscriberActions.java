// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.extendedae;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.behavior.inventory.containers.FeedReport;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.OutputLook;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.game.menu.MenuConfirmation;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.ItemMatch;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 扩展压印器的分页界面操作动作集：统一负责打开界面后切到指定页（依次发 {@code CUpdatePage(page)} 与
 * {@code CEAEGenericPacket("show")} 并核对目标页 4 槽启用），再执行该页冲突腾空、投料或收产物并仅收回本会话角色自放模板。
 */
final class ExInscriberActions {

    private static final int SYNC_WAIT_TICKS = ExtendedAeMenuActionSupport.SYNC_WAIT_TICKS;
    /** 扩展压印器 4 页的顶、底、中间、输出槽位语义矩阵。F 游戏事实。 */
    private static final String[][] PAGE_SEMANTICS = {
            {"INSCRIBER_PLATE_TOP", "INSCRIBER_PLATE_BOTTOM", "MACHINE_INPUT", "MACHINE_OUTPUT"},
            {"EX_1", "EX_4", "EX_7", "EX_10"},
            {"EX_2", "EX_5", "EX_8", "EX_11"},
            {"EX_3", "EX_6", "EX_9", "EX_12"}
    };

    private ExInscriberActions() {}

    /** 取指定页（0..3）四个工作格的语义名称列表（顶、底、中间、输出）。 */
    static String[] semanticsForPage(int page) {
        int clamped = Math.max(0, Math.min(3, page));
        return PAGE_SEMANTICS[clamped];
    }

    /** 切页校验小工具：打开界面后确认当前处于目标页且目标页 4 槽启用；否则经 mod action 发包切页。 */
    static final class PageSwitch {

        private final int targetPage;
        private final ExtendedAeCompat compat;
        private PendingMenuAction pending;
        private int waited;

        PageSwitch(int targetPage, ExtendedAeCompat compat) {
            this.targetPage = Math.max(0, Math.min(3, targetPage));
            this.compat = Objects.requireNonNull(compat, "compat");
        }

        /** 若已在目标页且槽位启用返回 true；需继续等待返回 false；超时或被拒返回失败 Problem。 */
        Optional<Problem> ensureOnPage(OpenedMenu menu) {
            if (isOnTargetPage()) {
                pending = null;
                return Optional.empty();
            }
            if (pending != null) {
                if (pending.status() == PendingMenuAction.Status.PENDING) {
                    if (++waited <= SYNC_WAIT_TICKS) return Optional.empty();
                    return Optional.of(Problem.of(Problem.Kind.STUCK, "扩展压印器切到第 " + targetPage + " 页超时"));
                }
                pending = null;
                if (isOnTargetPage()) return Optional.empty();
                return Optional.of(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                        "扩展压印器切页至第 " + targetPage + " 页未生效"));
            }
            // 依次发送 CUpdatePage(targetPage) 与 CEAEGenericPacket("show") 触发服务端切换并启用目标页槽位。
            MenuConfirmation confirmed = (ctx, pending) -> isOnTargetPage()
                    ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING;
            Optional<PendingMenuAction> sent = menu.submitModAction(
                    "扩展压印器切页至第 " + targetPage + " 页",
                    () -> {
                        compat.sendUpdatePage(targetPage);
                        compat.sendGenericAction("show");
                    },
                    confirmed, SYNC_WAIT_TICKS);
            if (sent.isEmpty()) {
                if (++waited <= SYNC_WAIT_TICKS) return Optional.empty();
                return Optional.of(Problem.of(Problem.Kind.STUCK, "无法向扩展压印器发送切页包"));
            }
            pending = sent.get();
            waited = 0;
            return Optional.empty();
        }

        boolean isOnTargetPage() {
            return compat.openExInscriberMenu()
                    .map(v -> v.page() == targetPage && v.currentPageSlotsEnabled())
                    .orElse(false);
        }
    }

    /** 切到目标页并把冲突工作格（0..3）里的占槽物快速移回背包。 */
    static final class ClearAction implements Action {

        private enum Stage { OPEN, SWITCH_PAGE, CLEAR, WAIT_MOVE, CLOSE, DONE }

        private final int targetPage;
        private final List<Integer> conflictIndices;
        private final Set<String> ownPlates;
        private final MenuOpening opening;
        private final ExtendedAeCompat compat;
        private final PageSwitch pageSwitch;

        private Stage stage = Stage.OPEN;
        private OpenedMenu menu;
        private Action closing;
        private int cursor;
        private int clickedSlot = -1;
        private String clickedItem;
        private int waited;
        private Problem problem;

        ClearAction(int targetPage, List<Integer> conflictIndices, Set<String> ownPlates,
                MenuOpening opening, ExtendedAeCompat compat) {
            this.targetPage = targetPage;
            this.conflictIndices = List.copyOf(Objects.requireNonNull(conflictIndices, "conflictIndices"));
            this.ownPlates = ownPlates;
            this.opening = Objects.requireNonNull(opening, "opening");
            this.compat = Objects.requireNonNull(compat, "compat");
            this.pageSwitch = new PageSwitch(targetPage, compat);
        }

        @Override public ActionStatus tick(TickContext tick) {
            return switch (stage) {
                case OPEN -> open(tick);
                case SWITCH_PAGE -> switchPage();
                case CLEAR -> clearNext();
                case WAIT_MOVE -> waitMove();
                case CLOSE -> closeMenu(tick);
                case DONE -> problem == null ? ActionStatus.done() : ActionStatus.failed(problem);
            };
        }

        private ActionStatus open(TickContext tick) {
            ActionStatus status = opening.tick(tick);
            if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
            if (!(status instanceof ActionStatus.Done)) return status;
            menu = opening.opened().orElse(null);
            if (menu == null) return finish(Problem.of(Problem.Kind.TARGET_GONE, "扩展压印器界面没打开"));
            stage = Stage.SWITCH_PAGE;
            return ActionStatus.progressed();
        }

        private ActionStatus switchPage() {
            Optional<Problem> err = pageSwitch.ensureOnPage(menu);
            if (err.isPresent()) return beginClose(err.get());
            if (!pageSwitch.isOnTargetPage()) return ActionStatus.running();
            stage = Stage.CLEAR;
            waited = 0;
            return ActionStatus.progressed();
        }

        private ActionStatus clearNext() {
            if (cursor >= conflictIndices.size()) return beginClose(null);
            Optional<MenuContent.Reading> reading = menu.reading();
            if (reading.isEmpty()) {
                if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
                return beginClose(Problem.of(Problem.Kind.STUCK, "扩展压印器界面读数同步超时"));
            }
            int workIdx = conflictIndices.get(cursor);
            String semantic = semanticsForPage(targetPage)[workIdx];
            List<Integer> slotIds = compat.enabledMenuSlotsOf(semantic);
            if (slotIds.isEmpty()) {
                cursor++;
                return ActionStatus.progressed();
            }
            clickedSlot = slotIds.getFirst();
            SlotSnapshot snap = containerSnapshot(reading.get(), clickedSlot);
            if (snap.isEmpty()) {
                cursor++;
                return ActionStatus.progressed();
            }
            clickedItem = snap.key();
            if (!menu.quickMove(clickedSlot)) {
                if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
                return beginClose(Problem.of(Problem.Kind.STUCK, "无法从扩展压印器取出冲突物 " + clickedItem));
            }
            waited = 0;
            stage = Stage.WAIT_MOVE;
            return ActionStatus.progressed();
        }

        private ActionStatus waitMove() {
            if (menu.busy()) return ActionStatus.running();
            Optional<MenuContent.Reading> reading = menu.reading();
            if (reading.isPresent() && containerSnapshot(reading.get(), clickedSlot).isEmpty()) {
                if (ownPlates != null && clickedItem != null) ownPlates.remove(clickedItem);
                cursor++;
                waited = 0;
                stage = Stage.CLEAR;
                return ActionStatus.progressed();
            }
            if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
            return beginClose(Problem.of(Problem.Kind.INVENTORY_FULL,
                    "无法腾空扩展压印器第 " + targetPage + " 页的 " + clickedItem + "，请检查背包空位"));
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

        @Override public String describe() { return "腾空扩展压印器第 " + targetPage + " 页的冲突槽位"; }
    }

    /** 切到目标页并把指定原料投入当前页对应工作格，记录本会话角色自放的不消耗模板。 */
    static final class FeedAction implements Action, FeedReport {

        private enum Stage { OPEN, SWITCH_PAGE, FEED, WAIT_FEED, CLOSE, DONE }

        private final int targetPage;
        private final String itemSpec;
        private final int wantedCount;
        private final boolean collectBack;
        private final Set<String> ownPlates;
        private final MenuOpening opening;
        private final ExtendedAeCompat compat;
        private final PageSwitch pageSwitch;
        private final List<String> notes = new ArrayList<>();

        private Stage stage = Stage.OPEN;
        private OpenedMenu menu;
        private Action closing;
        private int delivered;
        private int playerCountBefore;
        private int waited;
        private Problem problem;

        FeedAction(int targetPage, String itemSpec, int wantedCount, boolean collectBack, Set<String> ownPlates,
                MenuOpening opening, ExtendedAeCompat compat) {
            this.targetPage = targetPage;
            this.itemSpec = Objects.requireNonNull(itemSpec, "itemSpec");
            this.wantedCount = Math.max(1, wantedCount);
            this.collectBack = collectBack;
            this.ownPlates = ownPlates;
            this.opening = Objects.requireNonNull(opening, "opening");
            this.compat = Objects.requireNonNull(compat, "compat");
            this.pageSwitch = new PageSwitch(targetPage, compat);
        }

        @Override public int delivered() { return delivered; }

        @Override public List<String> notes() { return List.copyOf(notes); }

        @Override public ActionStatus tick(TickContext tick) {
            return switch (stage) {
                case OPEN -> open(tick);
                case SWITCH_PAGE -> switchPage();
                case FEED -> feedNext();
                case WAIT_FEED -> waitFeed();
                case CLOSE -> closeMenu(tick);
                case DONE -> problem == null ? ActionStatus.done() : ActionStatus.failed(problem);
            };
        }

        private ActionStatus open(TickContext tick) {
            ActionStatus status = opening.tick(tick);
            if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
            if (!(status instanceof ActionStatus.Done)) return status;
            menu = opening.opened().orElse(null);
            if (menu == null) return finish(Problem.of(Problem.Kind.TARGET_GONE, "扩展压印器界面没打开"));
            stage = Stage.SWITCH_PAGE;
            return ActionStatus.progressed();
        }

        private ActionStatus switchPage() {
            Optional<Problem> err = pageSwitch.ensureOnPage(menu);
            if (err.isPresent()) return beginClose(err.get());
            if (!pageSwitch.isOnTargetPage()) return ActionStatus.running();
            stage = Stage.FEED;
            waited = 0;
            return ActionStatus.progressed();
        }

        private ActionStatus feedNext() {
            if (delivered >= wantedCount) return beginClose(null);
            if (menu.busy()) return ActionStatus.running();
            Optional<MenuContent.Reading> readingOpt = menu.reading();
            if (readingOpt.isEmpty()) {
                if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
                return beginClose(Problem.of(Problem.Kind.STUCK, "扩展压印器界面读数同步超时"));
            }
            MenuContent.Reading reading = readingOpt.get();
            int playerSlot = findMatchingPlayerSlot(reading, itemSpec);
            if (playerSlot < 0) {
                if (delivered > 0) return beginClose(null);
                return beginClose(Problem.of(Problem.Kind.NEED_ITEM, "背包里没有 " + itemSpec + "，无法投进扩展压印器"));
            }
            playerCountBefore = countInPlayer(reading, itemSpec);
            if (!menu.quickMove(playerSlot)) {
                if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
                return beginClose(Problem.of(Problem.Kind.STUCK, "无法将 " + itemSpec + " 移入扩展压印器"));
            }
            waited = 0;
            stage = Stage.WAIT_FEED;
            return ActionStatus.progressed();
        }

        private ActionStatus waitFeed() {
            if (menu.busy()) return ActionStatus.running();
            Optional<MenuContent.Reading> readingOpt = menu.reading();
            if (readingOpt.isPresent()) {
                int nowCount = countInPlayer(readingOpt.get(), itemSpec);
                if (nowCount < playerCountBefore) {
                    delivered += (playerCountBefore - nowCount);
                    if (collectBack || ExInscriberPlanner.isPressItemId(itemSpec)) {
                        if (ownPlates != null) ownPlates.add(itemSpec);
                    }
                    waited = 0;
                    stage = Stage.FEED;
                    return ActionStatus.progressed();
                }
            }
            if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
            if (delivered > 0) {
                notes.add("第 " + targetPage + " 页已放入 " + delivered + "/" + wantedCount + " 件 " + itemSpec);
                return beginClose(null);
            }
            return beginClose(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    "扩展压印器第 " + targetPage + " 页未接收 " + itemSpec + "（可能槽满或模板不匹配）"));
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

        @Override public String describe() {
            return "向扩展压印器第 " + targetPage + " 页投入 " + itemSpec + " ×" + wantedCount;
        }
    }

    /** 切到目标页取出输出格产物，并顺手只收回本会话角色亲自放入该页的不消耗模板。 */
    static final class TakeAction implements OutputLook {

        private enum Stage { OPEN, SWITCH_PAGE, TAKE_OUTPUT, WAIT_OUTPUT, TAKE_PLATE, WAIT_PLATE, CLOSE, DONE }

        private final int targetPage;
        private final String outputItemId;
        private final Set<String> ownPlates;
        private final MenuOpening opening;
        private final ExtendedAeCompat compat;
        private final Supplier<PlayerContext> contexts;
        private final PageSwitch pageSwitch;
        private final List<String> notes = new ArrayList<>();

        private Stage stage = Stage.OPEN;
        private OpenedMenu menu;
        private Action closing;
        private int seen;
        private int taken;
        private int outputSlotId = -1;
        private int outputCountBefore;
        private int plateSlotId = -1;
        private String plateTaking;
        private int waited;
        private Problem problem;

        TakeAction(int targetPage, String outputItemId, Set<String> ownPlates, MenuOpening opening,
                ExtendedAeCompat compat, Supplier<PlayerContext> contexts) {
            this.targetPage = targetPage;
            this.outputItemId = Objects.requireNonNull(outputItemId, "outputItemId");
            this.ownPlates = ownPlates;
            this.opening = Objects.requireNonNull(opening, "opening");
            this.compat = Objects.requireNonNull(compat, "compat");
            this.contexts = Objects.requireNonNull(contexts, "contexts");
            this.pageSwitch = new PageSwitch(targetPage, compat);
        }

        @Override public int seen() { return seen; }

        @Override public int taken() { return taken; }

        @Override public List<String> notes() { return List.copyOf(notes); }

        @Override public ActionStatus tick(TickContext tick) {
            return switch (stage) {
                case OPEN -> open(tick);
                case SWITCH_PAGE -> switchPage();
                case TAKE_OUTPUT -> takeOutput();
                case WAIT_OUTPUT -> waitOutput();
                case TAKE_PLATE -> takeOwnPlate();
                case WAIT_PLATE -> waitPlate();
                case CLOSE -> closeMenu(tick);
                case DONE -> problem == null ? ActionStatus.done() : ActionStatus.failed(problem);
            };
        }

        private ActionStatus open(TickContext tick) {
            ActionStatus status = opening.tick(tick);
            if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
            if (!(status instanceof ActionStatus.Done)) return status;
            menu = opening.opened().orElse(null);
            if (menu == null) return finish(Problem.of(Problem.Kind.TARGET_GONE, "扩展压印器界面没打开"));
            stage = Stage.SWITCH_PAGE;
            return ActionStatus.progressed();
        }

        private ActionStatus switchPage() {
            Optional<Problem> err = pageSwitch.ensureOnPage(menu);
            if (err.isPresent()) return beginClose(err.get());
            if (!pageSwitch.isOnTargetPage()) return ActionStatus.running();
            stage = Stage.TAKE_OUTPUT;
            waited = 0;
            return ActionStatus.progressed();
        }

        private ActionStatus takeOutput() {
            Optional<MenuContent.Reading> readingOpt = menu.reading();
            if (readingOpt.isEmpty()) {
                if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
                return beginClose(Problem.of(Problem.Kind.STUCK, "收产物时扩展压印器读数同步超时"));
            }
            String outSemantic = semanticsForPage(targetPage)[3];
            List<Integer> outSlots = compat.enabledMenuSlotsOf(outSemantic);
            if (outSlots.isEmpty()) {
                stage = Stage.TAKE_PLATE;
                return ActionStatus.progressed();
            }
            outputSlotId = outSlots.getFirst();
            SlotSnapshot outSnap = containerSnapshot(readingOpt.get(), outputSlotId);
            if (outSnap.isEmpty() || !outputItemId.equals(outSnap.key())) {
                stage = Stage.TAKE_PLATE;
                return ActionStatus.progressed();
            }
            seen = Math.max(seen, outSnap.count());
            outputCountBefore = outSnap.count();
            if (!menu.quickMove(outputSlotId)) {
                if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
                return beginClose(Problem.of(Problem.Kind.STUCK, "无法从扩展压印器取出产物 " + outputItemId));
            }
            waited = 0;
            stage = Stage.WAIT_OUTPUT;
            return ActionStatus.progressed();
        }

        private ActionStatus waitOutput() {
            if (menu.busy()) return ActionStatus.running();
            Optional<MenuContent.Reading> readingOpt = menu.reading();
            if (readingOpt.isPresent()) {
                SlotSnapshot now = containerSnapshot(readingOpt.get(), outputSlotId);
                int nowCount = now.isEmpty() ? 0 : now.count();
                if (nowCount < outputCountBefore) {
                    taken += (outputCountBefore - nowCount);
                    stage = Stage.TAKE_PLATE;
                    waited = 0;
                    return ActionStatus.progressed();
                }
            }
            if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
            notes.add("产物 " + outputItemId + " 未能全部移入背包，可能背包已满");
            stage = Stage.TAKE_PLATE;
            waited = 0;
            return ActionStatus.progressed();
        }

        // 只检查顶（0）与底（1）模板槽里属于本会话角色自放的模板，机内原有的模板一律不动。
        private ActionStatus takeOwnPlate() {
            if (ownPlates == null || ownPlates.isEmpty()) return beginClose(null);
            Optional<MenuContent.Reading> readingOpt = menu.reading();
            if (readingOpt.isEmpty()) return beginClose(null);
            String[] semantics = semanticsForPage(targetPage);
            for (int idx = 0; idx < 2; idx++) {
                List<Integer> slots = compat.enabledMenuSlotsOf(semantics[idx]);
                if (slots.isEmpty()) continue;
                int slotId = slots.getFirst();
                SlotSnapshot snap = containerSnapshot(readingOpt.get(), slotId);
                if (snap.isEmpty()) continue;
                String matchedSpec = matchOwnPlate(snap, ownPlates);
                if (matchedSpec == null) continue;
                plateSlotId = slotId;
                plateTaking = matchedSpec;
                if (!menu.quickMove(slotId)) {
                    if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
                    return beginClose(null);
                }
                waited = 0;
                stage = Stage.WAIT_PLATE;
                return ActionStatus.progressed();
            }
            return beginClose(null);
        }

        private ActionStatus waitPlate() {
            if (menu.busy()) return ActionStatus.running();
            Optional<MenuContent.Reading> readingOpt = menu.reading();
            if (readingOpt.isPresent() && containerSnapshot(readingOpt.get(), plateSlotId).isEmpty()) {
                ownPlates.remove(plateTaking);
                notes.add("顺手收回了本会话放进第 " + targetPage + " 页的模板：" + plateTaking);
                stage = Stage.TAKE_PLATE;
                waited = 0;
                return ActionStatus.progressed();
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

        @Override public String describe() {
            return "从扩展压印器第 " + targetPage + " 页取出 " + outputItemId;
        }
    }

    private static SlotSnapshot containerSnapshot(MenuContent.Reading reading, int slotId) {
        for (int i = 0; i < reading.containerSlotIds().size(); i++) {
            if (reading.containerSlotIds().get(i) == slotId) return reading.containerSnapshots().get(i);
        }
        return SlotSnapshot.empty();
    }

    private static int findMatchingPlayerSlot(MenuContent.Reading reading, String itemSpec) {
        for (int i = 0; i < reading.playerSnapshots().size(); i++) {
            SlotSnapshot snap = reading.playerSnapshots().get(i);
            if (!snap.isEmpty() && matchesSpec(snap, itemSpec)) return reading.playerSlotIds().get(i);
        }
        return -1;
    }

    private static int countInPlayer(MenuContent.Reading reading, String itemSpec) {
        int total = 0;
        for (SlotSnapshot snap : reading.playerSnapshots()) {
            if (!snap.isEmpty() && matchesSpec(snap, itemSpec)) total += snap.count();
        }
        return total;
    }

    private static String matchOwnPlate(SlotSnapshot snap, Set<String> ownPlates) {
        for (String spec : List.copyOf(ownPlates)) {
            if (matchesSpec(snap, spec)) return spec;
        }
        return null;
    }

    private static boolean matchesSpec(SlotSnapshot snap, String spec) {
        if (spec.equals(snap.key())) return true;
        ItemStack stack = snap.stack();
        if (stack != null && !stack.isEmpty()) {
            String regId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (spec.equals(regId) || ItemMatch.matches(stack, spec)) return true;
        }
        return false;
    }
}
