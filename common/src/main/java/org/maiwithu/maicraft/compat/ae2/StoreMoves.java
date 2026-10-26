// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;

import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 「逐样存」：把要存的几样东西一样样存进网络——整格快速移动，不足一整格的尾数先凑上光标再整份交回，
 * 都存完才算这一段做完。每一下按背包加光标里那种东西的合计少了几件结算（凑尾数的几件先在光标上、
 * 交回网络时不再过背包，只看背包看不到已发生的存入），少了几件才记"存进了网络"几件；
 * 点了一会儿没少、光标上还挂着没交出去的，是网络放不下（存储满了、不收这种东西），
 * 没收下的照实点回背包再收场，不让东西挂在光标上。
 */
final class StoreMoves {

    /** 快速移动发出去后等背包同步过来的宽限（刻）；过了还没少就是网络放不下。 */
    private static final int SETTLE_TICKS = 10;

    /** 凑尾数的一下普通点击：在哪一格、左键还是右键；takesToCursor 表示这一下把东西拿上了光标。 */
    private record TrimClick(int slotId, int button, boolean takesToCursor) {}

    private final TerminalSession session;
    private final TaskRecords records;
    /** 给结果与日志看的终端说法，例如"ae2:terminal（12,64,-3 的北面）"。 */
    private final String terminal;
    /** 这一趟要存的几样；每样存完或存不进都换下一样，不重试一样。 */
    private final List<BackpackStack> stacks;
    private final String purpose;

    /** 正在存第几样。 */
    private int current;
    private BackpackStack stack;
    /** 这一样确认存进的件数。 */
    private int storedThis;
    /** 确认存进网络的件数。 */
    private int stored;
    /** 正在等结算的那一下：发之前背包加光标里这种东西的合计件数（没有在等的为 -1），与等了几刻。 */
    private int before = -1;
    private int quiet;
    /** 正在凑光标上那一小份：还没点完的几下（先拿起整份，再退回多余的件）；点完这一趟才整份交给网络。 */
    private final Deque<TrimClick> trimming = new ArrayDeque<>();
    /** 凑尾数拿起的那一格：网络没收下的部分照这一格点回背包。 */
    private int trimSourceSlot = -1;
    /** 正在把光标上没存出去的几件点回背包：点完（光标空了）才按这一趟的实情收场。 */
    private boolean returning;
    /** 光标上的部分没被网络收下时收场的原因。 */
    private Problem heldProblem;
    /** 最近一样存不进的原因：样样都存不进时按它收场。 */
    private Problem lastProblem;

    StoreMoves(TerminalSession session, TaskRecords records, String terminal, List<BackpackStack> stacks,
            String purpose) {
        this.session = session;
        this.records = records;
        this.terminal = terminal;
        this.stacks = List.copyOf(stacks);
        this.stack = this.stacks.getFirst();
        this.purpose = purpose;
    }

