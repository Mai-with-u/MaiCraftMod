// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.burning;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 这把火还要烧多久、掉多少血、够不够把角色烧死。客户端只知道"着没着火"，不知道还剩几刻，只能按身体待过的火源估：
 * 剩余着火时间 = 那种火源的原版时长 × 着火时长属性 − 离开它之后过去的时间；待过好几种时取估得最长的那份。
 * 每秒掉 1 点，剩余秒数向上取整就是估出的伤害；火焰保护对伤害的减免不扣，宁可估多。
 * 估出的伤害不少于此刻的血加黄心，就算够烧死。
 *
 * @param remainingTicks 估计还要烧多少刻
 * @param damage         估计还要掉多少点血
 * @param lethal         估着够把角色烧死
 */
public record BurnEstimate(int remainingTicks, int damage, boolean lethal) {

    /** 没在烧。 */
    public static final BurnEstimate NONE = new BurnEstimate(0, 0, false);

    /** 点着角色的火源，带原版点着时给的时长（秒）。 */
    public enum Source {
        /** 泡在岩浆里：每刻都把着火时间顶到 15 秒。 */
        LAVA(15),
        /** 站进火、灵魂火：着火时间顶到 8 秒。 */
        FIRE(8),
        /** 看不出来源（火焰附魔的武器、火矢、火球、雷击）：按原版里除岩浆外最长的 8 秒算。 */
        UNSEEN(8);

        private final int seconds;

        Source(int seconds) {
            this.seconds = seconds;
        }
    }

    /** 身体待过的一处火源：哪种，最后一刻还待在那里是哪一刻（看不出来源的是第一次看到着火那一刻）。 */
    public record Exposure(Source source, long lastTick) {}

    /**
     * 按身体待过的火源估这把火。
     *
     * @param exposures   身体待过的火源；空表示没在烧
     * @param now         此刻（与 lastTick 同一种刻）
     * @param burningTime 着火时长属性（原版 1；火焰保护会缩短它）
     * @param health      此刻的血
     * @param absorption  此刻的黄心
     */
    public static BurnEstimate of(List<Exposure> exposures, long now, double burningTime, double health,
                                  double absorption) {
        double remaining = 0;
        for (Exposure exposure : exposures) {
            double full = exposure.source().seconds * 20.0 * Math.max(0, burningTime);
            remaining = Math.max(remaining, full - Math.max(0, now - exposure.lastTick()));
        }
        int ticks = (int) Math.ceil(remaining);
        int damage = (int) Math.ceil(ticks / 20.0);
        return new BurnEstimate(ticks, damage, damage > 0 && damage >= health + absorption);
    }

    /**
     * 逐刻记身体待过哪些火源：在岩浆里、在火里就刷新那一处的最后一刻；身上着火又没待过火源，从第一次看到着火算；
     * 火灭了、也不在火源里就全清。每刻只记一次，同一刻再记不改。
     */
    public static final class Exposures {

        private final Map<Source, Long> lastTicks = new EnumMap<>(Source.class);
        private long observedTick = Long.MIN_VALUE;

        /**
         * 记这一刻。
         *
         * @param inLava 身体泡在岩浆里
         * @param inFire 脚格或头格是火、灵魂火
         * @param onFire 身上着着火
         */
        public void observe(long tick, boolean inLava, boolean inFire, boolean onFire) {
            if (tick == observedTick) return;
            observedTick = tick;
            if (!inLava && !inFire && !onFire) {
                // 火灭了（进水、淋雨、烧完）：上一把火的来源不再算数。
                lastTicks.clear();
                return;
            }
            if (inLava) lastTicks.put(Source.LAVA, tick);
            if (inFire) lastTicks.put(Source.FIRE, tick);
            if (onFire && lastTicks.isEmpty()) lastTicks.put(Source.UNSEEN, tick);
        }

        /** 记着的火源。 */
        public List<Exposure> exposures() {
            List<Exposure> list = new ArrayList<>();
            lastTicks.forEach((source, tick) -> list.add(new Exposure(source, tick)));
            return List.copyOf(list);
        }
    }
}
