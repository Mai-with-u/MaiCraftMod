// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import org.maiwithu.maicraft.behavior.menu.MoveConfirmation.Verdict;
import org.maiwithu.maicraft.kernel.task.ActionStatus;

/**
 * 对着开着的界面跑一笔搬运：调用方提交一笔（点哪几下、怎么核对），这里逐刻点、等同步、
 * 按核对方式出结论。每刻至多发一下；发不出去的留在队头下一刻再点，不算点过。
 * 没能确认的绝不重发——重试只能是调用方拿着新证据显式再提交一笔。
 * 界面中途没了：点出去没等到结果的那几下数进结论，原样交给调用方。
 * 这里只回答"这一笔在游戏里发生了什么"；拿它怎么记账、要不要再试，是调用方的决定。
 * 只认点格子的笔：模组协议的操作走挂起动作那一族，不在这里。
 */
public final class TransferRun {

    /** 一笔点完后等两侧内容同步过来的宽限（刻）；过了还定不下来就承认没能确认。 */
    private static final int SETTLE_TICKS = 5;

    /** 一笔搬运：按顺序要点哪几下、怎么核对、这一笔是干什么的（写进没能确认的交代里）。 */
    public record Move(List<MenuClick> clicks, Check check, String what) {
        public Move {
            if (clicks.isEmpty()) throw new IllegalArgumentException("一笔搬运至少要有点击");
            clicks = List.copyOf(clicks);
            what = Objects.requireNonNull(what, "what");
        }
    }

    /** 一笔怎么算结清：标准核对式都长在搬运确认上，提交时由调用方按两侧的性质挑。 */
    public sealed interface Check {
        /** 普通槽：源格恰好减、目标格恰好增。 */
        record Normal(int sourceSlotId, int targetSlotId, int amount) implements Check {}
        /** 机器槽：源格恰好减、光标空；目标格不核对（机器可能在回显到达前就把东西吃掉）。 */
        record Machine(int sourceSlotId, int amount) implements Check {}
        /** 搬进背包：背包侧配对总数增长、光标空；背包侧不核对单格，源侧也不核对。 */
        record IntoBackpack(Predicate<SlotSnapshot> matched, int amount) implements Check {}
        /** 存进容器：容器侧配对总数增长、光标空；容器侧不核对单格，背包侧少了多少由调用方对照前后差额交代。 */
        record IntoContainer(Predicate<SlotSnapshot> matched) implements Check {}
        /** 放下光标：光标整个空掉、目标格恰好多了光标上那么多件；源就是光标本身。 */
        record PutDown(int targetSlotId) implements Check {}
    }

    /** 本刻推进的进展。 */
    public sealed interface Progress {
        /** 点出去了一下。 */
        enum Advanced implements Progress { ADVANCED }
        /** 还在等：上一下没结清、同步还没到、或还没到下结论的时候。 */
        enum Waiting implements Progress { WAITING }
        /** 这一笔出结论了；下一刻才能提交下一笔。 */
        record Settled(MoveResult result) implements Progress {}
    }

    /** 一笔搬运的结论。 */
    public sealed interface MoveResult {
        /** 结清：实际搬动了几件（可能少于计划——对面没那么多）。 */
        record Confirmed(int amount) implements MoveResult {}
        /** 点出去了纹丝不动：多半是放不下。 */
        enum NotMoved implements MoveResult { NOT_MOVED }
        /** 对不上：两侧没按核对式对齐，或光标上的东西没能放下来。 */
        enum Diverged implements MoveResult { DIVERGED }
        /** 界面中途没了；点出去没等到结果的有几下。 */
        record MenuGone(int submittedClicks) implements MoveResult {}
    }

    private enum Stage { IDLE, PRESSING, SETTLING }

    private final OpenedMenu menu;
    private Stage stage = Stage.IDLE;
    private Move move;
    private final Deque<MenuClick> pressing = new ArrayDeque<>();
    /** 动手前冻结的证据：核对要的格子、配对总数与光标；第一下点出去之前抄下来，还没动手时为 null。 */
    private Evidence before;
    private int quietTicks;

    public TransferRun(OpenedMenu menu) {
        this.menu = Objects.requireNonNull(menu, "menu");
    }

    /** 提交一笔搬运；上一笔还没出结论时拒绝，先把结论取走。 */
    public void begin(Move next) {
        if (stage != Stage.IDLE) throw new IllegalStateException("上一笔还没出结论，不能提交下一笔");
        this.move = Objects.requireNonNull(next, "move");
        this.pressing.clear();
        this.pressing.addAll(next.clicks());
        this.before = null;
        this.quietTicks = 0;
        this.stage = Stage.PRESSING;
    }

