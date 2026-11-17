// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.ability.machine.spi.FeedReport;
import org.maiwithu.maicraft.behavior.inventory.containers.MenuFeed;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 经界面往机器里投料的联动动作：行为层的投料动作接上机器能力的实情接口——
 * machine_run 按动作有没有交实情（{@link FeedReport}）记投料账，行为层的动作自己只管投与核对，
 * 这里把"确认放进去几件、哪些没放进"接给它。各模组经界面的投料（通用机械的机器、AE2 压印器、农夫乐事厨锅）共用。
 */
public final class CompatMenuFeed implements Action, FeedReport {

    private final MenuFeed feed;

    public CompatMenuFeed(MenuFeed feed) {
        this.feed = Objects.requireNonNull(feed, "feed");
    }

    @Override public ActionStatus tick(TickContext context) {
        return feed.tick(context);
    }

    @Override public int delivered() {
        return feed.delivered();
    }

    @Override public List<String> notes() {
        return feed.notes();
    }

    @Override public void pause() {
        feed.pause();
    }

    @Override public void close() {
        feed.close();
    }

    @Override public Interruptibility interruptibility() {
        return feed.interruptibility();
    }

    @Override public String describe() {
        return feed.describe();
    }
}
