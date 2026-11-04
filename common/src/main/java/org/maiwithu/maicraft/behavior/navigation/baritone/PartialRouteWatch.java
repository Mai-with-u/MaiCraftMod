// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.maiwithu.maicraft.behavior.navigation.calc.HeuristicCosts;

/**
 * 路线到不了目标、引擎只给"往那边最好走到哪"的半截路时，看人有没有离目标更近。
 * 半截路走了又算、算了又走，半分钟没比之前最近的一次更近两格，就当这条路走不通，交还上层换个去处——
 * 像人绕着一片岩浆海找不到过去的路，转一会儿就换个方向，不在原地来回挪。
 * 实机 10-15 下界岩浆海边探索，引擎一直给五到七格的半截路，人在原地来回挪了四分钟（身子一直在动，原先的卡住判断不管）。
 */
final class PartialRouteWatch {

    /** 半截路上这么久没更近就放弃。 */
    static final int LIMIT_TICKS = 600;
    /** 更近两格才算更近：寻路估价一格约 3.6。 */
    static final double MIN_GAIN = 2 * HeuristicCosts.COST_HEURISTIC;

    private static final Logger LOG = LoggerFactory.getLogger(PartialRouteWatch.class);

    private double best = Double.MAX_VALUE;
    private long bestTick = Long.MIN_VALUE;

    /**
     * 走在半截路上的一刻：比最近的一次更近就重新起算，否则看过了多久。
     *
     * @param remaining 此刻离目标的寻路估价
     * @return 半截路上已经这么久没更近
     */
    boolean stalled(double remaining, long tick) {
        if (bestTick == Long.MIN_VALUE || tick < bestTick || remaining < best - MIN_GAIN) {
            best = Math.min(best, remaining);
            bestTick = tick;
            return false;
        }
        return tick - bestTick > LIMIT_TICKS;
    }

    /**
     * 走路时的半截路一刻：和 {@link #stalled} 同一份判断，另外开始数、数满判死各记一行调试日志，
     * 实机里对得上是哪道闸收的场、数了多久。
     */
    boolean watch(double remaining, long tick, BlockPos feet) {
        boolean starting = bestTick == Long.MIN_VALUE;
        long since = bestTick;
        boolean stalled = stalled(remaining, tick);
        if (starting) {
            LOG.debug("[maicraft-path] 只有半截路，开始数有没有更近（离目标估价 {}），在 {}",
                    Math.round(remaining), feet.toShortString());
        }
        if (stalled) {
            LOG.debug("[maicraft-path] 半截路 {} 刻没更近两格，判走不通，在 {}", tick - since, feet.toShortString());
        }
        return stalled;
    }

    /** 续走、有了整路时从头算；正在数的话记一行为什么清零、已经数了多久。 */
    void reset(String why, long tick, BlockPos feet) {
        if (bestTick != Long.MIN_VALUE) {
            LOG.debug("[maicraft-path] {}，半截路计时清零（已数 {} 刻），在 {}", why, tick - bestTick, feet.toShortString());
        }
        reset();
    }

    /** 有了一条能到目标的整路、或换了目标：从头算。 */
    void reset() {
        best = Double.MAX_VALUE;
        bestTick = Long.MIN_VALUE;
    }
}
