// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.StepwiseActions;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuTakeRound;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 从终端取货的生产实现：走到 ME 终端跟前、点在面板上打开 → 等网络读数同步 → 逐笔取进背包 → 关上；
 * 现货不够、网络里有样板时在终端里下单补缺口，开工后重新打开终端站在跟前等成品进网络。
 *
 * <p>打开与核对走 {@link TerminalSync}（走到、点开、核对是这台、等网络读数同步），核对过了就建一轮
 * 共用的取货一轮，这里只给差异点：这一刻下一笔怎么出怎么发、下单插页与重开的交接、收场前把光标放好
 * （放不进背包放回网络）、关箱前记下网络存货，都在 {@link TakeMoves} 与会话里。
 * 拿走多少最终以引擎在动作结束后按身上的重新清点为准。收场时光标上还有东西，先放回背包
 * （背包放不下就放回网络）再关界面，不留给关界面的流程去还——背包满了它会把东西丢在地上。
 * 下单没成或等不到成品：就算已经取到一些也按失败交回，让引擎划掉这条路，不再回来白跑。
 */
public final class MenuTerminalTakes implements TerminalTakes {

    private final Ae2Compat compat;
    private final PlayerServices services;
    /** 认终端、到终端跟前：和存东西共用一套认法。 */
    private final NearbyTerminals terminals;

