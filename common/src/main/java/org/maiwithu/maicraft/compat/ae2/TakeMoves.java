// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.maiwithu.maicraft.behavior.menu.MenuClick;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuTakeRound;
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
 * 终端取货的差异点，长在取货一轮的接缝上：这一刻下一笔怎么出、怎么发，收场前放光标怎么放。
 * 缺一组以上整组取（经模组协议发、等游戏确认），不足一组一件一件取到光标上再放下；
 * 收场时把光标上的东西放回背包（背包放不下放回网络）。每一笔都等游戏确认：整组取看背包里多了几件，
 * 取一件看光标上多了一件，放下看光标空了、那一格多了；只记确认过的量，没动静就不再硬点，对不上的如实记下不凑数。
 * 现货取完还缺交给「下单等成品」的一段，开工后由外层重开终端再取；要的是一桶流体而网络里存的是流体时，
 * 像玩家拿空桶去装。收场语义（没能确认的记账、放光标、关界面、最后结论）都走取货一轮一处，这里不出自己的结论。
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

    private final TakeSession session;
    /** 现货不够时问它下不下单、等不等成品。 */
    private final WaitsForCraft craft;
    /** 只看一眼：等存货同步齐记下来就收场，什么都不拿。 */
    private final boolean lookOnly;
    /** 网络里现成没有时允不允许下单合成；坠落中取落地料不允许，等成品时人已经落地了。 */
    private final boolean allowCrafting;

    // 收场的原因与口径：盘货或一笔的执行发现要就此收场时记下，最后的结论由外层照它算。
    private Problem wrapReason;
    private boolean failEvenIfTaken;
    // 打开与核对那段发现要照常收场（开错了、没连上、存货一直没同步、超时）：一轮的第一刻就放下，走收场。
    private boolean openFailed;
    // 下单那边要重开终端（开工了看成品）：一轮照常收场但不关界面，交接给外层重开再取。
    private boolean reopenRequested;
    // 正走在下单那几页上：判断"两件事之间的空当"时要把这段摘出去。
    private boolean drivingCraft;
    private boolean onCraftPages;

    // 没看到要的东西时，存货表上一刻有几条、连着几刻没变、一共等了几刻。
    private int lastStockSize = -1;
    private int stockStableTicks;
    private int stockFullWaited;

    // 为装流体把背包里的空桶放进网络了，等它在存货里出现。
    private boolean bucketOffered;
    private int bucketWaited;

    // 收场前放好光标：正在点的一笔、已经等了几刻、正在等确认的"放回网络"那一下。
    private MenuTakeRound.MoveExecution clearingStroke;
    private int cursorWaited;
    private PendingMenuAction putBackPending;
    // 放回网络之类没能确认的交代：收场路上不在一笔的账里，随最后的结论一起交回。
    private final List<String> notes = new ArrayList<>();

    TakeMoves(TakeSession session, WaitsForCraft craft, boolean lookOnly, boolean allowCrafting) {
        this.session = session;
        this.craft = craft;
        this.lookOnly = lookOnly;
        this.allowCrafting = allowCrafting;
    }

    /** 这一轮收场时照它算的原因；没要失败过的为 null。 */
    Problem wrapReason() {
        return wrapReason;
    }

    /** 就算已经取到一些也按失败交回（下单没成、等不到成品）：引擎要划掉这条路换别的。 */
    boolean failEvenIfTaken() {
        return failEvenIfTaken;
    }

    /** 收场路上没能确认的交代，随最后的结论交回。 */
    List<String> notes() {
        return List.copyOf(notes);
    }

    /** 要重开终端（下单开工了）：一轮不关界面直接交回，外层重开后再建新一轮接着取。 */
    boolean reopenRequested() {
        return reopenRequested;
    }

    /** 正站在终端前等网络做成品（不在下单那几页上）：这是两件事之间的空当，饿了吃一口、天黑去睡都能插进来。 */
    boolean betweenActions() {
        return craft.waiting() && !onCraftPages;
    }

    /** 收场前放光标那段有没有停不得的一下：放回网络还在等游戏确认时光标上可能拿着东西。 */
    boolean unsafeToStop() {
        return putBackPending != null || (clearingStroke != null && clearingStroke.unsafeToStop());
    }

    // 记下要照常收场的原因：只记第一条，后面的缘由不顶掉前面的。
    private void wrapUp(Problem reason, boolean evenIfTaken) {
        if (reason != null && wrapReason == null) wrapReason = reason;
        failEvenIfTaken |= evenIfTaken;
    }

    // 还没盘货就照常收场：原因记下，一轮的第一刻放下，走记存货、放光标、关界面的收场。
    void failedAtOpen(Problem reason) {
        wrapUp(reason, false);
        openFailed = true;
    }

    // 外层把收场的原因记进来（超时、界面刚开就被关上），再照常走收场或直接出结论。
    void stashReason(Problem reason) {
        wrapUp(reason, false);
    }

    /** 取货一轮的下一步：盘网络存货出下一笔，或交给下单等成品的一段，或就此收场。 */
    MenuTakeRound.Step next(MenuContent.Reading reading, TickContext tick) {
        if (openFailed || reopenRequested) return MenuTakeRound.Step.giveUp();
        if (drivingCraft) return craftTurn(craft.proceed(tick));
        if (session.menu().busy()) return new MenuTakeRound.Step.Wait(ActionStatus.running());
        PlayerContext player = tick.player();
        AbstractContainerMenu raw = session.rawPage(player);
        List<Ae2TerminalMenu.StockEntry> stock = session.compat().networkStock(raw).orElse(List.of());
        // 只看一眼：等存货表稳下来、记成看全了就收场去关界面，什么都不拿。
        if (lookOnly) {
            if (stockStillArriving(stock.size())) return new MenuTakeRound.Step.Wait(ActionStatus.running());
            session.compat().networkLinked(raw).ifPresent(linked -> session.access().remember(linked, stock, true));
            return MenuTakeRound.Step.giveUp();
        }
        SlotSnapshot cursor = SlotSnapshot.of(raw.getCarried());
        // 光标上挂着不是这次要取的东西：多半是有人动过光标，不是程序出错。不在上面叠，收场时由关界面放回背包。
        if (!cursor.isEmpty() && !session.wanted(cursor.stack())) {
            wrapUp(Problem.of(Problem.Kind.STUCK, "终端界面的光标上挂着 " + cursor.count()
                    + " 个" + itemId(cursor.stack()) + "，不是这次要取的，先停手", null), false);
            return MenuTakeRound.Step.giveUp();
        }
        Ae2TerminalMenu.StockEntry entry = cursor.isEmpty() ? firstWanted(stock) : entryFor(cursor, stock);
        if (entry == null) {
            int confirmed = session.confirmedTotal();
            int remaining = session.request().count() - confirmed;
            // 成品刚好取够、网络里也没了：拿够就收场，不再等下单的那一单。
            if (remaining <= 0) return MenuTakeRound.Step.giveUp();
            // 一件还没取到就没看见：可能是存货还没同步齐，等表连着几刻不再变多再说没有。
            if (confirmed == 0 && stockStillArriving(stock.size())) return new MenuTakeRound.Step.Wait(ActionStatus.running());
            // 等到存货表不再变了：这时记下的才算看全了，下次报价可以照它说"没有"。
            if (confirmed == 0) {
                session.compat().networkLinked(raw)
                        .ifPresent(linked -> session.access().remember(linked, stock, true));
            }
            // 坠落中取落地料不在空中下单：等网络把东西做出来时人已经落地了，现成没有就收场。
            if (!allowCrafting) {
                wrapUp(session.nothingLeft(), false);
                return MenuTakeRound.Step.giveUp();
            }
            // 现货取完了还缺（或一开始就没有）：网络里那一条有样板就像玩家一样下单补缺口，件数封顶在背包装得下的；
            // 自己之前下的这样东西还在做就不再下单、接着等；下过单就在跟前等成品进网络，进来一批取一批。
            List<SlotSnapshot> playerSide = reading.playerSnapshots();
            return craftTurn(craft.onNoStock(raw, stock, confirmed, remaining,
                    sample -> NetworkTakePlan.room(sample, playerSide)));
        }
        int remaining = session.request().count() - session.confirmedTotal();
        if (entry.fillsBucket() && cursor.isEmpty()) {
            return fillBucket(entry, stock, reading, raw, remaining);
        }
        NetworkTakePlan.Move move = NetworkTakePlan.next(remaining, cursor, entry,
                reading.playerSlotIds(), reading.playerInventoryIndices(), reading.playerSnapshots());
        return switch (move) {
            case NetworkTakePlan.TakeStack takeStack -> new MenuTakeRound.Step.Do(new ProtocolTake(
                    "从 ME 终端整组取 " + itemId(entry.sample()), stackAmount(entry), entry.sample(),
                    () -> session.compat().takeStack(raw, takeStack.serial()), true));
            case NetworkTakePlan.TakeOne takeOne -> new MenuTakeRound.Step.Do(new ProtocolTake(
                    "从 ME 终端取一件 " + itemId(entry.sample()), 1, entry.sample(),
                    () -> session.compat().takeOne(raw, takeOne.serial()), false));
            case NetworkTakePlan.PutDown putDown -> new MenuTakeRound.Step.Do(new PutDown(putDown.slotId(), cursor, false));
            case NetworkTakePlan.NoRoom noRoom -> {
                wrapUp(Problem.of(Problem.Kind.INVENTORY_FULL,
                        "背包放不下从 " + session.access().describe() + " 取的" + session.request().wanted().describe(),
                        null), false);
                yield MenuTakeRound.Step.giveUp();
            }
            case NetworkTakePlan.Done done -> {
                if (remaining > 0) wrapUp(session.nothingLeft(), false);
                yield MenuTakeRound.Step.giveUp();
            }
        };
    }

    // 下单那边的决定换成这一刻的下一步：去下单的下一刻走下单那几页，等的把状态原样交回，要重开的记下等收场交接。
    private MenuTakeRound.Step craftTurn(TerminalTurn turn) {
        if (turn instanceof TerminalTurn.Order) {
            drivingCraft = true;
            onCraftPages = true;
            return new MenuTakeRound.Step.Wait(ActionStatus.progressed());
        }
        if (turn instanceof TerminalTurn.Stay(ActionStatus status)) return new MenuTakeRound.Step.Wait(status);
        if (turn instanceof TerminalTurn.Reopen) {
            reopenRequested = true;
            return MenuTakeRound.Step.giveUp();
        }
        if (turn instanceof TerminalTurn.WrapUp(Problem reason, boolean evenIfTaken)) {
            wrapUp(reason, evenIfTaken);
            return MenuTakeRound.Step.giveUp();
        }
        return new MenuTakeRound.Step.Wait(ActionStatus.running());
    }

    // 要的是一桶水（岩浆）而网络里存的是流体：像玩家拿空桶点终端里的水。网络里有空桶就直接点，
    // AE 拿一只装满放进背包；没有就先把背包里的空桶放进网络再点；两边都没有空桶如实说。
    private MenuTakeRound.Step fillBucket(Ae2TerminalMenu.StockEntry entry, List<Ae2TerminalMenu.StockEntry> stock,
            MenuContent.Reading reading, AbstractContainerMenu raw, int remaining) {
        if (remaining <= 0) return MenuTakeRound.Step.giveUp();
        boolean networkHasBucket = stock.stream()
                .anyMatch(other -> !other.fillsBucket() && other.stored() > 0 && other.sample().is(Items.BUCKET));
        if (networkHasBucket) {
            if (stowSlot(entry.sample().copyWithCount(1), reading) < 0) {
                wrapUp(Problem.of(Problem.Kind.INVENTORY_FULL,
                        "背包放不下从 " + session.access().describe() + " 装的" + session.request().wanted().describe(),
                        null), false);
                return MenuTakeRound.Step.giveUp();
            }
            return new MenuTakeRound.Step.Do(new ProtocolTake("从 ME 终端装一桶 " + itemId(entry.sample()), 1, entry.sample(),
                    () -> session.compat().fillBucket(raw, entry.serial()), true));
        }
        // 背包里的空桶放进网络：放过一次还没在存货里出现就等一会儿，等不到如实说。
        if (bucketOffered) {
            if (++bucketWaited <= BUCKET_WAIT_TICKS) return new MenuTakeRound.Step.Wait(ActionStatus.running());
            wrapUp(Problem.of(Problem.Kind.STUCK,
                    "把空桶放进 " + session.access().describe() + " 的网络后一直没看到", null), false);
            return MenuTakeRound.Step.giveUp();
        }
        for (int i = 0; i < reading.playerSnapshots().size(); i++) {
            SlotSnapshot slot = reading.playerSnapshots().get(i);
            if (slot.isEmpty() || !slot.stack().is(Items.BUCKET)) continue;
            if (!session.menu().quickMove(reading.playerSlotIds().get(i))) {
                return new MenuTakeRound.Step.Wait(ActionStatus.running());
            }
            bucketOffered = true;
            return new MenuTakeRound.Step.Wait(ActionStatus.progressed());
        }
        wrapUp(Problem.of(Problem.Kind.NEED_ITEM, session.access().describe() + " 的网络里有"
                + itemId(entry.sample()) + "要装的流体，可网络里和背包里都没有空桶",
                "先备一只空桶（三块铁锭）再来装"), false);
        return MenuTakeRound.Step.giveUp();
    }

    // 收场前把光标上的东西放回背包，再关界面：放进背包里能整个装下的一格，背包放不下就放回网络。
    // 到期限还没放好（例如一直点不出去）就照常关界面，光标上的东西交给游戏的关闭流程。
    ActionStatus beforeClose(TickContext tick) {
        if (putBackPending != null) return awaitPutBack(tick.player());
        if (clearingStroke != null) {
            return clearingStroke.tick(tick, result -> {
                clearingStroke = null;
                // 放好或没能确认都照常去关，不再折腾；界面没了流程照界面没了收场。
                return ActionStatus.done();
            });
        }
        AbstractContainerMenu raw = session.rawPage(tick.player());
        Optional<MenuContent.Reading> reading = session.reading();
        SlotSnapshot cursor = SlotSnapshot.of(raw.getCarried());
        if (cursor.isEmpty() || ++cursorWaited > CURSOR_TICKS) return ActionStatus.done();
        if (session.menu().busy()) return ActionStatus.running();
        int slot = stowSlot(cursor.stack().copyWithCount(cursor.count()), reading.get());
        if (slot >= 0) {
            clearingStroke = new PutDown(slot, cursor, true);
            return clearingStroke.tick(tick, result -> {
                clearingStroke = null;
                return ActionStatus.done();
            });
        }
        return putBack(cursor, raw);
    }

    // 背包里没有一格装得下：在终端的网络格子空白处点一下，把光标上的东西放回网络；本刻发不出去就下一刻再试。
    private ActionStatus putBack(SlotSnapshot cursor, AbstractContainerMenu raw) {
        MenuConfirmation confirmation = (context, ignored) -> switch (
                NetworkTakeConfirmation.putBack(cursor, SlotSnapshot.of(session.rawPage(context).getCarried()))
                        .verdict()) {
            case CONFIRMED -> MenuConfirmation.Verdict.APPLIED;
            case WAITING -> MenuConfirmation.Verdict.PENDING;
            case DIVERGED -> MenuConfirmation.Verdict.DIVERGED;
        };
        Optional<PendingMenuAction> sent = session.menu().submitModAction("把光标上的东西放回 ME 网络",
                () -> session.compat().putBack(raw), confirmation, TAKE_TIMEOUT_TICKS);
        if (sent.isEmpty()) return ActionStatus.running();
        putBackPending = sent.get();
        return ActionStatus.running();
    }

    // 等放回网络那一下的结论：没能确认就记下，照常去关界面；放回了一部分（网络满了）下一刻再看光标。
    private ActionStatus awaitPutBack(PlayerContext player) {
        putBackPending = player.menuActions().poll(player, putBackPending);
        if (!putBackPending.terminal()) return ActionStatus.running();
        PendingMenuAction done = putBackPending;
        putBackPending = null;
        if (done.status() != PendingMenuAction.Status.CONFIRMED_APPLIED) {
            notes.add("把光标上的东西放回 " + session.access().describe() + " 的网络没能确认：" + done.detail());
        }
        return ActionStatus.done();
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

    // 重开终端后这一段的计数清零，重新按"连着几刻不变才算齐"等存货，下一刻回到盘货。
    void resetCounters() {
        lastStockSize = -1;
        stockStableTicks = 0;
        stockFullWaited = 0;
        drivingCraft = false;
        onCraftPages = false;
        reopenRequested = false;
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

    // 整组取按计划搬几件：网络里有几件算几件，最多一组。
    private static int stackAmount(Ae2TerminalMenu.StockEntry entry) {
        return (int) Math.min(entry.stored(), entry.sample().getMaxStackSize());
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

    /** 经模组协议发的一笔取货（整组取、取一件、装一桶）：发一下、等游戏确认，按网络取货的核对式确认。 */
    private final class ProtocolTake implements MenuTakeRound.MoveExecution {

        private final String what;
        private final int amount;
        private final SlotSnapshot sample;
        private final Runnable send;
        /** 整组取、装一桶按背包侧多出来的确认；取一件按光标上多出来的。 */
        private final boolean byStack;
        private PendingMenuAction pending;
        // 动手前冻结的证据：第一下真发出去那一刻抄下来的背包侧样子，结论按它与当刻的差算。
        private List<SlotSnapshot> evidenceBefore;
        private List<Integer> evidenceSlotIds;

        ProtocolTake(String what, int amount, ItemStack sample, Runnable send, boolean byStack) {
            this.what = what;
            this.amount = amount;
            this.sample = SlotSnapshot.of(sample);
            this.send = send;
            this.byStack = byStack;
        }

        @Override public int plannedAmount() { return amount; }

        // 发一下取货：本刻发不出去（上一下没结清、界面没画好、没有交互机会）就下一刻再试。
        @Override public ActionStatus tick(TickContext tick,
                Function<TransferRun.MoveResult, ActionStatus> settled) {
            // 界面没了：交给流程照界面没了收场；点出去没等到结果的如实说。
            if (session.reading().isEmpty()) {
                int submitted = pending != null ? 1 : 0;
                pending = null;
                return settled.apply(new TransferRun.MoveResult.MenuGone(submitted));
            }
            if (pending == null) {
                submit(tick.player());
                return pending == null ? ActionStatus.running() : ActionStatus.progressed();
            }
            return awaitTake(tick.player(), settled);
        }

        private void submit(PlayerContext player) {
            MenuContent.Reading reading = session.reading().orElseThrow();
            AbstractContainerMenu raw = session.rawPage(player);
            List<Integer> slotIds = reading.playerSlotIds();
            List<SlotSnapshot> before = reading.playerSnapshots();
            SlotSnapshot cursorBefore = SlotSnapshot.of(raw.getCarried());
            MenuConfirmation confirmation = (context, ignored) -> {
                AbstractContainerMenu now = session.rawPage(context);
                NetworkTakeConfirmation.Result result = byStack
                        ? NetworkTakeConfirmation.stackTaken(sample, before, snapshots(now, slotIds),
                                SlotSnapshot.of(now.getCarried()))
                        : NetworkTakeConfirmation.oneTaken(sample, cursorBefore, SlotSnapshot.of(now.getCarried()));
                return menuVerdict(result.verdict());
            };
            session.menu().submitModAction(what, send, confirmation, TAKE_TIMEOUT_TICKS)
                    .ifPresent(action -> {
                        pending = action;
                        evidenceSlotIds = slotIds;
                        evidenceBefore = before;
                    });
        }

        // 等那一下取货的结论：整组取确认了就按背包侧多出来的记账；取一件的东西在光标上，放下那一笔再记；
        // 点了没动静（背包放不下、网络断了、刚被别人取走）不再硬点，等不到结论的如实收场。
        private ActionStatus awaitTake(PlayerContext player,
                Function<TransferRun.MoveResult, ActionStatus> settled) {
            pending = player.menuActions().poll(player, pending);
            if (!pending.terminal()) return ActionStatus.running();
            PendingMenuAction done = pending;
            pending = null;
            switch (done.status()) {
                case CONFIRMED_APPLIED -> {
                    if (byStack) {
                        AbstractContainerMenu now = session.rawPage(player);
                        NetworkTakeConfirmation.Result result = NetworkTakeConfirmation.stackTaken(sample,
                                evidenceBefore, snapshots(now, evidenceSlotIds), SlotSnapshot.of(now.getCarried()));
                        if (result.amount() > 0) {
                            session.settlement().confirm(itemId(sample.stack()), result.amount());
                        }
                        return settled.apply(new TransferRun.MoveResult.Confirmed(result.amount()));
                    }
                    return settled.apply(new TransferRun.MoveResult.Confirmed(0));
                }
                case CONFIRMED_NOT_APPLIED -> {
                    wrapUp(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                            "在 " + session.access().describe() + " 取" + itemId(sample.stack())
                                    + "没取到（背包放不下、网络断了或刚被别人取走）", null), false);
                    return settled.apply(TransferRun.MoveResult.NotMoved.NOT_MOVED);
                }
                default -> {
                    wrapUp(Problem.of(Problem.Kind.STUCK,
                            "从 " + session.access().describe() + " 取" + itemId(sample.stack()) + "的一下没能确认", null), false);
                    return settled.apply(TransferRun.MoveResult.Diverged.DIVERGED);
                }
            }
        }

        @Override public int abandon() {
            int submitted = pending != null ? 1 : 0;
            pending = null;
            return submitted;
        }

        @Override public boolean unsafeToStop() { return pending != null; }
    }

    /** 把光标上的东西放进背包的一笔：点目标格，按光标空了、那一格多了核对；那一格放不下就回到挑选再挑一格。 */
    private final class PutDown implements MenuTakeRound.MoveExecution {

        private final int amount;
        private final ItemStack sample;
        private final String item;
        /** 收场前放光标的那一笔：对不上账不再折腾，照常去关界面。 */
        private final boolean clearing;
        /** 要重新挑格再开笔（上一格放不下）。 */
        private boolean rechoose;

        // 点目标格开笔：动手前光标上的样子在构造时抄下，记账按它说放了几件。
        PutDown(int slotId, SlotSnapshot cursor, boolean clearing) {
            this.amount = cursor.count();
            this.clearing = clearing;
            this.sample = cursor.stack();
            this.item = itemId(cursor.stack());
            begin(slotId);
        }

        private void begin(int slotId) {
            rechoose = false;
            session.transport().begin(new TransferRun.Move(List.of(new MenuClick(slotId, false, 0, false)),
                    new TransferRun.Check.PutDown(slotId), "把光标上的东西放进背包"));
        }

        @Override public int plannedAmount() { return amount; }

        @Override public ActionStatus tick(TickContext tick,
                Function<TransferRun.MoveResult, ActionStatus> settled) {
            if (rechoose) return rechooseAndBegin(settled);
            return switch (session.transport().tick()) {
                case TransferRun.Progress.Advanced advanced -> ActionStatus.progressed();
                case TransferRun.Progress.Waiting waiting -> ActionStatus.running();
                case TransferRun.Progress.Settled done -> onSettled(done.result(), settled);
            };
        }

        // 一笔的结论：确认了按光标上的记账；对不上账说清搬走多少说不清；那一格放不下就回到挑选再挑一格，
        // 挑不出（背包真装不下了）说清缘由交给收场。
        private ActionStatus onSettled(TransferRun.MoveResult result,
                Function<TransferRun.MoveResult, ActionStatus> settled) {
            if (result instanceof TransferRun.MoveResult.Confirmed(int moved)) {
                session.settlement().confirm(item, moved);
                return settled.apply(result);
            }
            if (result instanceof TransferRun.MoveResult.Diverged) {
                if (!clearing) {
                    wrapUp(Problem.of(Problem.Kind.STUCK, "把光标上的" + item + "放进背包时两边对不上", null), false);
                }
                return settled.apply(result);
            }
            if (result instanceof TransferRun.MoveResult.MenuGone) return settled.apply(result);
            return rechooseAndBegin(settled);
        }

        // 回到挑选再挑一格：只有一份读数、按统一的收进背包挑选；挑不出就交给收场（装不下照常关界面）。
        private ActionStatus rechooseAndBegin(Function<TransferRun.MoveResult, ActionStatus> settled) {
            Optional<MenuContent.Reading> reading = session.reading();
            if (reading.isEmpty()) return settled.apply(new TransferRun.MoveResult.MenuGone(0));
            int slot = stowSlot(sample.copyWithCount(amount), reading.get());
            if (slot < 0) {
                wrapUp(Problem.of(Problem.Kind.INVENTORY_FULL,
                        "背包放不下从 " + session.access().describe() + " 取的" + session.request().wanted().describe(),
                        null), false);
                return settled.apply(TransferRun.MoveResult.NotMoved.NOT_MOVED);
            }
            begin(slot);
            return ActionStatus.progressed();
        }

        @Override public int abandon() { return session.transport().abandon(); }

        @Override public boolean unsafeToStop() { return session.transport().unsafeToStop(); }
    }
}
