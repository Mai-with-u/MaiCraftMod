// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.result.Problem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 算路时长上限的逐刻判断：只在引擎真在算时累计，走出来了清零，自我重算重新起算，超时给出带起点状态的问题。 */
class PlanningCeilingTest {

    @Test
    void continuousCalculatingPastLimitFails() {
        PlanningCeiling ceiling = new PlanningCeiling(100);
        for (int i = 0; i < 99; i++) {
            assertFalse(ceiling.observe(false, true), "在算的每一刻都还在上限之内");
        }
        assertTrue(ceiling.observe(false, true), "连续算满上限就承认这一段已经死在算路里");
    }

    @Test
    void routeAppearingResetsTheClock() {
        PlanningCeiling ceiling = new PlanningCeiling(100);
        for (int i = 0; i < 60; i++) ceiling.observe(false, true);
        // 半路算出了一段可走的路线：人上路了，账清零，接着走的算路重新计。
        assertFalse(ceiling.observe(true, true));
        for (int i = 0; i < 99; i++) assertFalse(ceiling.observe(false, true), "上一段算路的时间不算到这一段头上");
        assertTrue(ceiling.observe(false, true));
    }

    @Test
    void engineGapsBetweenSearchesDoNotResetTheClock() {
        PlanningCeiling ceiling = new PlanningCeiling(100);
        for (int i = 0; i < 50; i++) ceiling.observe(false, true);
        // 两轮搜索之间引擎换气的空档：不在算也不在有路线上，账照旧累计，不重新开始。
        ceiling.observe(false, false);
        ceiling.observe(false, false);
        for (int i = 0; i < 49; i++) assertFalse(ceiling.observe(false, true));
        assertTrue(ceiling.observe(false, true), "空档不算重新算路，一笔连着的账");
    }

    @Test
    void deliberateReplanStartsAFreshClock() {
        PlanningCeiling ceiling = new PlanningCeiling(100);
        for (int i = 0; i < 90; i++) ceiling.observe(false, true);
        // 脱困升级、只读复算后的重算这类自我修正：次数自己有上限，上限重新起算不替它们收场。
        ceiling.restart();
        for (int i = 0; i < 99; i++) assertFalse(ceiling.observe(false, true), "重新算路后重新计");
        assertTrue(ceiling.observe(false, true));
    }

    @Test
    void timeoutProblemCarriesStartVoxelState() {
        Problem inWater = PlanningCeiling.timeoutProblem(
                null, new BlockPos(120, 62, -8), true, false, 300);
        assertEquals(Problem.Kind.STUCK, inWater.kind());
        assertTrue(inWater.message().contains("(120, 62, -8)"), "写清起点在哪");
        assertTrue(inWater.message().contains("泡在水里"), "起点身体在水里要写明");
        assertTrue(inWater.message().contains("15 秒"), "等了多久要写明");
        Problem airborne = PlanningCeiling.timeoutProblem(null, new BlockPos(0, 70, 0), false, false, 300);
        assertTrue(airborne.message().contains("悬空"), "不站实也不在水里是悬空");
        Problem grounded = PlanningCeiling.timeoutProblem(null, new BlockPos(0, 70, 0), false, true, 300);
        assertTrue(grounded.message().contains("站在地上"), "站实了也说清");
        assertTrue(!grounded.message().contains("脚下方块"), "读不到世界就不写脚下方块这一项");
    }
}
