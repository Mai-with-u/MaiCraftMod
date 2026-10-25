// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.breath;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.survival.SurvivalFakes;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 水下行走呼吸预算：头在水里、氧气够不着上浮或已不到三分之一时放弃这段路，其余继续走。 */
class UnderwaterWalkBudgetTest {

    @Test
    void headAboveWaterOrWaterBreathingKeepsWalking() {
        // 只有脚泡在水里、头露在外面：不耗氧，走路照常。
        assertEquals(UnderwaterWalkBudget.Verdict.KEEP_WALKING,
                UnderwaterWalkBudget.judge(SurvivalFakes.calm().air(0).build()));
        // 有水下呼吸类效果：在水底待着也不缺气。
        assertEquals(UnderwaterWalkBudget.Verdict.KEEP_WALKING,
                UnderwaterWalkBudget.judge(SurvivalFakes.calm().underwater(10).breathing().build()));
    }

    @Test
    void plentyOfAirKeepsWalking() {
        // 刚潜下去，氧气还满：浅滩蹚水、短暂埋头都不受影响。
        assertEquals(UnderwaterWalkBudget.Verdict.KEEP_WALKING,
                UnderwaterWalkBudget.judge(SurvivalFakes.calm().underwater(300).build()));
        assertEquals(UnderwaterWalkBudget.Verdict.KEEP_WALKING,
                UnderwaterWalkBudget.judge(SurvivalFakes.calm().underwater(120).build()));
    }

    @Test
    void airBelowOneThirdGivesUpAndSurfaces() {
        // 剩 90 刻（三泡）：换气需求已经要找空当浮头，走路段不能再潜着赶路。
        assertEquals(UnderwaterWalkBudget.Verdict.GIVE_UP_AND_SURFACE,
                UnderwaterWalkBudget.judge(SurvivalFakes.calm().underwater(90).build()));
    }

    @Test
    void airNotEnoughToSurfaceGivesUpEvenWhenPlentyLeft() {
        // 深水底下还剩 150 刻：看着有五泡，游上去要 160 刻，再走下去就浮不上来了。
        assertEquals(UnderwaterWalkBudget.Verdict.GIVE_UP_AND_SURFACE,
                UnderwaterWalkBudget.judge(SurvivalFakes.calm().underwater(150).surfaceNeeds(160).build()));
    }
}
