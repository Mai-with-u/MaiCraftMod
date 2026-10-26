// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.maiwithu.maicraft.behavior.menu.MenuClick;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MoveConfirmation;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.menu.TransferRun;
import org.maiwithu.maicraft.game.menu.CursorStowing;
import org.maiwithu.maicraft.game.menu.MenuConfirmation;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 「逐笔搬」：把网络存货里要的东西一笔笔搬进背包——缺一组以上整组取，不足一组一件一件取再放下光标；
 * 收场时也把光标上的东西放回背包（背包放不下放回网络）。每一笔都等游戏确认：整组取看背包里多了几件，
 * 取一件看光标上多了一件，放下看光标空了、那一格多了；只记确认过的量，没动静就不再硬点，对不上的如实记下不凑数。
 * 现货取完还缺交给「下单等成品」的一段；要的是一桶流体而网络里存的是流体时，像玩家拿空桶去装。
 */
final class TakeMoves {

    /** 一下取货等游戏确认的期限（刻）。 */
    private static final int TAKE_TIMEOUT_TICKS = 40;
    /** 收场前放好光标的期限（刻）：到点还没放好就照常关界面，光标上的东西交给游戏的关闭流程。 */
    private static final int CURSOR_TICKS = 40;
    /** 把空桶放进网络后等它出现在存货里的期限（刻）。 */
    private static final int BUCKET_WAIT_TICKS = 40;
    /**
     * 没看到要的东西时，存货表连着这么多刻（刻）不再变才下"网络里没有"的结论：大网络的存货 AE2 分好几个包同步，
     * 第一个包到了就判没有，实机主城 AE 里的铁锭、铁板都被说成"没有"。
     */
    private static final int STOCK_STABLE_TICKS = 10;
    /** 等存货表同步齐的总期限（刻）：到点还没稳下来就按此刻看到的下结论。 */
    private static final int STOCK_FULL_WAIT_TICKS = 100;

    /** 正在等确认的那一下是哪种取法。 */
    private enum Pending { STACK, ONE }

    private final TakeSession session;
    /** 现货不够时问它下不下单、等不等成品。 */
    private final WaitsForCraft craft;
    /** 只看一眼：等存货同步齐记下来就收场，什么都不拿。 */
    private final boolean lookOnly;
    /** 网络里现成没有时允不允许下单合成；坠落中取落地料不允许，等成品时人已经落地了。 */
    private final boolean allowCrafting;

    // 正在等确认的一下：取法、取的那种东西、点之前角色那一侧与光标的样子。
    private PendingMenuAction pending;
    private Pending pendingKind;
    private SlotSnapshot pendingSample;
    private List<Integer> pendingSlotIds;
    private List<SlotSnapshot> pendingBefore;

    // 放下光标的一笔交给搬运执行逐刻点、按光标空掉与那一格增多核对；记下是哪种东西好记账。
    private boolean putDownPending;
    private String putDownItem;

    // 收场前放好光标：已经等了几刻、正在等确认的"放回网络"那一下。
    private boolean clearing;
    private int cursorWaited;
    private PendingMenuAction putBackPending;

    // 没看到要的东西时，存货表上一刻有几条、连着几刻没变、一共等了几刻。
    private int lastStockSize = -1;
    private int stockStableTicks;
    private int stockFullWaited;

    // 为装流体把背包里的空桶放进网络了，等它在存货里出现。
    private boolean bucketOffered;
    private int bucketWaited;

    TakeMoves(TakeSession session, WaitsForCraft craft, boolean lookOnly, boolean allowCrafting) {
        this.session = session;
        this.craft = craft;
        this.lookOnly = lookOnly;
        this.allowCrafting = allowCrafting;
    }

