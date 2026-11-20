// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 挖掘器服务端回弹与破块确认终态映射的离线测试。 */
class BlockDiggerRollbackTest {

    @Test
    void 客户端破坏消失后又恢复非空判定为服务端回弹() {
        assertTrue(BlockDigger.rolledBackAfterGone(true, false));
        assertFalse(BlockDigger.rolledBackAfterGone(false, false), "还没看到方块消失不算回弹");
        assertFalse(BlockDigger.rolledBackAfterGone(true, true), "仍保持消失状态不算回弹");
    }

    @Test
    void 低延迟单刻消失后次刻恢复原方块可被准确捕获_高延迟持续未消失不误判() {
        // 模拟低延迟/瞬挖：第 N 刻刚发完破块 settleBreak 见客户端已消失（sawGone -> true），
        // 第 N+1 刻服务端回弹把原方块写回（goneNow = false）时立即判定回弹。
        boolean sawGone = BlockDigger.updateSawBlockGone(false, true);
        assertTrue(sawGone, "单刻内观测到目标格消失应立刻记录 sawBlockGone");
        assertTrue(BlockDigger.rolledBackAfterGone(sawGone, false), "低延迟单刻消失次刻恢复必须判定回弹");

        // 模拟高延迟正常挖掘：多刻内目标格一直未消失，绝不误判为回弹。
        boolean highLatencySawGone = BlockDigger.updateSawBlockGone(false, false);
        assertFalse(highLatencySawGone);
        assertFalse(BlockDigger.rolledBackAfterGone(highLatencySawGone, false));
        // 高延迟下客户端终于消失且持续保持消失，不判回弹。
        highLatencySawGone = BlockDigger.updateSawBlockGone(highLatencySawGone, true);
        assertFalse(BlockDigger.rolledBackAfterGone(highLatencySawGone, true));
    }

    @Test
    void 破块确认被拒绝或状态分歧映射为REFUSED而非打不着() {
        assertEquals(BlockDigger.DigResult.BROKE_TARGET,
                BlockDigger.settleStatus(PendingInteraction.Status.CONFIRMED_APPLIED, true));
        assertEquals(BlockDigger.DigResult.BROKE_OCCLUDER,
                BlockDigger.settleStatus(PendingInteraction.Status.CONFIRMED_APPLIED, false));
        assertEquals(BlockDigger.DigResult.REFUSED,
                BlockDigger.settleStatus(PendingInteraction.Status.CONFIRMED_NOT_APPLIED, true));
        assertEquals(BlockDigger.DigResult.REFUSED,
                BlockDigger.settleStatus(PendingInteraction.Status.DIVERGED, true));
        assertEquals(BlockDigger.DigResult.NO_SHOT,
                BlockDigger.settleStatus(PendingInteraction.Status.UNCERTAIN, true));
    }
}
