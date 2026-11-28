// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.maiwithu.maicraft.behavior.navigation.calc.HeuristicCosts;

/**
 * 路线到不了目标、引擎只给"往那边最好走到哪"的半截路时，看人有没有离目标更近。
 * 长途出行与探索默认保留 30 秒（600 刻）容忍，避免跨区块、涉水或绕宽障碍时被误判走不通；
 * 局部靠近站位等短距场景可按构造参数传入更短的止损上限。
 * 途中因等移动障碍、备料换手、落地救援、收拾装备或挖硬方块暂停推进时，通过 {@link #hold(long)} 顺延起算刻，
 * 不把合法停等耗时扣进半截路预算。
 */
final class PartialRouteWatch {

    /** 长途出行与探索默认的半截路止损上限（30 秒）：跨区块与绕宽障碍留足推进时间。 */
    static final int LIMIT_TICKS = 600;
    /** 更近两格才算更近：寻路估价一格约 3.6。 */
    static final double MIN_GAIN = 2 * HeuristicCosts.COST_HEURISTIC;

    private static final Logger LOG = LoggerFactory.getLogger(PartialRouteWatch.class);

    private final int limitTicks;
    private double best = Double.MAX_VALUE;
    private long bestTick = Long.MIN_VALUE;
    /** 最近一次参与半截路推进或顺延的游戏刻：用于计算停等区间的顺延量。 */
    private long lastTick = Long.MIN_VALUE;
    /** 最近一次推进消耗的刻数：同刻先 stalled(T) 再 hold(T) 时把这一刻顺延回去，顺延后清零防重算。 */
    private long lastElapsed;

    PartialRouteWatch() {
        this(LIMIT_TICKS);
    }

    PartialRouteWatch(int limitTicks) {
        if (limitTicks <= 0) throw new IllegalArgumentException("半截路止损刻数必须大于 0");
        this.limitTicks = limitTicks;
    }

    int limitTicks() {
        return limitTicks;
    }

    /**
     * 走在半截路上的一刻：比最近的一次更近就重新起算，否则看净推进了多久。
     *
     * @param remaining 此刻离目标的寻路估价
     * @return 半截路上已经这么久没更近
     */
    boolean stalled(double remaining, long tick) {
        if (bestTick == Long.MIN_VALUE || tick < bestTick || remaining < best - MIN_GAIN) {
            best = Math.min(best, remaining);
            bestTick = tick;
            lastTick = tick;
            lastElapsed = 0;
            return false;
        }
        lastElapsed = lastTick != Long.MIN_VALUE && tick > lastTick ? tick - lastTick : 0;
        lastTick = tick;
        return tick - bestTick > limitTicks;
    }

    /**
     * 合法停等（等移动障碍、挪工具、落地救援、收装备、挖硬方块）期间调用：
     * 把自上次推进以来经过的刻数顺延到起算刻（含同一刻内先 stalled 后 hold 挖块的情形），
     * 不把合法停等算成半截路原地空转。
     */
    void hold(long tick) {
        if (bestTick == Long.MIN_VALUE) return;
        if (lastTick != Long.MIN_VALUE && tick > lastTick) {
            bestTick += tick - lastTick;
            lastElapsed = 0;
        } else if (tick == lastTick && lastElapsed > 0) {
            bestTick += lastElapsed;
            lastElapsed = 0;
        }
        lastTick = tick;
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
            LOG.debug("[maicraft-path] 只有半截路，开始数有没有更近（离目标估价 {}，上限 {} 刻），在 {}",
                    Math.round(remaining), limitTicks, feet.toShortString());
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

    /** 有了一条能到目标的整路、重新算路或换了目标：从头算。 */
    void reset() {
        best = Double.MAX_VALUE;
        bestTick = Long.MIN_VALUE;
        lastTick = Long.MIN_VALUE;
        lastElapsed = 0;
    }
}
