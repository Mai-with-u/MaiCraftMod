// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.containers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;

import org.maiwithu.maicraft.behavior.menu.MenuClick;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MoveConfirmation;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.menu.TransferPlan;
import org.maiwithu.maicraft.behavior.menu.TransferRun;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 往一只开着的容器里搬：按存什么的顺序一堆堆搬，每一笔交给搬运执行逐刻点、等同步、
 * 按「存进容器」的核对式出结论，再按两侧同种东西的总数前后对照记账。
 *
 * <p>整堆用快速移动；只存一部分时按搬运计划拆成半堆，明确放进一格（快速移动会把整堆多搬走）。
 * 先看容器里放不放得下再动手，不把东西拿在光标上再找地方放。背包少了几件、容器多了几件，
 * 两边对上才记账；点了没动是这只放不下了，换下一样或下一只；对不上的记进未确认，不凑数。
 * 被打断时请游戏关上界面（光标上的东西由原版还回背包），恢复后由存放动作重新点开接着存；
 * 关界面前没来得及结算的那一笔交给重新点开后的这一趟，按同一只容器两侧的总数补结算。
 * 只在 {@link StoresInContainer} 里用：走过去、点开、关上、重开都归它。
 */
final class ContainerTransfer implements Action {

    /** 补结算的那一笔等两侧内容同步过来的宽限（刻）；过了还定不下来就照实下结论。 */
    private static final int CARRY_SETTLE_TICKS = 5;

    /** 正在搬的一笔：搬哪种东西、动手前两侧各有几件；记账与对不上的说明都要用。 */
    private record Move(String itemId, int playerBefore, int containerBefore) {}

    /** 界面被关掉时还没结算的一笔：重新点开同一只容器后按两侧总数补结算。 */
    record Unsettled(String itemId, int playerBefore, int containerBefore) {}

    private final OpenedMenu menu;
    private final List<String> order;
    private final StoresInContainer.Ledger ledger;
    /** 对着这份界面逐刻点、等同步、出结论的搬运执行；补结算的一笔不经过它。 */
    private final TransferRun run;
    /** 这只容器里放不下的东西：不再往这只里塞。 */
    private final Set<String> full = new HashSet<>();
    /** 手上正在搬的一笔；没有为 null。 */
    private Move current;
    /** 上一趟留下、还没补结算的一笔；这一趟不是因为界面被关掉而停的为 null。 */
    private Unsettled carryOver;
    private int carryQuietTicks;
    private boolean finished;
    /** 界面被关掉了（被打断、被别人关）：这一趟到此为止，没结算的那一笔交给下一趟。 */
    private boolean menuLost;
    private Map<String, Integer> contentsAfter;

    /**
     * @param menu      点开并同步完的那一份界面
     * @param order     要存的东西，按存什么的顺序
     * @param ledger    搬运的账
     * @param carryOver 上一趟界面被关掉时没结算的那一笔；没有为 null
     */
    ContainerTransfer(OpenedMenu menu, List<String> order, StoresInContainer.Ledger ledger, Unsettled carryOver) {
        this.menu = menu;
        this.order = List.copyOf(order);
        this.ledger = ledger;
        this.run = new TransferRun(menu);
        this.carryOver = carryOver;
    }

    /** 界面被关掉时还没结算的那一笔；这一趟不是因为界面被关掉而停的为空。 */
    Optional<Unsettled> unsettled() {
        if (!menuLost) return Optional.empty();
        if (current != null) {
            return Optional.of(new Unsettled(current.itemId(), current.playerBefore(), current.containerBefore()));
        }
        return Optional.ofNullable(carryOver);
    }

    /** 搬完时容器里有什么（物品 ID 到件数）；还没搬完为空。 */
    Optional<Map<String, Integer>> contentsAfter() {
        return Optional.ofNullable(contentsAfter);
    }

