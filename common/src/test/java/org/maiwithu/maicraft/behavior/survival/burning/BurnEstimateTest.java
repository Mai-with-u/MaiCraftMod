// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.burning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.survival.burning.BurnEstimate.Exposure;
import org.maiwithu.maicraft.behavior.survival.burning.BurnEstimate.Source;

/** 估这把火：按身体待过的火源与着火时长属性算还要烧多久、掉多少血、够不够烧死。 */
class BurnEstimateTest {

    @Test
    void justOutOfLavaWithFourHealthIsLethal() {
        // 刚爬出岩浆：还要烧 15 秒、掉 15 点，只剩 4 血。
        BurnEstimate burn = BurnEstimate.of(List.of(new Exposure(Source.LAVA, 100)), 100, 1.0, 4, 0);
        assertEquals(300, burn.remainingTicks());
        assertEquals(15, burn.damage());
        assertTrue(burn.lethal());
    }

    @Test
    void fullHealthOutlastsALavaBurn() {
        assertFalse(BurnEstimate.of(List.of(new Exposure(Source.LAVA, 100)), 100, 1.0, 20, 0).lethal());
    }

    @Test
    void absorptionCountsTowardSurviving() {
        // 12 血加 4 点黄心扛得住 15 点吗？扛不住；20 血就扛得住。
        assertTrue(BurnEstimate.of(List.of(new Exposure(Source.LAVA, 0)), 0, 1.0, 12, 2).lethal());
        assertFalse(BurnEstimate.of(List.of(new Exposure(Source.LAVA, 0)), 0, 1.0, 12, 4).lethal());
    }

    @Test
    void halvedBurningTimeHalvesTheEstimate() {
        // 火焰保护把着火时长属性压到一半：15 秒变 7.5 秒，伤害向上取整 8 点。
        BurnEstimate burn = BurnEstimate.of(List.of(new Exposure(Source.LAVA, 0)), 0, 0.5, 20, 0);
        assertEquals(150, burn.remainingTicks());
        assertEquals(8, burn.damage());
    }

    @Test
    void timeSinceLeavingTheSourceIsSubtracted() {
        // 出了火 3 秒：8 秒的火还剩 5 秒。
        BurnEstimate burn = BurnEstimate.of(List.of(new Exposure(Source.FIRE, 40)), 100, 1.0, 20, 0);
        assertEquals(100, burn.remainingTicks());
        assertEquals(5, burn.damage());
        assertEquals(BurnEstimate.NONE.damage(),
                BurnEstimate.of(List.of(new Exposure(Source.FIRE, 0)), 1000, 1.0, 20, 0).damage(), "早烧完了");
    }

    @Test
    void theLongestSourceWins() {
        // 先泡过岩浆、又踩进火：原版只把着火时间往长了顶，按岩浆那份算。
        BurnEstimate burn = BurnEstimate.of(List.of(new Exposure(Source.LAVA, 0), new Exposure(Source.FIRE, 20)),
                20, 1.0, 20, 0);
        assertEquals(280, burn.remainingTicks());
    }

    @Test
    void exposuresFollowWhereTheBodyWas() {
        BurnEstimate.Exposures exposures = new BurnEstimate.Exposures();
        // 在岩浆里泡到第 10 刻，第 11 刻爬出来还着着火：岩浆按第 10 刻算。
        for (long tick = 0; tick <= 10; tick++) exposures.observe(tick, true, false, true);
        exposures.observe(11, false, false, true);
        assertEquals(List.of(new Exposure(Source.LAVA, 10)), exposures.exposures());
        // 火灭了：上一把火不再算数。
        exposures.observe(12, false, false, false);
        assertTrue(exposures.exposures().isEmpty());
        // 看不出来源地着了火（火矢）：从第一次看到算，后面接着着火不往后挪。
        exposures.observe(50, false, false, true);
        exposures.observe(60, false, false, true);
        assertEquals(List.of(new Exposure(Source.UNSEEN, 50)), exposures.exposures());
    }
}