    public MenuTerminalTakes(Ae2Compat compat, PlayerServices services, SeenNetworkStock seen,
            Supplier<Instant> clock) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.terminals = new NearbyTerminals(compat, services, seen, clock);
    }

    @Override public Optional<Action> take(Ae2Terminals.Terminal terminal, String dimension, ItemRequest request,
            Permissions permissions, TaskRecords records) {
        return Optional.of(new TakeAction(terminals.placed(terminal, dimension, permissions), request, false, records,
                true));
    }

    @Override public Optional<Action> takeWireless(String terminalItemId, ItemRequest request, TaskRecords records) {
        return Optional.of(new TakeAction(terminals.wireless(terminalItemId), request, false, records, true));
    }

    // 先在访问点跟前把终端绑上，再拿出它取货：绑定做成了接着取，做砸了整串按绑定那一步的原因失败。
    @Override public Optional<Action> pairAndTake(Ae2Terminals.AccessPoint point, String terminalItemId,
            ItemRequest request, Permissions permissions, TaskRecords records) {
        WirelessBinding binding = new WirelessBinding(compat, services, point, terminalItemId, permissions);
        TakeAction take = new TakeAction(terminals.wireless(terminalItemId), request, false, records, true);
        return Optional.of(new StepwiseActions(
                "先把身上的" + terminalItemId + "绑到 " + point.describe() + "，再从 ME 网络取"
                        + request.wanted().describe(),
                binding, take));
    }

    @Override public Optional<Action> takeWirelessNow(String terminalItemId, ItemRequest request) {
        return Optional.of(new TakeAction(terminals.wireless(terminalItemId), request, false, null, false));
    }

    @Override public Optional<Action> lookWireless(String terminalItemId, ItemRequest request) {
        return Optional.of(new TakeAction(terminals.wireless(terminalItemId), request, true, null, false));
    }

    /** 从终端取货的动作：点开并等同步 → 一轮取货（逐笔取、下单插页、放好光标、关上都在里面），跨刻推进。 */
    private final class TakeAction implements Action {

        /** 一次取货的总期限（刻）：走过去加上最多六十几下单件取，一分钟足够；到点如实收场，不在一台终端上耗死。 */
        private static final long GIVE_UP_TICKS = 20L * 60;

        /** 三段共用的会话上下文：此刻开着的界面、搬运执行、搬运结算与「要什么」。 */
        private final TakeSession session;
        /** 「打开与核对」：走到、点开、等网络读数同步；重开后再来一遍。 */
        private final TerminalSync sync;
        /** 「下单等成品」：现货不够在终端里下单，站在跟前等成品进网络。 */
        private final WaitsForCraft craft;
        /** 终端取货的差异点：下一笔怎么出怎么发、收场前放光标。 */
        private final TakeMoves moves;
        /** 界面核对过之后的一轮取货：逐笔、记账、放光标、关界面都走它；重开交接后换新一轮。 */
        private MenuTakeRound round;
        /** 重开前的没能确认记账，跟着会话走，别跟着被丢掉的一轮丢掉。 */
        private final List<String> carriedNotes = new ArrayList<>();
        private long waited;
        /** 站在终端前等成品时被生存需求打断过：回来先重新打开终端接着等，不当成取完了。 */
        private boolean reopenAfterPause;
        /** 交接重开做完了：外层丢掉旧的一轮，重新走打开与核对。 */
        private boolean restarted;

        /**
         * @param lookOnly 只看一眼，什么都不拿
         * @param records  发起任务的记账口，下单的结论记成 attempts；没有为 null
         */
        TakeAction(TerminalAccess access, ItemRequest request, boolean lookOnly, TaskRecords records,
                boolean allowCrafting) {
            this.session = new TakeSession(access, compat, services, request);
            this.sync = TerminalSync.waitingForStock(session);
            this.craft = new WaitsForCraft(compat, session, records);
            this.moves = new TakeMoves(session, craft, lookOnly, allowCrafting);
        }

        @Override public ActionStatus tick(TickContext tick) {
            if (reopenAfterPause) return resumeAfterPause();
            // 下了单（或在等之前下的单）时，下单那几页与等成品另有期限，总期限跟着放宽。
            long limit = craft.involved()
                    ? GIVE_UP_TICKS + CraftOrdering.MOST_TICKS + CraftWait.TOTAL_TICKS : GIVE_UP_TICKS;
            if (++waited > limit) return checkTimeout(tick);
            if (round == null) return openSegment(tick);
            return tickRound(tick);
        }

        // 总期限到了：还没在收场路上的就地收场（不再盘下一笔，照常放光标、关界面、出结论）；
        // 界面还没点开的直接按超时收场；已经在收场路上的照原样走完。
        private ActionStatus checkTimeout(TickContext tick) {
            Problem timeout = Problem.of(Problem.Kind.STUCK,
                    "从 " + session.access().describe() + " 取货超时", null);
            if (round != null && !round.atClosing()) {
                moves.stashReason(timeout);
                return round.wrapUp("总期限到了");
            }
            if (round == null && session.menu() != null) {
                moves.failedAtOpen(timeout);
                round = newRound();
                return ActionStatus.progressed();
            }
            if (round == null) return result();
            return tickRound(tick);
        }

        // 打开与核对：走到、点开、核对是这台、等网络读数同步；核对过了建一轮取货交给流程。
        // 核对里发现开错了、没连上、存货一直没同步：照常收场（记存货、放光标、关界面）。
        private ActionStatus openSegment(TickContext tick) {
            TerminalTurn turn = sync.proceed(tick);
            if (turn instanceof TerminalTurn.Stay(ActionStatus status)) return status;
            if (turn instanceof TerminalTurn.Synced) {
                round = newRound();
                return ActionStatus.progressed();
            }
            if (turn instanceof TerminalTurn.WrapUp(Problem reason, boolean evenIfTaken)) {
                // 就算已经取到一些也按失败交回的口径留给下单那边；打开与核对阶段的失败照常收场。
                moves.failedAtOpen(reason);
                round = newRound();
                return ActionStatus.progressed();
            }
            if (turn instanceof TerminalTurn.FinishNow(Problem reason)) {
                // 界面刚打开就被关上了：不用放光标、不用关，直接收场。
                moves.stashReason(reason);
                return result();
            }
            // 打开与核对不会走到下单与重开；照常推进。
            return ActionStatus.progressed();
        }

        // 一轮取货推进一刻：终态前先问一句是不是交接重开了——是就丢掉旧的一轮，重新走打开与核对，不算取完。
        private ActionStatus tickRound(TickContext tick) {
            ActionStatus status = round.tick(tick, session.reading());
            if (status instanceof ActionStatus.Running) return status;
            if (restarted) {
                carriedNotes.addAll(round.unconfirmedFacts());
                round = null;
                restarted = false;
                return ActionStatus.progressed();
            }
            return status;
        }

        private MenuTakeRound newRound() {
            return new MenuTakeRound(session.menu(), List.of(new TakeWant()), new RoundEndings());
        }

        // 关掉此刻的终端重新打开：下单开工了（或等成品被打断回来），等读数同步后建新一轮接着取，账跟着会话走。
        private void reopen() {
            session.reopen();
            sync.resetCounters();
            moves.resetCounters();
        }

        // 等成品时被打断过（饿了去吃、天黑去睡）：界面已经关了，作业留在网络里接着做。回来重新走到终端前打开，
        // 接着等同一单——等过的时间照算、不重复下单；打断前还没确认的那一下如实记下，不再等它。
        private ActionStatus resumeAfterPause() {
            reopenAfterPause = false;
            if (round != null) carriedNotes.addAll(round.unconfirmedFacts());
            round = null;
            reopen();
            return ActionStatus.progressed();
        }

        // 收场：取到了就算做完（数量由引擎清点）；一件都没取到就按收场原因失败，让引擎换别的路。
        // 下单没成、等不到成品的，取到了也按失败交回（引擎照样按身上清点记下取到的）。
        private ActionStatus result() {
            Problem reason = moves.wrapReason();
            if (reason == null || (session.confirmedTotal() > 0 && !moves.failEvenIfTaken())) {
                return ActionStatus.done();
            }
            List<String> facts = new ArrayList<>(carriedNotes);
            if (round != null) facts.addAll(round.unconfirmedFacts());
            facts.addAll(moves.notes());
            String unconfirmed = facts.isEmpty() ? "" : "；没能确认的：" + String.join("；", facts);
            return ActionStatus.failed(Problem.of(reason.kind(), reason.message() + unconfirmed,
                    reason.suggestion()));
        }

        // 被生存需求打断：界面关上。站在终端前等成品时打断的，回来接着等；取现货时打断的就此收场，取到多少由引擎清点。
        @Override public void pause() {
            if (round != null && !round.done() && moves.betweenActions()) {
                reopenAfterPause = true;
                round.abandonQuietly();
            }
            session.opening().pause();
            session.abandonMenu();
        }

        // 此刻能不能被生存需求打断：一下取货、放下、放回网络还在等游戏确认时停不得，光标上可能拿着东西；
        // 站在终端前等网络做成品是两件事之间的空当，饿了吃一口、天黑去睡都能插进来，作业留在网络里接着做；其余是正常干活。
        @Override public Interruptibility interruptibility() {
            if ((round != null && round.unsafeToStop()) || moves.unsafeToStop()) {
                return Interruptibility.UNSAFE_TO_STOP;
            }
            return round != null && moves.betweenActions() ? Interruptibility.BETWEEN_ACTIONS : Interruptibility.WORKING;
        }

        // 收尾：打开动作一并收尾；一轮还没收场的，先撒手（点出去的那几下交回没能确认）再请游戏关上界面。
        @Override public void close() {
            session.opening().close();
            if (round != null && !round.done()) round.close(true);
        }

        @Override public String describe() {
            String doing = "从 " + session.access().describe() + " 取" + session.request().wanted().describe();
            return craft.waiting() ? doing + "，" + craft.waitingText() : doing;
        }

        /** 要货单里的一样：整个会话就要这一样，缺几件按身上确认过的算，下一笔的盘法与发法在逐笔搬里。 */
        private final class TakeWant implements MenuTakeRound.Want {

            @Override public String label() {
                return "从 " + session.access().describe() + " 取" + session.request().wanted().describe();
            }

            @Override public Predicate<SlotSnapshot> matcher() {
                return snapshot -> !snapshot.isEmpty() && session.wanted(snapshot.stack());
            }

            // 缺几件按身上确认过的算：每笔都等游戏确认过才记账，与读数当刻对不对得上无关。
            @Override public int stillNeeded(MenuContent.Reading reading) {
                return session.request().count() - session.confirmedTotal();
            }

            @Override public MenuTakeRound.Step next(MenuContent.Reading reading, int needed, TickContext tick) {
                return moves.next(reading, tick);
            }

            // 对不上账的一笔之后不再接着盘：搬走多少说不清，交给引擎按身上的清点。
            @Override public boolean settleAfterDiverge() {
                return true;
            }
        }

        /** 一轮取货的收场：关界面前记下网络存货、放好光标；下单开工了就不关界面，交接给外层重开。 */
        private final class RoundEndings implements MenuTakeRound.Endings {

            // 关箱前把这台终端此刻的网络存货记下：下次报价用。界面已经没了时这里拿不到读数，记不下就算了。
            @Override public void rememberBeforeClose(Optional<MenuContent.Reading> lastReading) {
                session.rememberStockNow();
            }

            // 关界面前放好光标：放进背包，装不下放回网络。
            @Override public ActionStatus beforeClose(TickContext tick, MenuContent.Reading reading) {
                return moves.beforeClose(tick);
            }

            // 下单开工了要关掉重开再等成品：交接（关掉此刻的界面、计数清零）在这里做好，这一轮不记不关直接交回。
            @Override public boolean finishWithoutClosing() {
                if (!moves.reopenRequested()) return false;
                reopen();
                restarted = true;
                return true;
            }

            @Override public ActionStatus finishAfterClosed(boolean tookAnything) {
                return result();
            }

            // 界面中途没了：搬走多少由引擎清点，照常收场。
            @Override public ActionStatus finishAfterMenuGone(boolean tookAnything) {
                return result();
            }
        }
    }
}
