// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.List;
import java.util.Optional;

import net.minecraft.world.inventory.AbstractContainerMenu;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 「打开与核对」：走到终端跟前点在面板上打开（不点线缆芯）、核对是这台终端的界面、等网络连接状态同步过来；
 * 取货还等网络存货表一齐到，存东西只等连接状态。没连上网络照抄界面上的原因（无线终端超出访问点范围、
 * 没电、没频道）如实收场；空表或没连上多等一小会儿，免得把还没到的同步当成空网络。
 * 取货与存东西共用这一段，等什么由构造时定。
 */
final class TerminalSync {

    /** 等同步等的是什么：取货要等存货表一齐到，存东西只等连接状态。 */
    private enum Mode { STOCK, LINK }

    /** 点开后等网络存货表与连接状态同步过来的期限（刻）；等不到不把没同步的网络当成空的。 */
    private static final int STOCK_WAIT_TICKS = 40;
    /** 存货表出现后再等几刻（刻）：网络是空的或没连上时，同步到的就是空表，等过这一小会儿才下结论。 */
    private static final int STOCK_SETTLE_TICKS = 10;
    /** 存东西打开后等连接状态同步过来的期限（刻）。 */
    private static final int LINK_WAIT_TICKS = 40;

    private final TerminalSession session;
    private final Mode mode;
    /** 等网络读数同步过来已经等了几刻。 */
    private int waited;
    /** 空表或没连上时又多等了几刻。 */
    private int settleWaited;

    /** 取货用的：等连接状态与网络存货表一齐到。 */
    static TerminalSync waitingForStock(TerminalSession session) {
        return new TerminalSync(session, Mode.STOCK);
    }

    /** 存东西用的：只等连接状态，存货表归逐样存那段自己看。 */
    static TerminalSync waitingForLink(TerminalSession session) {
        return new TerminalSync(session, Mode.LINK);
    }

    private TerminalSync(TerminalSession session, Mode mode) {
        this.session = session;
        this.mode = mode;
    }

    // 走过去点开：走不到、点不开，都把打开动作的原因原样交回，引擎换路；点开了就核对、等同步。
    TerminalTurn proceed(TickContext tick) {
        if (session.menu() == null) {
            ActionStatus status = session.opening().tick(tick);
            if (!(status instanceof ActionStatus.Done)) return new TerminalTurn.Stay(status);
            session.bindMenu();
            return new TerminalTurn.Stay(ActionStatus.progressed());
        }
        return sync(tick);
    }

    // 核对是这台终端的界面，再等连接状态与网络存货同步过来；没连上网络就如实收场。
    private TerminalTurn sync(TickContext tick) {
        PlayerContext player = tick.player();
        AbstractContainerMenu raw = session.rawPage(player);
        if (session.reading().isEmpty()) {
            return new TerminalTurn.FinishNow(Problem.of(Problem.Kind.TARGET_GONE, "终端界面刚打开就被关上了", null));
        }
        if (!session.access().belongs(raw, player.level())) {
            return new TerminalTurn.WrapUp(Problem.of(Problem.Kind.TARGET_GONE,
                    "点开的不是 " + session.access().describe() + " 的界面", null), false);
        }
        Optional<Boolean> linked = session.compat().networkLinked(raw);
        Optional<List<Ae2TerminalMenu.StockEntry>> stock = mode == Mode.STOCK
                ? session.compat().networkStock(raw) : Optional.of(List.of());
        if (linked.isEmpty() || stock.isEmpty()) {
            if (++waited > waitLimit()) {
                return new TerminalTurn.WrapUp(Problem.of(Problem.Kind.STUCK,
                        mode == Mode.STOCK ? "终端界面开了，网络存货一直没同步过来"
                                : "终端界面开了，网络连接状态一直没同步过来", null), false);
            }
            return new TerminalTurn.Stay(ActionStatus.running());
        }
        if (mode == Mode.LINK) {
            // 记下这台没连上：下次存东西挑终端排后面。连上时不记，存货没等全，记了会把没到的当成没有。
            if (!linked.get()) {
                session.access().remember(false, List.of(), true);
                return new TerminalTurn.WrapUp(unlinked(raw, "存不了"), false);
            }
            return new TerminalTurn.Synced();
        }
        // 连上了、也有货就不用再等；空表或没连上时多等一小会儿，免得把还没到的同步当成空网络。
        boolean clearlyReady = linked.get() && !stock.get().isEmpty();
        if (!clearlyReady && ++settleWaited <= STOCK_SETTLE_TICKS) return new TerminalTurn.Stay(ActionStatus.running());
        // 刚同步过来的多半只是第一包（大网络分好几包发）：先按"没看全"记，免得下次把没到的当成没有。
        session.access().remember(linked.get(), stock.get(), !linked.get());
        if (!linked.get()) return new TerminalTurn.WrapUp(unlinked(raw, "取不了"), false);
        return new TerminalTurn.Synced();
    }

    // 没连上的说法：界面上写着原因就照抄（无线终端超出访问点范围、没电、没频道）；读不到才用笼统的说法。
    private Problem unlinked(AbstractContainerMenu raw, String whatStops) {
        String why = session.compat().unlinkedWhy(raw).map(text -> "（" + text + "）").orElse("（没电或没频道）");
        return Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                session.access().describe() + " 没连上 ME 网络" + why + "，" + whatStops, null);
    }

    // 重开终端后等同步的计数清零，重新按期限等。
    void resetCounters() {
        waited = 0;
        settleWaited = 0;
    }

    private int waitLimit() {
        return mode == Mode.STOCK ? STOCK_WAIT_TICKS : LINK_WAIT_TICKS;
    }
}