    /** 上一笔已经出结论、可以提交下一笔了。 */
    public boolean idle() {
        return stage == Stage.IDLE;
    }

    /** 中途撒手：这一笔不再点了，点出去没等到结果的有几下，交回调用方记没能确认；界面不由这里关。 */
    public int abandon() {
        int submitted = move == null ? 0 : move.clicks().size() - pressing.size();
        stage = Stage.IDLE;
        pressing.clear();
        return submitted;
    }

    /** 一笔没点完且光标上拿着东西：半途撒手会把拆出来的尾数交给关界面的流程，停不得。 */
    public boolean unsafeToStop() {
        return stage == Stage.PRESSING && !menu.cursorEmpty();
    }

    /** 本刻推进一笔：发下一下、等同步、按核对方式出结论。没有在跑的一笔时调用是程序错误。 */
    public Progress tick() {
        return switch (stage) {
            case IDLE -> throw new IllegalStateException("没有在跑的一笔");
            case PRESSING -> press();
            case SETTLING -> settle();
        };
    }

    /**
     * 推进一刻并把进展换算成动作的一刻：点出去算进展，等同步算还在做，出了结论交给结论钩子记账并接住。
     * 记账是调用方的决定：钩子拿到带全证据的结论，返回接住之后的动作状态。
     * 没有在跑的一笔时调用是程序错误。
     */
    public ActionStatus tickAction(Function<MoveResult, ActionStatus> settled) {
        return switch (tick()) {
            case Progress.Advanced advanced -> ActionStatus.progressed();
            case Progress.Waiting waiting -> ActionStatus.running();
            case Progress.Settled done -> settled.apply(done.result());
        };
    }

    // 点这一笔的下一下：快速移动整堆；普通点击先把要从的格子登记给界面会话（中途关界面时
    // 光标上的东西放回这一格），再发左右键。第一下点出去之前先冻结动手前的证据。
    private Progress press() {
        if (menu.busy()) return Progress.Waiting.WAITING;
        Optional<MenuContent.Reading> reading = menu.reading();
        if (reading.isEmpty()) return menuGone();
        if (before == null) before = Evidence.freeze(menu, reading.get(), move.check());
        MenuClick click = pressing.peek();
        boolean sent = send(click);
        if (!sent) return Progress.Waiting.WAITING;
        pressing.poll();
        if (pressing.isEmpty()) {
            stage = Stage.SETTLING;
            quietTicks = 0;
        }
        return Progress.Advanced.ADVANCED;
    }

    // 一笔的几下都点完了：等两侧内容按核对式对上；宽限内定不下来就承认没能确认。
    private Progress settle() {
        Optional<MenuContent.Reading> reading = menu.reading();
        if (reading.isEmpty()) return menuGone();
        Verdict verdict = verdict(reading.get());
        if (verdict == Verdict.CONFIRMED) {
            stage = Stage.IDLE;
            return new Progress.Settled(new MoveResult.Confirmed(confirmedAmount(reading.get())));
        }
        if (verdict == Verdict.DIVERGED) {
            stage = Stage.IDLE;
            return new Progress.Settled(MoveResult.Diverged.DIVERGED);
        }
        if (++quietTicks <= SETTLE_TICKS) return Progress.Waiting.WAITING;
        // 宽限到点还定不下来：光标上还拿着东西的说不清搬了多少；纹丝不动的多半是放不下。
        stage = Stage.IDLE;
        return new Progress.Settled(menu.cursorEmpty() ? MoveResult.NotMoved.NOT_MOVED : MoveResult.Diverged.DIVERGED);
    }

    // 界面没了：点出去没等到结果的那几下数进结论；一下都没发出去的不算，那只是没点成。
    private Progress menuGone() {
        int submitted = move.clicks().size() - pressing.size();
        stage = Stage.IDLE;
        return new Progress.Settled(new MoveResult.MenuGone(submitted));
    }

    private boolean send(MenuClick click) {
        if (click.quickMove()) return menu.quickMove(click.slotId());
        if (click.takesToCursor()) menu.noteCursorTakenFrom(click.slotId());
        return menu.click(click.slotId(), click.button());
    }

