// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.menu;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 菜单等待记录的测试工厂：在离线单测里构造待确认的菜单操作并按剧本推进终态，不改动生产可见性。
 */
public final class TestPendingMenuActions {

    private TestPendingMenuActions() {}

    /** 构造一笔处于等待服务端确认状态的点击或交换记录。 */
    public static PendingMenuAction pendingClick(PlayerContext context, int slot, int timeoutTicks) {
        return new PendingMenuAction(
                PendingMenuAction.Kind.CLICK,
                context,
                0,
                1,
                slot,
                timeoutTicks,
                false,
                (current, action) -> MenuConfirmation.Verdict.PENDING);
    }

    /** 把等待中的菜单操作推进到指定的终态与说明。 */
    public static void finish(PendingMenuAction pending, PendingMenuAction.Status status, String detail) {
        pending.finish(status, detail);
    }
}
