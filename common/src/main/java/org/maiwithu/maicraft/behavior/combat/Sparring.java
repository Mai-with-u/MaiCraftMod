// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import java.util.Optional;

/**
 * 切磋什么时候收手：谁到了停手线、再砍一刀他就倒了、他停手了、他走开了、半分钟谁都没打中谁。纯函数。
 *
 * <p>停手线是血量点（一颗心 2 点，不算黄心）；0 是打到有人倒下，这时不管"再砍一刀他就倒了"。
 * 他停手：5 秒没打中我，并且退到他够不着我的地方、蹲着、或主手换成不是兵器的东西。读不到他的血时只看我这边。
 */
public final class Sparring {

    /** 他多久（刻）没打中我才可能是停手了：5 秒。 */
    public static final int QUIET_TICKS = 100;
    /** 谁都没打中谁多久（刻）就收手：30 秒。 */
    public static final int NO_HITS_TICKS = 600;
    /** 他走开多远（格）就收手。 */
    public static final double LEFT_BLOCKS = 12;
    /** 默认停手线：6 点（三颗心）。 */
    public static final int DEFAULT_STOP_AT = 6;

    private Sparring() {}

    /** 怎么收手的：写进结果的 `ended_by`。 */
    public enum Ending {
        THEY_STOPPED("they_stopped", "他停手了"),
        THEY_AT_LINE("they_at_line", "他到了停手线"),
        ME_AT_LINE("me_at_line", "我到了停手线"),
        FINISHING_BLOW("finishing_blow", "再砍一刀他就倒了"),
        THEY_LEFT("they_left", "他走开了"),
        NO_HITS("no_hits", "半分钟谁都没打中谁"),
        CANCELLED("cancelled", "被取消了");

        public final String value;
        public final String words;

        Ending(String value, String words) {
            this.value = value;
            this.words = words;
        }
    }

    /**
     * 这一刻的场面。
     *
     * @param theirHealth  他的血；读不到为 NaN
     * @param distance     他离我多远（格）
     * @param theyReachMe  他够得着我
     * @param theyStandDown 他蹲着，或主手拿的不是兵器
     * @param lastTheyHit  他最近一次打中我的刻；开打以来没打中过为开打那刻
     * @param lastAnyHit   谁最近一次打中谁的刻；没有为开打那刻
     * @param myBlow       我这一刀能打掉他多少血（按手上武器，不扣护甲，宁可估高）
     */
    public record Moment(long now, double myHealth, double theirHealth, double distance, boolean theyReachMe,
                         boolean theyStandDown, long lastTheyHit, long lastAnyHit, double myBlow) {}

    /** 该不该收手、为什么；接着打为空。 */
    public static Optional<Ending> ending(Moment m, int stopAt) {
        boolean known = !Double.isNaN(m.theirHealth());
        if (m.distance() > LEFT_BLOCKS) return Optional.of(Ending.THEY_LEFT);
        if (m.myHealth() <= stopAt) return Optional.of(Ending.ME_AT_LINE);
        if (known && m.theirHealth() <= stopAt) return Optional.of(Ending.THEY_AT_LINE);
        if (known && stopAt > 0 && m.theirHealth() - m.myBlow() <= 0) return Optional.of(Ending.FINISHING_BLOW);
        if (m.now() - m.lastTheyHit() >= QUIET_TICKS && (!m.theyReachMe() || m.theyStandDown())) {
            return Optional.of(Ending.THEY_STOPPED);
        }
        if (m.now() - m.lastAnyHit() >= NO_HITS_TICKS) return Optional.of(Ending.NO_HITS);
        return Optional.empty();
    }
}
