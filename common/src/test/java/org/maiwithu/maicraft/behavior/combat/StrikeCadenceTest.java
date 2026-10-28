// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 出刀节拍：离下一刀的刻数不超过走到出手带外沿要的刻数加一刻就往里走。 */
class StrikeCadenceTest {

    @Test
    void stepsInJustEarlyEnoughToArriveCharged() {
        // 打玩家：站在离他 4 格的冷却带里，离出手带外沿（3.15 格）差 0.85 格，走路 0.22 格/刻要 4 刻。
        double gap = 4.0 - 3.15;
        assertEquals(4, StrikeCadence.walkTicks(gap, StrikeCadence.WALK_PER_TICK));
        assertFalse(StrikeCadence.stepIn(10, gap, StrikeCadence.WALK_PER_TICK), "剑还差 10 刻：站着等");
        assertFalse(StrikeCadence.stepIn(6, gap, StrikeCadence.WALK_PER_TICK), "还差 6 刻：还早");
        assertTrue(StrikeCadence.stepIn(5, gap, StrikeCadence.WALK_PER_TICK), "还差 5 刻：走 4 刻加落脚一刻，正好");
        assertTrue(StrikeCadence.stepIn(0, gap, StrikeCadence.WALK_PER_TICK), "够格了当然往里走");
    }

    @Test
    void alreadyInTheStrikeBandOnlyStepsInAtTheLastTick() {
        // 打怪时站在两条带重合的缝里：差的距离是零，和原来充满才换带几乎一样，只早一刻。
        assertFalse(StrikeCadence.stepIn(2, 0, StrikeCadence.WALK_PER_TICK));
        assertTrue(StrikeCadence.stepIn(1, 0, StrikeCadence.WALK_PER_TICK));
        assertTrue(StrikeCadence.stepIn(1, -0.4, StrikeCadence.WALK_PER_TICK));
    }

    @Test
    void slowerWalkingStartsEarlier() {
        // 喝了缓慢走得慢：同样差 0.85 格要走更久，起步就更早。
        assertTrue(StrikeCadence.stepIn(9, 0.85, 0.11));
        assertFalse(StrikeCadence.stepIn(9, 0.85, StrikeCadence.WALK_PER_TICK));
    }
}
