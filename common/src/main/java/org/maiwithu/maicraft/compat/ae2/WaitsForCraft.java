// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.List;
import java.util.function.ToIntFunction;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 「下单等成品」：现货不够、网络里有样板时像玩家一样在终端里下单补缺口，走完下单那几页；
 * 开工后关掉 AE2 退回来的终端重新打开，站在跟前等成品进网络，进来一批取一批。
 * 下不下单、下几件、等成品的期限由 {@link NetworkCraft} 决定；这里把它的决定换成会话的走向，
 * 等成品时数刻、每秒报一次进展，不让外层把这段有期限的等待当成原地打转。
 */
final class WaitsForCraft {

    private final NetworkCraft craft;
    private final TakeSession session;
    /** 等成品时数刻，每秒报一次进展用。 */
    private int craftBeat;

    WaitsForCraft(Ae2Compat compat, TakeSession session, TaskRecords records) {
        this.session = session;
        this.craft = new NetworkCraft(compat, session::sendOnPage, session.access().describe(), records);
    }

    /** 下过单或在等成品：取货会话的总期限要放宽。 */
    boolean involved() {
        return craft.involved();
    }

    /** 正站在终端前等成品。被打断时界面关上、作业留在网络里接着做；回来看到作业还在做就接着等，不重复下单。 */
    boolean waiting() {
        return craft.waiting();
    }

    /** 在等的是什么，给调试面板与日志看，例如"等网络做好 44 个 minecraft:iron_ingot（到了 30 个）"。 */
    String waitingText() {
        return craft.waitingText();
    }

    // 逐笔搬发现现货不够，这一刻怎么办：下不下单、接着等还是收场，都由下单那边定。
    TerminalTurn onNoStock(AbstractContainerMenu terminal, List<Ae2TerminalMenu.StockEntry> stock,
            int taken, int remaining, ToIntFunction<ItemStack> room) {
        return switch (craft.noStock(terminal, stock, session::wanted, taken, remaining, room)) {
            case NetworkCraft.Ordering ordering -> new TerminalTurn.Order();
            // AE2 还记着这一单在做：网络在干活，每秒报一次进展，这段等待的上限由下单那边的期限管。
            case NetworkCraft.Waiting waiting -> new TerminalTurn.Stay(waiting.jobRunning() && ++craftBeat % 20 == 0
                    ? ActionStatus.progressed() : ActionStatus.running());
            case NetworkCraft.Reopen reopen -> new TerminalTurn.Reopen();
            case NetworkCraft.Stop stop -> new TerminalTurn.WrapUp(stop.problem(), stop.evenIfTaken());
            case NetworkCraft.Nothing nothing -> new TerminalTurn.WrapUp(session.nothingLeft(), false);
        };
    }

    // 下单的几页上走一刻：点合成 → 填数量、算完能做就开工 → 确认页看结论，每一刻看此刻的界面是哪一页。
    TerminalTurn proceed(TickContext tick) {
        AbstractContainerMenu page = session.rawPage(tick.player());
        return switch (craft.ordering(page)) {
            case NetworkCraft.Reopen reopen -> new TerminalTurn.Reopen();
            case NetworkCraft.Stop stop -> new TerminalTurn.WrapUp(stop.problem(), stop.evenIfTaken());
            default -> new TerminalTurn.Stay(ActionStatus.running());
        };
    }
}
