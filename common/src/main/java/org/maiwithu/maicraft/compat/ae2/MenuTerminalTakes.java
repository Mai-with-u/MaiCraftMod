// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.StepwiseActions;
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
 * <p>一次取货会话拆成三段协作，各自有自己的状态与收场：
 * 「打开与核对」{@link TerminalSync}（走到、点开、核对是这台、等网络读数同步）、
 * 「逐笔搬」{@link TakeMoves}（整组取与放下光标，每一笔都等游戏确认）、
 * 「下单等成品」{@link WaitsForCraft}（在终端里下单、站在跟前等成品进网络）；
 * 三段共用的界面与记账在 {@link TakeSession}。这里只管会话的阶段推进与收场：
 * 总期限、被生存需求打断后接着等、关界面，以及取没取够的最后结论。
 *
 * <p>拿走多少最终以引擎在动作结束后按身上的重新清点为准。收场时光标上还有东西，先放回背包
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

    /** 从终端取货的会话：点开并等同步 → 逐笔取 → （要时）下单等成品 → 放好光标 → 关上，跨刻推进。 */
    private final class TakeAction implements Action {

        /** 取货会话的先后顺序；现货取完还缺、网络里有样板时，插一段下单合成，开工后重新打开终端等成品。 */
        private enum Stage { OPEN, TAKE, CRAFT, CURSOR, CLOSE, DONE }

        /** 一次取货的总期限（刻）：走过去加上最多六十几下单件取，一分钟足够；到点如实收场，不在一台终端上耗死。 */
        private static final long GIVE_UP_TICKS = 20L * 60;

        /** 三段共用的会话上下文：此刻开着的界面、搬运执行、搬运结算与「要什么」。 */
        private final TakeSession session;
        /** 「打开与核对」：走到、点开、等网络读数同步。 */
        private final TerminalSync sync;
        /** 「下单等成品」：现货不够在终端里下单，站在跟前等成品进网络。 */
        private final WaitsForCraft craft;
        /** 「逐笔搬」：整组取与放下光标。 */
        private final TakeMoves moves;

        private Stage stage = Stage.OPEN;
        private Action closing;
        private long waited;
        /** 收场的原因：一件都没取到时按它失败；取到了就算做完。null 是正常收场（拿够了）。 */
        private Problem stopReason;
        /** 下单没成或等不到成品：就算已经取到一些也按失败交回，让引擎划掉这条路，不再回来白跑。 */
        private boolean failEvenIfTaken;
        /** 站在终端前等成品时被生存需求打断过：回来先重新打开终端接着等，不当成取完了。 */
        private boolean reopenAfterPause;

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
            if (++waited > limit && stage.ordinal() < Stage.CURSOR.ordinal()) {
                stop(Problem.of(Problem.Kind.STUCK, "从 " + session.access().describe() + " 取货超时", null));
            }
            return switch (stage) {
                case OPEN -> onTurn(sync.proceed(tick), Stage.TAKE);
                case TAKE -> onTurn(moves.proceed(tick), null);
                case CRAFT -> onTurn(craft.proceed(tick), null);
                case CURSOR -> onTurn(moves.clearCursor(tick), Stage.CLOSE);
                case CLOSE -> closeMenu(tick);
                case DONE -> result();
            };
        }

        // 一段交回的走向换成会话的推进：进下一段、收场，或把动作状态原样交回。afterOpen 是打开并同步齐后去的段。
        private ActionStatus onTurn(TerminalTurn turn, Stage afterOpen) {
            if (turn instanceof TerminalTurn.Stay(ActionStatus status)) return status;
            if (turn instanceof TerminalTurn.Synced) {
                stage = afterOpen;
                return ActionStatus.progressed();
            }
            if (turn instanceof TerminalTurn.WrapUp(Problem reason, boolean evenIfTaken)) {
                failEvenIfTaken |= evenIfTaken;
                return wrapUp(reason);
            }
            if (turn instanceof TerminalTurn.FinishNow(Problem reason)) {
                // 界面已经不在了：不用放光标、不用关，直接收场。
                if (stopReason == null) stopReason = reason;
                stage = Stage.DONE;
                return result();
            }
            if (turn instanceof TerminalTurn.Order) {
                stage = Stage.CRAFT;
                return ActionStatus.progressed();
            }
            if (turn instanceof TerminalTurn.Reopen) {
                reopen();
                return ActionStatus.progressed();
            }
            // CloseOnly：不用放光标了，直接去关界面。
            stage = Stage.CLOSE;
            return ActionStatus.progressed();
        }

        // 收场：还停在下单那几页上就关掉那一页直接结束；在终端前等的照常放好光标、关界面收场。
        private ActionStatus wrapUp(Problem reason) {
            if (stage != Stage.CRAFT) return stopNow(reason);
            session.abandonMenu();
            if (stopReason == null) stopReason = reason;
            stage = Stage.DONE;
            return result();
        }

        // 开工了（或等成品时被打断回来）：关掉 AE2 退回来的终端重新打开看存货，等成品进网络。
        private void reopen() {
            session.reopen();
            sync.resetCounters();
            moves.resetStockCounters();
            stage = Stage.OPEN;
        }

        // 等成品时被打断过（饿了去吃、天黑去睡）：界面已经关了，作业留在网络里接着做。回来重新走到终端前打开，
        // 接着等同一单——等过的时间照算、不重复下单；打断前还没确认的那一下如实记下，不再等它。
        private ActionStatus resumeAfterPause() {
            reopenAfterPause = false;
            moves.afterPause();
            reopen();
            return ActionStatus.progressed();
        }

        // 照常收场：先记下网络存货，再放好光标、关界面。还没打开界面就收场（例如走过去超时）时没有界面要关，直接结束。
        private void stop(Problem reason) {
            if (stopReason == null) stopReason = reason;
            if (session.menu() == null) {
                stage = Stage.DONE;
                return;
            }
            session.rememberStockNow();
            stage = Stage.CURSOR;
        }

        private ActionStatus stopNow(Problem reason) {
            stop(reason);
            return ActionStatus.progressed();
        }

        // 关界面：打开者负责关闭。关不上不冒充没开过，照常收场让引擎清点。
        private ActionStatus closeMenu(TickContext tick) {
            if (closing == null) closing = session.menu().closing();
            ActionStatus status = closing.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            stage = Stage.DONE;
            return result();
        }

        // 收场：取到了就算做完（数量由引擎清点）；一件都没取到就按收场原因失败，让引擎换别的路。
        // 下单没成、等不到成品的，取到了也按失败交回（引擎照样按身上清点记下取到的）。
        private ActionStatus result() {
            if (stopReason == null || (session.confirmedTotal() > 0 && !failEvenIfTaken)) return ActionStatus.done();
            String unconfirmed = session.settlement().unconfirmedFacts().isEmpty() ? ""
                    : "；没能确认的：" + String.join("；", session.settlement().unconfirmedFacts());
            return ActionStatus.failed(Problem.of(stopReason.kind(), stopReason.message() + unconfirmed,
                    stopReason.suggestion()));
        }

        // 被生存需求打断：界面关上。站在终端前等成品时打断的，回来接着等；取现货时打断的就此收场，取到多少由引擎清点。
        @Override public void pause() {
            if (session.menu() != null && stage == Stage.TAKE && craft.waiting()) reopenAfterPause = true;
            session.opening().pause();
            session.abandonMenu();
        }

        // 此刻能不能被生存需求打断：一下取货、放下、放回网络还在等游戏确认时停不得，光标上可能拿着东西；
        // 站在终端前等网络做成品是两件事之间的空当，饿了吃一口、天黑去睡都能插进来，作业留在网络里接着做；其余是正常干活。
        @Override public Interruptibility interruptibility() {
            if (moves.unsafeToStop()) return Interruptibility.UNSAFE_TO_STOP;
            return stage == Stage.TAKE && craft.waiting() ? Interruptibility.BETWEEN_ACTIONS : Interruptibility.WORKING;
        }

        // 收尾：打开动作一并收尾；界面还开着就请游戏关上，光标上的东西由原版的关闭流程还回背包。
        @Override public void close() {
            session.opening().close();
            if (closing != null) closing.close();
            if (session.menu() != null && stage != Stage.DONE) session.abandonMenu();
        }

        @Override public String describe() {
            String doing = "从 " + session.access().describe() + " 取" + session.request().wanted().describe();
            return craft.waiting() ? doing + "，" + craft.waitingText() : doing;
        }
    }
}
