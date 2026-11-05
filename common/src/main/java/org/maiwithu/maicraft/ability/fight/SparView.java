// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fight;

import java.util.UUID;

import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 切磋要看的对手：认出点名的那位玩家，每刻读他的血、离多远、够不够得着我、蹲没蹲、手上拿的是不是兵器、
 * 在没在拉弓；我这一刀能打掉多少血。生产实现读客户端同步来的数据；离线测试换成替身。
 */
interface SparView {

    /** 点名的那位玩家：实体编号、UUID、玩家名。 */
    record Opponent(int entityId, UUID uuid, String name) {}

    /**
     * 他此刻的样子。
     *
     * @param health      他的血；读不到（服务端改了同步数据）为 NaN
     * @param reachesMe   他够得着我
     * @param standsDown  他蹲着，或主手拿的不是兵器
     * @param drawingBow  他在拉弓、上弩
     * @param awayX       从他指向我的水平方向（单位长度）：收手后往这边退
     */
    record Reading(double health, double distance, boolean reachesMe, boolean standsDown, boolean drawingBow,
                   double awayX, double awayZ) {}

    /** 点名的是不是一位此刻在这个世界里的玩家；不是为 null。 */
    Opponent find(TickContext context, Target target);

    /** 他此刻的样子；不在这个世界里了为 null。 */
    Reading read(TickContext context, int entityId);

    /** 我的血。 */
    double myHealth(TickContext context);

    /** 我这一刀最多能打掉多少血：按手上武器的攻击力算暴击，不扣他的护甲，宁可估高。 */
    double myBlow(TickContext context);
}
