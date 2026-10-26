// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.inventory.spi.NetworkStorage;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 往 ME 网络里存东西的生产实现：挑一台终端（在身边时身上的无线终端优先，没带或点了地方就找装着的）→
 * 拿出无线终端右键打开，或走过去点在装着的终端面板上 → 核对是这台的界面、等连接状态同步过来
 * → 把要存的几样东西一样样存进网络 → 都存完才关上，不每样开关一次。
 *
 * <p>一次存东西的会话拆成两段协作：「打开与核对」与取货共用同一份 {@link TerminalSync}（只等连接状态），
 * 「逐样存」{@link StoreMoves} 自己管一格格快速移动、凑尾数与网络放不下时点回背包。
 * 每一下按背包加光标里那种东西的合计少了几件结算，少了几件才记"存进了网络"几件；没收下的照实点回背包，如实收场。
 * 没连上网络（超出访问点范围、没电）照抄界面上的原因收场并记下这台没连上，下次挑终端排后面；
 * 腾地方改走箱子或丢。认终端、开终端和取货是同一份（{@link NearbyTerminals}）。
 */
public final class MenuNetworkStores implements NetworkStorage {

    private final Ae2Compat compat;
    /** 认终端、到终端跟前：和取货共用一套认法。 */
    private final NearbyTerminals terminals;

