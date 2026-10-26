// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 任务边界（目标被替换、取消、任务结束）对待确认提交的结算：等待记录写明「到期限或玩家变更时结算」，
 * 玩家变更就是替换这条。路由按动作种类分派到各自的边界收场；已经终结的按冻结的结论原样交回，
 * 不重复记账也不翻案，新任务的第一次出手不被旧等待挡住。
 */
class ItemUseBoundarySettlementTest {

    private static PendingInteraction useItemPending(FakePlayerContext context, int timeoutTicks) {
        return new PendingInteraction(PendingInteraction.Kind.USE_ITEM, context,
                timeoutTicks, 1, InteractionConfirmation.pending(), null, null);
    }

    @Test
    void 等确认中的物品使用被替换时按边界结算交还() {
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        FakePlayerContext context = new FakePlayerContext(null);
        PendingInteraction pending = useItemPending(context, 30);

        PendingInteraction settled = sender.settleForTaskBoundary(context, pending, "目标被替换");

        // 提交可能已经生效：如实按不确定收场并放掉等待占位，不再挡新任务的第一次出手。
        assertEquals(PendingInteraction.Status.UNCERTAIN, settled.status());
        assertTrue(settled.terminal());
        assertEquals(1, sender.settleItemUseCalled);
        assertFalse(sender.retireCalled, "物品使用不再按一次性提交误交还");
    }

    @Test
    void 路由把其他一次性提交交给原有的不确定收场() {
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        FakePlayerContext context = new FakePlayerContext(null);
        PendingInteraction pending = new PendingInteraction(PendingInteraction.Kind.INTERACT_ENTITY,
                context, 30, 1, InteractionConfirmation.pending(), null, null);

        PendingInteraction settled = sender.settleForTaskBoundary(context, pending, "目标被替换");

        assertEquals(PendingInteraction.Status.UNCERTAIN, settled.status());
        assertTrue(sender.retireCalled);
        assertEquals(0, sender.settleItemUseCalled);
    }

    @Test
    void 期限先到结算的结论不被边界翻案() {
        DefaultInteractionSender sender = new DefaultInteractionSender(null);
        FakePlayerContext context = new FakePlayerContext(null);
        PendingInteraction pending = new PendingInteraction(PendingInteraction.Kind.USE_ITEM, context,
                30, 1, InteractionConfirmation.pending(), null, null);
        // 期限先到：放船这类手势按「提交了但没生效」收场（与 poll 里同一份断定）。
        pending.finish(DefaultInteractionSender.expiryStatus(InteractionConfirmation.pending()), "窗口到期");

        PendingInteraction settled = sender.settleForTaskBoundary(context, pending, "目标被替换");

        assertSame(pending, settled);
        assertEquals(PendingInteraction.Status.UNCERTAIN, settled.status());
        assertEquals("窗口到期", settled.detail(), "边界只照读冻结的结论，不重新记账");
    }

    @Test
    void 边界结算不认端口里没有的等待() {
        DefaultInteractionSender sender = new DefaultInteractionSender(null);
        FakePlayerContext context = new FakePlayerContext(null);
        PendingInteraction stranger = useItemPending(context, 30);

        assertThrows(IllegalArgumentException.class,
                () -> sender.settleItemUseForTaskBoundary(context, stranger, "目标被替换"));
    }
}