    // 逐笔取：上一下还在等确认就本刻不动手；拿够、网络里没了、背包放不下就收场去放光标、关界面。
    TerminalTurn proceed(TickContext tick) {
        Optional<MenuContent.Reading> reading = session.reading();
        if (reading.isEmpty()) {
            // 界面被合上了（打断或服务端关的）：取到多少由引擎清点，这里照常收场。
            return new TerminalTurn.FinishNow(null);
        }
        if (pending != null) return awaitTake(tick.player(), reading.get());
        if (putDownPending) return tickPutDown();
        if (session.menu().busy()) return new TerminalTurn.Stay(ActionStatus.running());

        PlayerContext player = tick.player();
        AbstractContainerMenu raw = session.rawPage(player);
        List<Ae2TerminalMenu.StockEntry> stock = session.compat().networkStock(raw).orElse(List.of());
        // 只看一眼：等存货表稳下来、记成看全了就收场去关界面，什么都不拿。
        if (lookOnly) {
            if (stockStillArriving(stock.size())) return new TerminalTurn.Stay(ActionStatus.running());
            session.compat().networkLinked(raw).ifPresent(linked -> session.access().remember(linked, stock, true));
            return new TerminalTurn.WrapUp(null, false);
        }
        SlotSnapshot cursor = SlotSnapshot.of(raw.getCarried());
        // 光标上挂着不是这次要取的东西：多半是有人动过光标，不是程序出错。不在上面叠，收场时由关界面放回背包。
        if (!cursor.isEmpty() && !session.wanted(cursor.stack())) {
            return new TerminalTurn.WrapUp(Problem.of(Problem.Kind.STUCK, "终端界面的光标上挂着 " + cursor.count()
                    + " 个" + itemId(cursor.stack()) + "，不是这次要取的，先停手", null), false);
        }
        Ae2TerminalMenu.StockEntry entry = cursor.isEmpty() ? firstWanted(stock) : entryFor(cursor, stock);
        if (entry == null) {
            int confirmed = session.confirmedTotal();
            int remaining = session.request().count() - confirmed;
            // 成品刚好取够、网络里也没了：拿够就收场，不再等下单的那一单。
            if (remaining <= 0) return new TerminalTurn.WrapUp(null, false);
            // 一件还没取到就没看见：可能是存货还没同步齐，等表连着几刻不再变多再说没有。
            if (confirmed == 0 && stockStillArriving(stock.size())) return new TerminalTurn.Stay(ActionStatus.running());
            // 等到存货表不再变了：这时记下的才算看全了，下次报价可以照它说"没有"。
            if (confirmed == 0) {
                session.compat().networkLinked(raw)
                        .ifPresent(linked -> session.access().remember(linked, stock, true));
            }
            // 坠落中取落地料不在空中下单：等网络把东西做出来时人已经落地了，现成没有就收场。
            if (!allowCrafting) return new TerminalTurn.WrapUp(session.nothingLeft(), false);
            // 现货取完了还缺（或一开始就没有）：网络里那一条有样板就像玩家一样下单补缺口，件数封顶在背包装得下的；
            // 自己之前下的这样东西还在做就不再下单、接着等；下过单就在跟前等成品进网络，进来一批取一批。
            List<SlotSnapshot> playerSide = reading.get().playerSnapshots();
            return craft.onNoStock(raw, stock, confirmed, remaining,
                    sample -> NetworkTakePlan.room(sample, playerSide));
        }
        int remaining = session.request().count() - session.confirmedTotal();
        if (entry.fillsBucket() && cursor.isEmpty()) return fillBucket(entry, stock, reading.get(), raw, remaining);
        NetworkTakePlan.Move move = NetworkTakePlan.next(remaining, cursor, entry,
                reading.get().playerSlotIds(), reading.get().playerInventoryIndices(),
                reading.get().playerSnapshots());
        return switch (move) {
            case NetworkTakePlan.TakeStack takeStack -> submit(Pending.STACK, entry, reading.get(), raw,
                    () -> session.compat().takeStack(raw, takeStack.serial()));
            case NetworkTakePlan.TakeOne takeOne -> submit(Pending.ONE, entry, reading.get(), raw,
                    () -> session.compat().takeOne(raw, takeOne.serial()));
            case NetworkTakePlan.PutDown putDown -> {
                putDown(putDown.slotId(), cursor);
                yield tickPutDown();
            }
            case NetworkTakePlan.NoRoom noRoom -> new TerminalTurn.WrapUp(Problem.of(Problem.Kind.INVENTORY_FULL,
                    "背包放不下从 " + session.access().describe() + " 取的" + session.request().wanted().describe(),
                    null), false);
            case NetworkTakePlan.Done done -> new TerminalTurn.WrapUp(remaining > 0 ? session.nothingLeft() : null,
                    false);
        };
    }