    /** 这只容器里放不下的东西。 */
    Set<String> full() {
        return Set.copyOf(full);
    }

    @Override public ActionStatus tick(TickContext context) {
        Optional<MenuContent.Reading> reading = menu.reading();
        if (reading.isEmpty()) {
            menuLost = true;
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "容器界面被关上了", null));
        }
        if (menu.busy()) {
            return ActionStatus.running();
        }
        if (carryOver != null) return settleCarried(reading.get());
        if (!run.idle()) return run.tickAction(this::onSettled);
        return startNext(reading.get());
    }

    // 挑下一样还要存、这只还放得下的东西，冻结两侧总数后提交给搬运执行，本刻就点第一下；
    // 都没有了就是这只存完了。
    private ActionStatus startNext(MenuContent.Reading reading) {
        for (String itemId : order) {
            int left = ledger.left(itemId);
            if (left <= 0 || full.contains(itemId)) continue;
            int sourceIndex = sourceFor(reading.playerSnapshots(), itemId, left);
            if (sourceIndex < 0) continue;
            SlotSnapshot source = reading.playerSnapshots().get(sourceIndex);
            Optional<List<MenuClick>> clicks = clicksFor(reading, reading.playerSlotIds().get(sourceIndex), source, left);
            if (clicks.isEmpty()) {
                full.add(itemId);
                continue;
            }
            current = new Move(itemId, total(reading.playerSnapshots(), itemId),
                    total(reading.containerSnapshots(), itemId));
            run.begin(new TransferRun.Move(clicks.get(),
                    new TransferRun.Check.IntoContainer(
                            snapshot -> !snapshot.isEmpty() && idOf(snapshot).equals(itemId)),
                    "往容器里搬 " + itemId));
            return run.tickAction(this::onSettled);
        }
        finished = true;
        contentsAfter = merged(reading.containerSnapshots());
        return ActionStatus.done();
    }

    // 上一趟留下的一笔：点过的几下不再点，按两侧总数补结算；宽限内定不下来就再等，
    // 过了宽限照实下结论——两边都没动说明那几下没点出去，不算放不下。
    private ActionStatus settleCarried(MenuContent.Reading reading) {
        Unsettled carried = carryOver;
        int playerNow = total(reading.playerSnapshots(), carried.itemId());
        int containerNow = total(reading.containerSnapshots(), carried.itemId());
        MoveConfirmation.Verdict verdict = MoveConfirmation.intoContainer(
                carried.playerBefore(), playerNow, carried.containerBefore(), containerNow, menu.cursorEmpty());
        if (verdict == MoveConfirmation.Verdict.WAITING && ++carryQuietTicks <= CARRY_SETTLE_TICKS) {
            return ActionStatus.running();
        }
        account(carried.itemId(), carried.playerBefore() - playerNow, containerNow - carried.containerBefore(),
                menu.cursorEmpty(), true);
        carryOver = null;
        return ActionStatus.progressed();
    }

    // 一笔出了结论：按两侧总数前后对照记账；界面被关上的，这一趟到此为止，那笔交给下一趟。
    private ActionStatus onSettled(TransferRun.MoveResult result) {
        Move move = current;
        if (result instanceof TransferRun.MoveResult.MenuGone) {
            menuLost = true;
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "容器界面被关上了", null));
        }
        Optional<MenuContent.Reading> reading = menu.reading();
        if (reading.isEmpty()) {
            menuLost = true;
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "容器界面被关上了", null));
        }
        int playerNow = total(reading.get().playerSnapshots(), move.itemId());
        int containerNow = total(reading.get().containerSnapshots(), move.itemId());
        if (result instanceof TransferRun.MoveResult.NotMoved) {
            // 点出去了纹丝不动：多半这只放不下这一样，不再往这只里塞。
            full.add(move.itemId());
        } else if (result instanceof TransferRun.MoveResult.Confirmed(int landed)) {
            account(move.itemId(), move.playerBefore() - playerNow, landed, menu.cursorEmpty(), false);
        } else if (result instanceof TransferRun.MoveResult.Diverged) {
            account(move.itemId(), move.playerBefore() - playerNow, containerNow - move.containerBefore(),
                    menu.cursorEmpty(), false);
        }
        current = null;
        return ActionStatus.progressed();
    }

    // 一笔的账：背包少的和容器多的对上才记账；点了没动是放不下；对不上照实记进没能确认。
    // carried 是上一趟留下补结算的一笔：两边都没动说明那几下没点出去，不算放不下。
    private void account(String itemId, int out, int in, boolean cursorEmpty, boolean carried) {
        if (out == 0 && in == 0) {
            if (!carried) full.add(itemId);
        } else if (out == in && out > 0 && cursorEmpty) {
            ledger.stored(itemId, out);
        } else if (out > in && in >= 0 && cursorEmpty) {
            // 背包少的比容器多的多、光标空着：东西进了这只容器，又被它下面的溜槽、漏斗抽走了一部分（补料箱常这样）。
            // 容器里多出来的照实记存进；其余离开了背包，不再当"还没存"反复去存。
            if (in > 0) ledger.stored(itemId, in);
            ledger.passedThrough(itemId, out - in, "往容器里搬 " + itemId + "：背包少了 " + out + " 件，容器只多了 " + in
                    + " 件，差的 " + (out - in) + " 件多半一放进去就被下面的溜槽或漏斗抽走了");
        } else {
            ledger.unconfirmed("往容器里搬 " + itemId + "：背包少了 " + out + " 件，容器多了 " + in
                    + " 件" + (cursorEmpty ? "" : "，光标上还拿着东西") + "，对不上账");
        }
    }

    // 这一堆怎么点：要的不少于整堆就快速移动；只要一部分就拆半堆放进一格；容器里没地方放给空。
    private Optional<List<MenuClick>> clicksFor(MenuContent.Reading reading, int sourceSlot, SlotSnapshot source, int left) {
        List<MenuClick> clicks = new ArrayList<>();
        int maxStack = source.stack().getMaxStackSize();
        if (left >= source.count()) {
            if (!hasRoom(reading, source, 1)) return Optional.empty();
            clicks.add(new MenuClick(sourceSlot, true, 0, false));
            return Optional.of(clicks);
        }
        int targetIndex = roomyTarget(reading, source, left);
        if (targetIndex < 0) return Optional.empty();
        int targetSlot = reading.containerSlotIds().get(targetIndex);
        for (TransferPlan.Step step : TransferPlan.exactAmount(source.count(), left, maxStack).steps()) {
            boolean onSource = step.where() == TransferPlan.Where.SOURCE;
            int slot = onSource ? sourceSlot : targetSlot;
            switch (step.kind()) {
                case TAKE_HALF -> clicks.add(new MenuClick(slot, false, 1, true));
                case TAKE_ALL -> clicks.add(new MenuClick(slot, false, 0, true));
                case GIVE_ALL -> clicks.add(new MenuClick(slot, false, 0, false));
                case GIVE_ONE, PUT_BACK_ONE -> clicks.add(new MenuClick(slot, false, 1, false));
                case QUICK_MOVE -> clicks.add(new MenuClick(slot, true, 0, false));
            }
        }
        return Optional.of(clicks);
    }

    // 容器里放不放得下这么多：有空格，或同种同组件的堆还没满。
    // 只看收这样东西的格：熔炉产出格、模组机器的输出格空着也不算有地方。
    private static boolean hasRoom(MenuContent.Reading reading, SlotSnapshot item, int amount) {
        int max = item.stack().getMaxStackSize();
        List<SlotSnapshot> container = reading.containerSnapshots();
        for (int i = 0; i < container.size(); i++) {
            SlotSnapshot slot = container.get(i);
            if (!reading.channel().accepts(reading.containerSlotIds().get(i), item.stack())) continue;
            if (slot.isEmpty() || slot.sameIdentity(item) && slot.count() + amount <= max) return true;
        }
        return false;
    }

    // 只放一部分时挑一格放得下全部的：先并进同种的堆，再占空格。
    // 不收这样东西的格（产出格、输出格、电池格）跳过，免得拆出来的半堆放不下、卡在光标上。
    private static int roomyTarget(MenuContent.Reading reading, SlotSnapshot item, int amount) {
        int max = item.stack().getMaxStackSize();
        int empty = -1;
        List<SlotSnapshot> container = reading.containerSnapshots();
        for (int i = 0; i < container.size(); i++) {
            SlotSnapshot slot = container.get(i);
            if (!reading.channel().accepts(reading.containerSlotIds().get(i), item.stack())) continue;
            if (slot.isEmpty()) {
                if (empty < 0) empty = i;
            } else if (slot.sameIdentity(item) && slot.count() + amount <= max) {
                return i;
            }
        }
        return empty;
    }

    // 挑从哪一格搬：先挑整格都该存的（件数不超过还要存的，取最多的那格，存完正好腾出一整格）；
    // 每一格都比还要存的多，才从第一格拆出要存的那几件。腾地方要存的正是背包里的一整格，靠这条才真腾得出来。
    private static int sourceFor(List<SlotSnapshot> snapshots, String itemId, int left) {
        int first = -1;
        int whole = -1;
        for (int i = 0; i < snapshots.size(); i++) {
            SlotSnapshot snapshot = snapshots.get(i);
            if (snapshot.isEmpty() || !idOf(snapshot).equals(itemId)) continue;
            if (first < 0) first = i;
            if (snapshot.count() <= left && (whole < 0 || snapshot.count() > snapshots.get(whole).count())) whole = i;
        }
        return whole >= 0 ? whole : first;
    }

    private static int total(List<SlotSnapshot> snapshots, String itemId) {
        int sum = 0;
        for (SlotSnapshot snapshot : snapshots) {
            if (!snapshot.isEmpty() && idOf(snapshot).equals(itemId)) sum += snapshot.count();
        }
        return sum;
    }

    private static Map<String, Integer> merged(List<SlotSnapshot> snapshots) {
        Map<String, Integer> merged = new LinkedHashMap<>();
        for (SlotSnapshot snapshot : snapshots) {
            if (!snapshot.isEmpty()) merged.merge(idOf(snapshot), snapshot.count(), Integer::sum);
        }
        return merged;
    }

    private static String idOf(SlotSnapshot snapshot) {
        return BuiltInRegistries.ITEM.getKey(snapshot.stack().getItem()).toString();
    }

    // 光标上拿着东西的那一下停不得：撒手会把东西掉在界面外的流程里。
    @Override public Interruptibility interruptibility() {
        return (current != null || carryOver != null) && !menu.cursorEmpty()
                ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
    }

    // 被生存需求打断：先请游戏关上界面，恢复后由任务重新点开接着存。
    @Override public void pause() {
        menu.abandon();
    }

    // 没搬完就收尾（被取消、角色没了）：这一笔说不清，记进未确认；界面请游戏关上。
    // 界面被关掉而停的，没结算的那一笔留给重新点开后的下一趟。
    @Override public void close() {
        if (finished || menuLost) return;
        String moving = current != null ? current.itemId() : carryOver != null ? carryOver.itemId() : null;
        if (moving != null) {
            ledger.unconfirmed("往容器里搬 " + moving + " 的那一下还没结算就停下了");
            current = null;
            carryOver = null;
        }
        menu.abandon();
    }

    @Override public String describe() {
        if (current != null) return "往容器里搬 " + current.itemId();
        if (carryOver != null) return "往容器里搬 " + carryOver.itemId();
        return "往容器里搬东西";
    }
}
