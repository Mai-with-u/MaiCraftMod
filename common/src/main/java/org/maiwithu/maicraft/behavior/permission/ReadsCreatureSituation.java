// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.Optional;
import java.util.UUID;

/**
 * 读生物处境的只读接缝：一只生物是玩家的吗、敌对吗、有名字吗、被谁驯服了、拴着绳吗、在围栏里吗。
 *
 * <p>"围栏里"需要一个统一的看法（脚下与四周是什么、圈了多大致算圈养），游戏接口层还没有这个视图，
 * 先以这个接缝留位，实现留给游戏接口层轨；实现到位之前，行为层拿不到处境就按受保护处理。
 */
public interface ReadsCreatureSituation {

    /** 一只生物的处境；认不出（走远了、没了）返回空。 */
    Optional<CreatureSituation> situationOf(UUID entityId);

    /**
     * 一只可驯服生物被谁驯服了：原版狼、猫、马、鹦鹉与继承原版可驯服生物的模组生物（车万女仆的女仆）都一样认。
     * 角色自己驯服的是角色自己的；自家人驯服的照旧按别人的算（自家人只放行取用存储）。
     */
    enum TamedBy {
        /** 驯服者就是角色。 */
        SELF("你的"),
        /** 驯服者是所有者列的自家人。 */
        TRUSTED("自家人的"),
        /** 驯服者是别的玩家。 */
        SOMEONE_ELSE("别人的"),
        /** 没被驯服，或不是可驯服的生物。 */
        NOBODY(null);

        private final String word;

        TamedBy(String word) {
            this.word = word;
        }

        /** observe 实体 traits 里"驯服"一项的写法；没驯服给 null，不写这一项。 */
        public String word() {
            return word;
        }

        /**
         * 按驯服者编号与角色、自家人名单比对：纯函数，离线测试直接给编号。
         *
         * @param owner   生物同步给客户端的驯服者编号；没驯服给 null
         * @param self    角色自己的编号
         * @param trusted 自家人名单
         */
        public static TamedBy of(UUID owner, UUID self, TrustedPlayers trusted) {
            if (owner == null) return NOBODY;
            if (owner.equals(self)) return SELF;
            return trusted.includes(owner.toString()) ? TRUSTED : SOMEONE_ELSE;
        }
    }

    /**
     * 一只生物的处境。
     *
     * @param player      是不是一位玩家；对玩家动手不归 fight 之外的任何许可管
     * @param hostileMob  是不是敌对生物（僵尸、骷髅这类）
     * @param named       有没有起过名字（名牌、命名牌）
     * @param tamed       有没有被驯服（不管是谁驯服的）
     * @param leashed     有没有拴着绳
     * @param inEnclosure 在不在围栏、栏圈这类圈养设施里（围栏里的牲畜是别人的）
     * @param inVehicle   是不是被放在船或矿车里
     * @param tamedBy     被谁驯服了
     */
    record CreatureSituation(boolean player, boolean hostileMob,
            boolean named, boolean tamed, boolean leashed, boolean inEnclosure, boolean inVehicle, TamedBy tamedBy) {

        public CreatureSituation {
            // 只说了"驯服了"没说是谁的，按别人的算：拿不准就不当自己的。
            if (tamedBy == null) tamedBy = tamed ? TamedBy.SOMEONE_ELSE : TamedBy.NOBODY;
            tamed = tamed || tamedBy != TamedBy.NOBODY;
        }

        public CreatureSituation(boolean player, boolean hostileMob, boolean named, boolean tamed, boolean leashed,
                                 boolean inEnclosure, boolean inVehicle) {
            this(player, hostileMob, named, tamed, leashed, inEnclosure, inVehicle, null);
        }

        public CreatureSituation(boolean player, boolean hostileMob, boolean named, boolean tamed, boolean leashed,
                                 boolean inEnclosure) {
            this(player, hostileMob, named, tamed, leashed, inEnclosure, false, null);
        }

        /**
         * 有没有哪条占着"这是有主的"的理由：有名字、被驯服、拴着绳、圈养着、被放在船或矿车里（玩家运牲口才这么放），
         * 占上一条就不伤它。角色自己驯服的也算有主：打它、为掉落杀它照样要点名放开。
         */
        public boolean bondedToSomeone() {
            return named || tamed || leashed || inEnclosure || inVehicle;
        }

        /**
         * 对它用东西、喂、骑、抱、开它的界面、存取它身上的东西时，它算不算别人的：角色自己驯服的不算，
         * 它有名字、拴着绳、在围栏里也一样（那多半是角色自己弄的）；其余照 {@link #bondedToSomeone}。
         */
        public boolean someoneElses() {
            return tamedBy != TamedBy.SELF && bondedToSomeone();
        }
    }
}