    // 要的是一桶水（岩浆）而网络里存的是流体：像玩家拿空桶点终端里的水。网络里有空桶就直接点，
    // AE 拿一只装满放进背包；没有就先把背包里的空桶放进网络再点；两边都没有空桶如实说。
    private TerminalTurn fillBucket(Ae2TerminalMenu.StockEntry entry, List<Ae2TerminalMenu.StockEntry> stock,
            MenuContent.Reading reading, AbstractContainerMenu raw, int remaining) {
        if (remaining <= 0) return new TerminalTurn.WrapUp(null, false);
        boolean networkHasBucket = stock.stream()
                .anyMatch(other -> !other.fillsBucket() && other.stored() > 0 && other.sample().is(Items.BUCKET));
        if (networkHasBucket) {
            if (stowSlot(entry.sample().copyWithCount(1), reading) < 0) {
                return new TerminalTurn.WrapUp(Problem.of(Problem.Kind.INVENTORY_FULL,
                        "背包放不下从 " + session.access().describe() + " 装的" + session.request().wanted().describe(),
                        null), false);
            }
            return submit(Pending.STACK, entry, reading, raw, () -> session.compat().fillBucket(raw, entry.serial()));
        }
        // 背包里的空桶放进网络：放过一次还没在存货里出现就等一会儿，等不到如实说。
        if (bucketOffered) {
            return ++bucketWaited <= BUCKET_WAIT_TICKS ? new TerminalTurn.Stay(ActionStatus.running())
                    : new TerminalTurn.WrapUp(Problem.of(Problem.Kind.STUCK,
                            "把空桶放进 " + session.access().describe() + " 的网络后一直没看到", null), false);
        }
        for (int i = 0; i < reading.playerSnapshots().size(); i++) {
            SlotSnapshot slot = reading.playerSnapshots().get(i);
            if (slot.isEmpty() || !slot.stack().is(Items.BUCKET)) continue;
            if (!session.menu().quickMove(reading.playerSlotIds().get(i))) {
                return new TerminalTurn.Stay(ActionStatus.running());
            }
            bucketOffered = true;
            return new TerminalTurn.Stay(ActionStatus.progressed());
        }
        return new TerminalTurn.WrapUp(Problem.of(Problem.Kind.NEED_ITEM, session.access().describe() + " 的网络里有"
                + itemId(entry.sample()) + "要装的流体，可网络里和背包里都没有空桶",
                "先备一只空桶（三块铁锭）再来装"), false);
    }

