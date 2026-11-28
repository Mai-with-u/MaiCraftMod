// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 半截路看有没有更近：长途默认保持 30 秒（600 刻）容忍，局部靠近支持传短上限；
 * 一直在挪近的不放弃，合法停等期间顺延起算刻，有了整路从头算。
 */
class PartialRouteWatchTest {

    @Test
    void keepsGoingWhileGettingCloser() {
        PartialRouteWatch watch = new PartialRouteWatch();
        double remaining = 400;
        for (long tick = 0; tick <= 2000; tick += 20) {
            // 每秒近一格多。
            assertFalse(watch.stalled(remaining, tick));
            remaining -= 5;
        }
    }

    @Test
    void defaultLongTravelKeepsHalfMinuteTolerance() {
        assertEquals(600, PartialRouteWatch.LIMIT_TICKS, "全局默认半截路容忍应维持 30 秒，保护长途与绕宽障碍");
        PartialRouteWatch watch = new PartialRouteWatch();
        assertFalse(watch.stalled(100, 0));
        assertFalse(watch.stalled(103, 500), "长途半截路在 30 秒内不提前判死");
        assertTrue(watch.stalled(100, 601), "超过 600 刻仍未更近两格才判走不通");
    }

    @Test
    void customShortLimitStopsEarlierForLocalApproach() {
        PartialRouteWatch watch = new PartialRouteWatch(160);
        assertFalse(watch.stalled(100, 0));
        assertFalse(watch.stalled(103, 160), "未超过局部上限时继续给绕路机会");
        assertTrue(watch.stalled(100, 161), "超过 160 刻仍未更近两格即判死");
    }

    @Test
    void holdingDuringValidWaitDoesNotBurnPartialRouteBudget() {
        // 等移动障碍、换手、挖硬块期间调用 hold 顺延起算刻：等 100 刻回来只算恢复后的净推进刻，不被误杀。
        PartialRouteWatch watch = new PartialRouteWatch(160);
        assertFalse(watch.stalled(100, 0));
        assertFalse(watch.stalled(102, 80));
        for (long tick = 81; tick <= 180; tick++) {
            watch.hold(tick);
        }
        assertFalse(watch.stalled(102, 250), "停等 100 刻后净推进仅 150 刻，未满 160 刻不误杀");
        assertTrue(watch.stalled(102, 261), "净推进超过 160 刻才判走不通");
    }

    @Test
    void sameTickHoldAfterStalledDuringMiningDoesNotBurnPartialRouteBudget() {
        // 复现真实 BaritoneWalkRun 同刻顺序：每刻先 watchProgress -> stalled(rem, T)，再 steer -> workBlocks -> hold(T)。
        // 连续挖黑曜石/硬块 200 刻（远超局部 160 刻上限）且同刻重复调 hold 也不重复顺延，必须全程不误杀；
        // 挖完停止 hold 后再净推进满 160 刻才判走不通。
        PartialRouteWatch watch = new PartialRouteWatch(160);
        assertFalse(watch.stalled(100, 0));
        for (long tick = 1; tick <= 200; tick++) {
            assertFalse(watch.stalled(100, tick), "同刻先查 stalled 时不得因挖硬块被误杀，tick=" + tick);
            watch.hold(tick);
            watch.hold(tick);
        }
        for (long tick = 201; tick <= 360; tick++) {
            assertFalse(watch.stalled(100, tick), "挖完恢复走后净推进未满 160 刻不提前判死，tick=" + tick);
        }
        assertTrue(watch.stalled(100, 361), "挖完后净推进超过 160 刻准确判死");
    }

    @Test
    void fullRouteStartsOver() {
        PartialRouteWatch watch = new PartialRouteWatch();
        watch.stalled(100, 0);
        watch.reset();
        assertFalse(watch.stalled(100, PartialRouteWatch.LIMIT_TICKS + 100));
    }
}
