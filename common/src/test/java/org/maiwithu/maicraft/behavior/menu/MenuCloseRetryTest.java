// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.menu.MenuSession.Claim;
import org.maiwithu.maicraft.behavior.menu.MenuSession.Closing;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 关界面的补试：会话到期限还没关上时，重认领会话再请游戏关一次；补试也到期限才认输，
 * 带着的还是失败的原因。关界面动作接不接得上这份判断，靠真实客户端的关闭时序，留给实机验收。
 */
class MenuCloseRetryTest {

    // 界面通道替身：开关与关闭请求记录都按测试脚本拨动，写法与会话的测试同一家。
    private static final class FakeChannel implements MenuChannel {
        boolean open = true;
        final List<String> clicks = new ArrayList<>();
        int closeRequests;

        @Override public boolean stillOpen() { return open; }
        @Override public boolean cursorCarrying() { return false; }
        @Override public boolean click(int slot, int button) { clicks.add(slot + ":" + button); return true; }
        @Override public boolean quickMove(int slot) { return true; }
        @Override public boolean selectTradeOffer(int offerIndex) { return true; }
        @Override public void requestClose() { closeRequests++; }
    }

    // 走完一次尝试：认领会话、请游戏关，到期限界面还开着就交回失败。
    private static Closing.Failed 走到期限(FakeChannel channel, MenuSession session, long startTick) {
        assertInstanceOf(Closing.InProgress.class, session.closeNow(channel, startTick));
        Closing last = session.closeNow(channel, startTick + MenuSession.CLOSE_TIMEOUT_TICKS);
        return assertInstanceOf(Closing.Failed.class, last);
    }

    @Test
    void 第一次到期限没关上补试一次后关上() {
        FakeChannel channel = new FakeChannel();
        MenuCloseRetry retry = new MenuCloseRetry();
        // 第一次尝试：请游戏关了一次，到期限界面还开着，会话报失败。
        Closing.Failed first = 走到期限(channel, ((Claim.Owned) MenuSession.claim(channel)).session(), 0);
        assertEquals(1, channel.closeRequests);
        // 补试给机会：重认领一份会话再请游戏关一次，这次界面关上了，收尾完成。
        assertTrue(retry.grantOne());
        MenuSession second = ((Claim.Owned) MenuSession.claim(channel)).session();
        assertInstanceOf(Closing.InProgress.class, second.closeNow(channel, 0));
        assertEquals(2, channel.closeRequests);
        channel.open = false;
        assertInstanceOf(Closing.Closed.class, second.closeNow(channel, 1));
    }

    @Test
    void 两次都到期限没关上带着原因认输() {
        FakeChannel channel = new FakeChannel();
        MenuCloseRetry retry = new MenuCloseRetry();
        // 第一次失败后补试一次；补试也到期限，失败的原因如实带出去，不再有第三次关闭请求。
        走到期限(channel, ((Claim.Owned) MenuSession.claim(channel)).session(), 0);
        assertTrue(retry.grantOne());
        Closing.Failed second = 走到期限(channel, ((Claim.Owned) MenuSession.claim(channel)).session(), 500);
        assertFalse(retry.grantOne(), "补试只有一次");
        assertEquals(Problem.Kind.STUCK, second.problem().kind());
        assertEquals(2, channel.closeRequests);
    }
}