    // 发一下取货：本刻发不出去（上一下没结清、界面没画好、没有交互机会）就下一刻再试。
    private TerminalTurn submit(Pending kind, Ae2TerminalMenu.StockEntry entry, MenuContent.Reading reading,
            AbstractContainerMenu raw, Runnable send) {
        SlotSnapshot sample = SlotSnapshot.of(entry.sample());
        List<Integer> slotIds = reading.playerSlotIds();
        List<SlotSnapshot> before = reading.playerSnapshots();
        SlotSnapshot cursorBefore = SlotSnapshot.of(raw.getCarried());
        MenuConfirmation confirmation = (context, ignored) -> {
            AbstractContainerMenu now = session.rawPage(context);
            NetworkTakeConfirmation.Result result = kind == Pending.STACK
                    ? NetworkTakeConfirmation.stackTaken(sample, before, snapshots(now, slotIds),
                            SlotSnapshot.of(now.getCarried()))
                    : NetworkTakeConfirmation.oneTaken(sample, cursorBefore, SlotSnapshot.of(now.getCarried()));
            return menuVerdict(result.verdict());
        };
        String what = (kind == Pending.STACK ? "从 ME 终端整组取 " : "从 ME 终端取一件 ") + itemId(entry.sample());
        Optional<PendingMenuAction> sent = session.menu().submitModAction(what, send, confirmation, TAKE_TIMEOUT_TICKS);
        if (sent.isEmpty()) return new TerminalTurn.Stay(ActionStatus.running());
        pending = sent.get();
        pendingKind = kind;
        pendingSample = sample;
        pendingSlotIds = slotIds;
        pendingBefore = before;
        return new TerminalTurn.Stay(ActionStatus.progressed());
    }