    // 上一下少了几件就记账；没在等的话按还要存的量挑下一格：整格用得上就快速移动，
    // 不足一整格的尾数先凑上光标再整份交回网络——快速移动一下就是一整格，不截量会把 count=8 存成 64。
    // 结算的基准是动手前背包加光标的合计：凑尾数的全程东西在光标与背包之间挪，合计不变，
    // 交回网络之后合计少几件就是网络收下几件；交回是光标对网络，只看背包会看不到已发生的存入。
    TerminalTurn proceed(TickContext tick) {
        OpenedMenu menu = session.menu();
        Optional<MenuContent.Reading> readingOptional = menu.reading();
        if (readingOptional.isEmpty()) return new TerminalTurn.FinishNow(null);
        if (menu.busy()) return new TerminalTurn.Stay(ActionStatus.running());
        MenuContent.Reading snapshot = readingOptional.get();
        int backpack = total(snapshot.playerSnapshots());
        int onCursor = cursorCount(snapshot);
        int onHand = backpack + onCursor;
        // 正在凑光标上那一小份：一下一下点，退回多余的件；点完这趟光标上正好是要存的几件，整份交给网络。
        if (!trimming.isEmpty()) {
            TrimClick click = trimming.peek();
            if (click.takesToCursor()) menu.noteCursorTakenFrom(click.slotId());
            if (!menu.click(click.slotId(), click.button())) return new TerminalTurn.Stay(ActionStatus.running());
            trimming.poll();
            if (trimming.isEmpty()) {
                // 这一小份凑齐了：整份交给网络（光标对网络的快速移动）。本刻发不出去下一刻再试。
                session.compat().putBack(session.rawPage(tick.player()));
            }
            return new TerminalTurn.Stay(ActionStatus.progressed());
        }
        if (returning) return putLeftoversBack(session.menu(), onCursor);
        if (before >= 0) {
            TerminalTurn settled = settleLastMove(onHand, onCursor);
            if (settled != null) return settled;
        }
        if (storedThis >= stack.count()) return nextKind(null);
        return switch (NetworkStorePlan.next(stack.count() - storedThis, slotCounts(snapshot))) {
            case NetworkStorePlan.Done done -> nextKind(storedThis > 0 ? null
                    : Problem.of(Problem.Kind.NEED_ITEM, "背包里已经没有 " + stack.itemId() + " 了", null));
            case NetworkStorePlan.QuickMove move -> {
                // 本刻发不出去（上一下没结清、界面没画好、没有交互机会）就下一刻再点，不算点过。
                if (!menu.quickMove(slotIdOf(snapshot, move.slotIndex()))) {
                    yield new TerminalTurn.Stay(ActionStatus.running());
                }
                before = onHand;
                quiet = 0;
                yield new TerminalTurn.Stay(ActionStatus.progressed());
            }
            case NetworkStorePlan.TrimToCursor trim -> {
                // 拿起这一格整份（左键），再把多出的几件一件件右键退回源格；点完的下一刻交给网络。
                // 拆量的几下要在交回网络前点完，不然快速移动会把整格多存进去。
                // 基准在此刻记下：这几下只会在背包与光标之间挪，合计不变，交回后才见分晓。
                int slotId = slotIdOf(snapshot, trim.slotIndex());
                trimming.add(new TrimClick(slotId, 0, true));
                for (int i = 0; i < trim.slotCount() - trim.keep(); i++) {
                    trimming.add(new TrimClick(slotId, 1, false));
                }
                before = onHand;
                trimSourceSlot = slotId;
                quiet = 0;
                yield new TerminalTurn.Stay(ActionStatus.progressed());
            }
        };
    }