    public MenuNetworkStores(Ae2Compat compat, PlayerServices services, SeenNetworkStock seen,
            Supplier<Instant> clock) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.terminals = new NearbyTerminals(compat, services, seen, clock);
    }

    @Override public boolean reachable(TerminalSearch search) {
        return pick(search).access() != null;
    }

    @Override public TerminalPick store(List<BackpackStack> stacks, String purpose, TerminalSearch search,
            TaskRecords records) {
        if (stacks.isEmpty()) return new TerminalPick.NotFound("没有要存的东西");
        Picked picked = pick(search);
        return picked.access() == null ? new TerminalPick.NotFound(picked.reason())
                : new TerminalPick.Chosen(new Storing(picked.access(), stacks, purpose, records));
    }

    /** 挑终端的结果：挑到了 access 不为空；挑不到 reason 写明身上和附近各是什么情况。 */
    private record Picked(TerminalAccess access, String reason) {
    }

    // 在身边找：身上绑好的无线终端拿出来就能开，比走去哪台都近，先用它；没带再找脚边的。
    // 点了地方：只找那里装着的终端，走过去点开。装着的按存东西的挑法挑（别人的不用，上次没连上的排后面）。
    private Picked pick(TerminalSearch search) {
        if (search.nearMeFirst()) {
            Optional<String> wireless = terminals.carriedWireless();
            if (wireless.isPresent()) return new Picked(terminals.wireless(wireless.get()), null);
        }
        Optional<BlockPos> center = search.nearMeFirst() ? terminals.feet() : Optional.of(search.near());
        Optional<String> dimension = terminals.dimension();
        if (center.isEmpty() || dimension.isEmpty()) return new Picked(null, "角色不在世界里，找不了终端");
        TerminalChooser.Choice choice = TerminalChooser.toStore(
                terminals.around(center.get(), search.radius(), search.permissions()), search.radius());
        String noWireless = search.nearMeFirst() ? "身上没有绑了访问点的无线终端，" : "";
        return switch (choice) {
            case TerminalChooser.None none -> new Picked(null, noWireless + none.reason());
            case TerminalChooser.Unknown unknown -> placed(unknown.candidate(), dimension.get(), search);
            case TerminalChooser.Known known -> placed(known.candidate(), dimension.get(), search);
        };
    }

    private Picked placed(TerminalChooser.Candidate candidate, String dimension, TerminalSearch search) {
        return new Picked(terminals.placed(candidate.terminal(), dimension, search.permissions()), null);
    }

    /** 存几堆进网络的会话：打开 → 等连上 → 一样样一格格快速移动 → 关上，跨刻推进。 */
    private final class Storing implements Action {

        /** 存东西会话的先后顺序。 */
        private enum Stage { OPEN, SYNC, STORE, CLOSE, DONE }

        /** 打开以后存东西的期限（刻）：存几格、关上，半分钟足够；到点如实收场。走过去的路由靠近自己管。 */
        private static final long GIVE_UP_TICKS = 20L * 30;

        private final TerminalSession session;
        private final TerminalSync sync;
        private StoreMoves moves;
        private final List<BackpackStack> stacks;
        private final String purpose;
        private final TaskRecords records;
        private Stage stage = Stage.OPEN;
        private Action closing;
        private long waited;
        /** 收场的原因：一件都没存进时按它失败；存进了就算做完。 */
        private Problem stopReason;

        Storing(TerminalAccess access, List<BackpackStack> stacks, String purpose, TaskRecords records) {
            this.session = new TerminalSession(access, compat);
            this.sync = TerminalSync.waitingForLink(session);
            this.stacks = List.copyOf(stacks);
            this.purpose = purpose;
            this.records = records;
        }

        @Override public ActionStatus tick(TickContext tick) {
            // 走过去、点开由打开动作自己管（走不到它会如实失败）；点开以后才开始算存东西的期限。
            if (stage != Stage.OPEN && ++waited > GIVE_UP_TICKS && stage.ordinal() < Stage.CLOSE.ordinal()) {
                stop(Problem.of(Problem.Kind.STUCK, "往 " + session.access().describe() + " 连着的网络存东西超时",
                        null));
            }
            return switch (stage) {
                case OPEN -> open(tick);
                case SYNC -> onTurn(sync.proceed(tick), Stage.STORE);
                case STORE -> onTurn(moves.proceed(tick), null);
                case CLOSE -> closeMenu(tick);
                case DONE -> result();
            };
        }

        // 拿出终端右键打开：打不开把原因原样交回，腾地方改走别的路。
        private ActionStatus open(TickContext tick) {
            ActionStatus status = session.opening().tick(tick);
            if (!(status instanceof ActionStatus.Done)) return status;
            session.bindMenu();
            moves = new StoreMoves(session, records, session.access().describe(), stacks, purpose);
            stage = Stage.SYNC;
            return ActionStatus.progressed();
        }

        // 一段交回的走向换成会话的推进：进下一段、收场，或把动作状态原样交回。afterSync 是连上后去的段。
        private ActionStatus onTurn(TerminalTurn turn, Stage afterSync) {
            if (turn instanceof TerminalTurn.Stay(ActionStatus status)) return status;
            if (turn instanceof TerminalTurn.Synced) {
                stage = afterSync;
                return ActionStatus.progressed();
            }
            if (turn instanceof TerminalTurn.FinishNow(Problem reason)) {
                // 界面已经不在了：不用关，直接收场。
                if (stopReason == null) stopReason = reason;
                stage = Stage.DONE;
                return result();
            }
            return stopNow(((TerminalTurn.WrapUp) turn).reason());
        }

        // 关界面：打开者负责关闭；关不上照常收场。
        private ActionStatus closeMenu(TickContext tick) {
            if (closing == null) closing = session.menu().closing();
            ActionStatus status = closing.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            stage = Stage.DONE;
            return result();
        }

        // 收场：存进了就算做完；一件都没存进就按收场原因失败，腾地方改走别的路。
        private ActionStatus result() {
            if (stopReason == null || (moves != null && moves.stored() > 0)) return ActionStatus.done();
            return ActionStatus.failed(stopReason);
        }

        // 收场：还没打开界面就没有要关的，直接结束；开着就去关。
        private void stop(Problem reason) {
            if (stopReason == null) stopReason = reason;
            stage = session.menu() == null ? Stage.DONE : Stage.CLOSE;
        }

        private ActionStatus stopNow(Problem reason) {
            stop(reason);
            return ActionStatus.progressed();
        }

        // 凑光标上那一小份、没收下的部分还没点回背包时停不得：东西在光标上，撒手会落进流程外。
        @Override public Interruptibility interruptibility() {
            return moves != null && moves.unsafeToStop()
                    ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
        }

        @Override public void pause() {
            session.opening().pause();
            session.abandonMenu();
        }

        // 收尾：打开动作一并收尾；界面还开着就请游戏关上，光标上的东西由原版的关闭流程还回背包。
        @Override public void close() {
            session.opening().close();
            if (closing != null) closing.close();
            if (session.menu() != null && stage != Stage.DONE) session.abandonMenu();
        }

        @Override public String describe() {
            BackpackStack current = moves == null ? stacks.getFirst() : moves.current();
            return stacks.size() == 1
                    ? "把 " + current.count() + " 个 " + current.itemId() + " 存进 "
                            + session.access().describe() + " 连着的网络"
                    : "把 " + stacks.size() + " 样东西存进 " + session.access().describe()
                            + " 连着的网络（正在存 " + current.itemId() + "）";
        }
    }
}