    // 等那一下取货的结论：整组取确认了就记账；取一件确认了东西在光标上，放下时再记；没动静或对不上就收场。
    private TerminalTurn awaitTake(PlayerContext player, MenuContent.Reading reading) {
        pending = player.menuActions().poll(player, pending);
        if (!pending.terminal()) return new TerminalTurn.Stay(ActionStatus.running());
        PendingMenuAction settled = pending;
        pending = null;
        AbstractContainerMenu raw = session.rawPage(player);
        String item = itemId(pendingSample.stack());
        switch (settled.status()) {
            case CONFIRMED_APPLIED -> {
                if (pendingKind == Pending.STACK) {
                    NetworkTakeConfirmation.Result result = NetworkTakeConfirmation.stackTaken(pendingSample,
                            pendingBefore, snapshots(raw, pendingSlotIds), SlotSnapshot.of(raw.getCarried()));
                    if (result.amount() > 0) session.settlement().confirm(item, result.amount());
                }
                return new TerminalTurn.Stay(ActionStatus.progressed());
            }
            case CONFIRMED_NOT_APPLIED -> {
                // 点了没动静：背包放不下、网络刚断、或这种东西刚被别人取走。不再硬点。
                return new TerminalTurn.WrapUp(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                        "在 " + session.access().describe() + " 取" + item + "没取到（背包放不下、网络断了或刚被别人取走）",
                        null), false);
            }
            default -> {
                session.settlement().unconfirmed("从 " + session.access().describe() + " 取" + item
                        + "的一下没能确认：" + settled.detail());
                return new TerminalTurn.WrapUp(Problem.of(Problem.Kind.STUCK,
                        "从 " + session.access().describe() + " 取" + item + "的一下没能确认", null), false);
            }
        }
    }

    // 把光标上的东西放进那一格：一笔交给搬运执行普通点格子，按光标空了、那一格多了核对；
    // 本刻点不出去（刚取过货、界面还没重新画好）就下一刻再点，不算点过。
    private void putDown(int slotId, SlotSnapshot cursor) {
        putDownItem = itemId(cursor.stack());
        putDownPending = true;
        session.transport().begin(new TransferRun.Move(List.of(new MenuClick(slotId, false, 0, false)),
                new TransferRun.Check.PutDown(slotId), "把光标上的东西放进背包"));
    }

    // 放下光标的一笔：搬运执行逐刻点，出了结论按现场记账；纹丝不动（多半那一格放不下）回到计划重新挑格。
    private TerminalTurn tickPutDown() {
        return switch (session.transport().tick()) {
            case TransferRun.Progress.Advanced advanced -> new TerminalTurn.Stay(ActionStatus.progressed());
            case TransferRun.Progress.Waiting waiting -> new TerminalTurn.Stay(ActionStatus.running());
            case TransferRun.Progress.Settled settled -> onPutDownSettled(settled.result());
        };
    }

    // 放下光标的结论：确认了记账；对不上如实记下收场。已经在收场放光标时对不上：不再折腾，直接去关界面。
    private TerminalTurn onPutDownSettled(TransferRun.MoveResult result) {
        putDownPending = false;
        if (result instanceof TransferRun.MoveResult.Confirmed(int amount)) {
            session.settlement().confirm(putDownItem, amount);
            return new TerminalTurn.Stay(ActionStatus.progressed());
        }
        if (result instanceof TransferRun.MoveResult.MenuGone) {
            // 界面被合上了：取到多少由引擎清点，这里照常收场。
            return new TerminalTurn.FinishNow(null);
        }
        if (result instanceof TransferRun.MoveResult.NotMoved) {
            return new TerminalTurn.Stay(ActionStatus.running());
        }
        session.settlement().unconfirmed("把光标上的" + putDownItem + "放进背包时两边对不上");
        if (clearing) return new TerminalTurn.CloseOnly();
        return new TerminalTurn.WrapUp(Problem.of(Problem.Kind.STUCK,
                "把光标上的" + putDownItem + "放进背包时两边对不上", null), false);
    }

    // 收场前先把光标上的东西放回背包，再关界面：放进背包里能整个装下的一格，背包放不下就放回网络。
    // 到期限还没放好（例如一直点不出去）就照常关界面，光标上的东西交给游戏的关闭流程。
    TerminalTurn clearCursor(TickContext tick) {
        clearing = true;
        AbstractContainerMenu raw = session.rawPage(tick.player());
        Optional<MenuContent.Reading> reading = session.reading();
        if (reading.isEmpty()) return new TerminalTurn.FinishNow(null);
        if (putBackPending != null) return awaitPutBack(tick.player());
        if (putDownPending) return tickPutDown();
        SlotSnapshot cursor = SlotSnapshot.of(raw.getCarried());
        if (cursor.isEmpty() || ++cursorWaited > CURSOR_TICKS) return new TerminalTurn.CloseOnly();
        if (session.menu().busy()) return new TerminalTurn.Stay(ActionStatus.running());
        int slot = stowSlot(cursor.stack().copyWithCount(cursor.count()), reading.get());
        if (slot >= 0) {
            putDown(slot, cursor);
            return tickPutDown();
        }
        return putBack(cursor, raw);
    }

    // 背包里没有一格装得下：在终端的网络格子空白处点一下，把光标上的东西放回网络；本刻发不出去就下一刻再试。
    private TerminalTurn putBack(SlotSnapshot cursor, AbstractContainerMenu raw) {
        MenuConfirmation confirmation = (context, ignored) -> menuVerdict(NetworkTakeConfirmation.putBack(cursor,
                SlotSnapshot.of(session.rawPage(context).getCarried())).verdict());
        Optional<PendingMenuAction> sent = session.menu().submitModAction("把光标上的东西放回 ME 网络",
                () -> session.compat().putBack(raw), confirmation, TAKE_TIMEOUT_TICKS);
        if (sent.isEmpty()) return new TerminalTurn.Stay(ActionStatus.running());
        putBackPending = sent.get();
        return new TerminalTurn.Stay(ActionStatus.progressed());
    }

    // 等放回网络那一下的结论：没能确认就记下，去关界面；放回了一部分（网络满了）下一刻再看光标。
    private TerminalTurn awaitPutBack(PlayerContext player) {
        putBackPending = player.menuActions().poll(player, putBackPending);
        if (!putBackPending.terminal()) return new TerminalTurn.Stay(ActionStatus.running());
        PendingMenuAction settled = putBackPending;
        putBackPending = null;
        if (settled.status() != PendingMenuAction.Status.CONFIRMED_APPLIED) {
            session.settlement().unconfirmed("把光标上的东西放回 " + session.access().describe()
                    + " 的网络没能确认：" + settled.detail());
            return new TerminalTurn.CloseOnly();
        }
        return new TerminalTurn.Stay(ActionStatus.progressed());
    }

    // 存货表还在变（还有包没到）就接着等；连着几刻没变或等到期限就算齐了。
    private boolean stockStillArriving(int size) {
        if (++stockFullWaited > STOCK_FULL_WAIT_TICKS) return false;
        if (size != lastStockSize) {
            lastStockSize = size;
            stockStableTicks = 0;
            return true;
        }
        return ++stockStableTicks < STOCK_STABLE_TICKS;
    }

    /** 这一刻有没有停不得的一下：取货、放下、放回网络还在等游戏确认时停不得，光标上可能拿着东西。 */
    boolean unsafeToStop() {
        return pending != null || putDownPending || putBackPending != null;
    }

    // 等成品时被生存需求打断过，回来重开终端：打断前还没确认的那一下如实记下、不再等它，搬运的一笔撒手。
    void afterPause() {
        if (pending != null) {
            session.settlement().unconfirmed("从 " + session.access().describe() + " 取货的一下没等到确认就被打断了");
            pending = null;
        }
        if (session.transport() != null) session.transport().abandon();
        putDownPending = false;
    }

    // 重开终端后存货表稳定性的计数清零，重新按"连着几刻不变才算齐"等。
    void resetStockCounters() {
        lastStockSize = -1;
        stockStableTicks = 0;
        stockFullWaited = 0;
    }

    // 网络存货里算想要的头一条：整组取、按存量从多到少，都由取货计划排好。
    private Ae2TerminalMenu.StockEntry firstWanted(List<Ae2TerminalMenu.StockEntry> stock) {
        List<Ae2TerminalMenu.StockEntry> wanted = NetworkTakePlan.matching(stock, session::wanted);
        return wanted.isEmpty() ? null : wanted.getFirst();
    }

    // 光标上已经有一些：接着取同一条；网络里那条没了也要能把光标上的放下，就按光标的样子补一条存量为零的。
    private Ae2TerminalMenu.StockEntry entryFor(SlotSnapshot cursor, List<Ae2TerminalMenu.StockEntry> stock) {
        for (Ae2TerminalMenu.StockEntry entry : stock) {
            if (SlotSnapshot.of(entry.sample()).sameIdentity(SlotSnapshot.of(cursor.stack().copyWithCount(1)))) {
                return entry;
            }
        }
        return new Ae2TerminalMenu.StockEntry(-1, cursor.stack(), 0, false);
    }

    // 只有一份读数、拿不到原界面：放光标、装不装得下的判断走统一的收进背包挑选；
    // 背包格号用读数里的权威格号，不按界面加槽顺序猜主背包在前。
    private static int stowSlot(ItemStack stack, MenuContent.Reading reading) {
        return CursorStowing.fromSnapshots(reading.playerSlotIds(), reading.playerInventoryIndices(),
                reading.playerSnapshots().stream().map(SlotSnapshot::stack).toList(), stack);
    }

    // 界面里这些槽位此刻的样子，按给的顺序。
    private static List<SlotSnapshot> snapshots(AbstractContainerMenu menu, List<Integer> slotIds) {
        List<SlotSnapshot> snapshots = new ArrayList<>(slotIds.size());
        for (int slotId : slotIds) {
            snapshots.add(slotId >= 0 && slotId < menu.slots.size()
                    ? SlotSnapshot.of(menu.getSlot(slotId).getItem()) : SlotSnapshot.empty());
        }
        return snapshots;
    }

    private static MenuConfirmation.Verdict menuVerdict(MoveConfirmation.Verdict verdict) {
        return switch (verdict) {
            case CONFIRMED -> MenuConfirmation.Verdict.APPLIED;
            case WAITING -> MenuConfirmation.Verdict.PENDING;
            case DIVERGED -> MenuConfirmation.Verdict.DIVERGED;
        };
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