    // 结算上一击：合计少了几件就是存进了网络几件，账与背包光标的变化一致；还没看出结果或到期限收场的，
    // 返回要推进的走向（返回 null 是结算完了，接着挑下一步）。
    private TerminalTurn settleLastMove(int onHand, int onCursor) {
        return switch (NetworkStoreSettlement.settle(before, onHand, onCursor)) {
            case NetworkStoreSettlement.Stored storedMove -> {
                credit(storedMove.moved());
                before = -1;
                yield null;
            }
            case NetworkStoreSettlement.Held held -> {
                // 期限到了光标还拿着没收完的：已经进了网络的照实记，剩下的点回背包再按放不下收场。
                if (++quiet < SETTLE_TICKS) yield new TerminalTurn.Stay(ActionStatus.running());
                if (held.moved() > 0) credit(held.moved());
                before = -1;
                heldProblem = held.moved() > 0
                        ? Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "网络只收下了 " + stack.itemId()
                                + " " + held.moved() + " 件，剩下的放不下（存储满了或不收这种东西）", null)
                        : Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                                "网络放不下 " + stack.itemId() + "（存储满了或不收这种东西）", null);
                returning = true;
                yield new TerminalTurn.Stay(ActionStatus.progressed());
            }
            case NetworkStoreSettlement.Waiting waiting -> {
                if (++quiet < SETTLE_TICKS) yield new TerminalTurn.Stay(ActionStatus.running());
                // 背包和光标都没少：网络放不下（存储满了、不收这种东西），如实记一笔，接着存下一样。
                before = -1;
                yield nextKind(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                        "网络放不下 " + stack.itemId() + "（存储满了或不收这种东西）", null));
            }
        };
    }

    // 把网络没收下的几件点回凑尾数的那一格：光标空了才按这一趟的实情收场，不让东西挂在光标上。
    private TerminalTurn putLeftoversBack(OpenedMenu menu, int onCursor) {
        if (onCursor > 0) {
            return menu.click(trimSourceSlot, 0)
                    ? new TerminalTurn.Stay(ActionStatus.progressed())
                    : new TerminalTurn.Stay(ActionStatus.running());
        }
        returning = false;
        return nextKind(heldProblem);
    }

    // 按实况记账：合计少了几件就是存进了网络几件，账与背包的变化一致。
    private void credit(int moved) {
        stored += moved;
        storedThis += moved;
        records.change(new Change(Change.Kind.ITEM_STORED, stack.itemId(), moved,
                purpose + "：存进了 " + terminal + " 连着的网络"));
    }

    // 这一样存完或存不进：存不进的记一笔试过；还有下一样就接着存，都过完了才收场去关界面。
    private TerminalTurn nextKind(Problem problem) {
        if (problem != null) {
            lastProblem = problem;
            records.attempt("把 " + stack.itemId() + " 存进 " + terminal + " 连着的网络", problem.message());
        }
        if (++current >= stacks.size()) {
            return new TerminalTurn.WrapUp(stored > 0 ? null : lastProblem, false);
        }
        stack = stacks.get(current);
        storedThis = 0;
        quiet = 0;
        return new TerminalTurn.Stay(ActionStatus.progressed());
    }

    /** 存进了网络几件：一件都没存进时外层按收场原因失败。 */
    int stored() {
        return stored;
    }

    /** 正在存的一样，给面板与日志看进展到哪了。 */
    BackpackStack current() {
        return stack;
    }

    // 凑光标上那一小份、没收下的部分还没点回背包时停不得：东西在光标上，撒手会落进流程外。
    boolean unsafeToStop() {
        return !trimming.isEmpty() || returning;
    }

    // 背包里这种东西一共几件。
    private int total(List<SlotSnapshot> player) {
        int sum = 0;
        for (SlotSnapshot slot : player) {
            if (!slot.isEmpty() && itemId(slot).equals(stack.itemId())) sum += slot.count();
        }
        return sum;
    }

    // 光标上拿着的这种东西有几件：交回网络是光标对网络，网络没收下的部分挂在光标上，结算要把它算作还没存出去的那份。
    private int cursorCount(MenuContent.Reading reading) {
        SlotSnapshot carried = reading.channel().cursor();
        return !carried.isEmpty() && itemId(carried).equals(stack.itemId()) ? carried.count() : 0;
    }

    // 背包里这种东西每一格的件数，按格子顺序；别的格不算。
    private List<Integer> slotCounts(MenuContent.Reading reading) {
        List<Integer> counts = new ArrayList<>();
        for (SlotSnapshot slot : reading.playerSnapshots()) {
            if (!slot.isEmpty() && itemId(slot).equals(stack.itemId())) counts.add(slot.count());
        }
        return counts;
    }

    // 这种东西的第 itemSlotIndex 格在界面里的槽位号：计划里的格序号是"这种东西的第几格"，要换算回界面槽位号。
    private int slotIdOf(MenuContent.Reading reading, int itemSlotIndex) {
        int seen = -1;
        for (int i = 0; i < reading.playerSnapshots().size(); i++) {
            SlotSnapshot slot = reading.playerSnapshots().get(i);
            if (slot.isEmpty() || !itemId(slot).equals(stack.itemId())) continue;
            if (++seen == itemSlotIndex) return reading.playerSlotIds().get(i);
        }
        throw new IllegalStateException("要存的 " + stack.itemId() + " 第 "
                + itemSlotIndex + " 格不在背包里，动手前的快照和现在的对不上");
    }

    private String itemId(SlotSnapshot slot) {
        return BuiltInRegistries.ITEM.getKey(slot.stack().getItem()).toString();
    }
}