    // 按核对式对现场证据下结论。
    private Verdict verdict(MenuContent.Reading reading) {
        return switch (move.check()) {
            case Check.Normal(int sourceSlotId, int targetSlotId, int amount) -> MoveConfirmation.normal(
                    before.slot(sourceSlotId), before.slot(targetSlotId),
                    snapshot(reading, sourceSlotId), snapshot(reading, targetSlotId), amount);
            case Check.Machine(int sourceSlotId, int amount) -> MoveConfirmation.machine(
                    before.slot(sourceSlotId), snapshot(reading, sourceSlotId), menu.cursorEmpty(), amount);
            case Check.IntoBackpack(Predicate<SlotSnapshot> matched, int amount) -> MoveConfirmation.intoBackpack(
                    before.matchedBefore, matchedTotal(reading.playerSnapshots(), matched), menu.cursorEmpty(), amount);
            case Check.IntoContainer(Predicate<SlotSnapshot> matched) -> MoveConfirmation.intoContainer(
                    before.matchedBefore, matchedTotal(reading.playerSnapshots(), matched),
                    before.containerMatchedBefore, matchedTotal(reading.containerSnapshots(), matched),
                    menu.cursorEmpty());
            case Check.PutDown(int targetSlotId) -> MoveConfirmation.normal(
                    before.cursor, before.slot(targetSlotId), menu.cursor(),
                    snapshot(reading, targetSlotId), before.cursor.count());
        };
    }

    // 结清了报实际搬动几件：点格子的笔按计划件数，搬进背包与存进容器的按那一侧真的多出来的，放下光标的按光标上的。
    private int confirmedAmount(MenuContent.Reading reading) {
        Check check = move.check();
        if (check instanceof Check.IntoBackpack(Predicate<SlotSnapshot> matched, int ignored)) {
            return matchedTotal(reading.playerSnapshots(), matched) - before.matchedBefore;
        }
        if (check instanceof Check.IntoContainer(Predicate<SlotSnapshot> matched)) {
            return matchedTotal(reading.containerSnapshots(), matched) - before.containerMatchedBefore;
        }
        if (check instanceof Check.PutDown) return before.cursor.count();
        if (check instanceof Check.Normal(int ignored, int ignoredToo, int amount)) return amount;
        if (check instanceof Check.Machine(int ignored, int amount)) return amount;
        throw new IllegalStateException("核对式到这里都有计划件数");
    }

    // 一侧里配对的东西一共几件；搬进背包与存进容器的核对按它动手前后的差结算。
    private static int matchedTotal(List<SlotSnapshot> snapshots, Predicate<SlotSnapshot> matched) {
        int total = 0;
        for (SlotSnapshot snapshot : snapshots) {
            if (!snapshot.isEmpty() && matched.test(snapshot)) total += snapshot.count();
        }
        return total;
    }

    // 界面两侧按槽位号找格子的样子：核对要的格子两侧都可能在。
    private static SlotSnapshot snapshot(MenuContent.Reading reading, int slotId) {
        int index = reading.playerSlotIds().indexOf(slotId);
        if (index >= 0) return reading.playerSnapshots().get(index);
        index = reading.containerSlotIds().indexOf(slotId);
        return index >= 0 ? reading.containerSnapshots().get(index) : SlotSnapshot.empty();
    }

    /** 动手前冻结的证据：两侧各格的样子、两侧配对总数与光标，都在第一下点出去之前抄下来。 */
    private record Evidence(SlotSnapshot cursor, int matchedBefore, int containerMatchedBefore,
                            Map<Integer, SlotSnapshot> slots) {
        static Evidence freeze(OpenedMenu menu, MenuContent.Reading reading, Check check) {
            Map<Integer, SlotSnapshot> slots = new HashMap<>();
            for (int i = 0; i < reading.playerSlotIds().size(); i++) {
                slots.put(reading.playerSlotIds().get(i), reading.playerSnapshots().get(i));
            }
            for (int i = 0; i < reading.containerSlotIds().size(); i++) {
                slots.put(reading.containerSlotIds().get(i), reading.containerSnapshots().get(i));
            }
            Predicate<SlotSnapshot> matched = null;
            if (check instanceof Check.IntoBackpack(Predicate<SlotSnapshot> m, int ignored)) matched = m;
            if (check instanceof Check.IntoContainer(Predicate<SlotSnapshot> m)) matched = m;
            return new Evidence(menu.cursor(),
                    matched == null ? 0 : TransferRun.matchedTotal(reading.playerSnapshots(), matched),
                    matched == null || !(check instanceof Check.IntoContainer)
                            ? 0 : TransferRun.matchedTotal(reading.containerSnapshots(), matched),
                    slots);
        }

        SlotSnapshot slot(int slotId) {
            return slots.getOrDefault(slotId, SlotSnapshot.empty());
        }
    }
}
