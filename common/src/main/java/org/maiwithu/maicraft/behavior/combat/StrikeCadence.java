// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

/**
 * 出刀节拍：冷却中这一刻该不该往里走。纯函数，近身打的走位每刻问一次。
 *
 * <p>会打的人掐着攻击条快满的时候往前一步砍，砍完退出对方够得着的地方等下一轮。打玩家时两边手一样长，
 * 没有"我够得着他够不着"的缝：冷却带在他够得着外（中心距约 3.65 格起），出手带在我稳稳够得着内（约 3.15 格内），
 * 中间差半格到一格多，走路要两到六刻。条满了才起步，剑一轮 12 刻里白等这几刻，他一步上来先砍到。
 * 所以离下一刀的刻数不超过"走到出手带外沿要的刻数 + 1"就提前往里走，到跟前正好够格。打怪时两条带
 * 本来重合在"我够得着它、它够不着我"的那条缝里，差的距离多半是零，走位几乎不变。
 *
 * <p>走过去的速度按走路算，不按疾跑：疾跑会吃掉暴击。
 */
final class StrikeCadence {

    /** 平地走路每刻走多远（格）：原版玩家移动速度属性 0.1 走出来约 0.22 格/刻。 */
    static final double WALK_PER_TICK = 0.22;
    /** 移动速度属性折成平地走路每刻多少格的倍数（属性 0.1 → 约 0.22 格/刻）。 */
    static final double WALK_PER_SPEED = 2.2;
    /** 走得再慢也按这么快算（格/刻），免得除出一个走不完的刻数。 */
    private static final double SLOWEST = 0.05;
    /** 到跟前多留一刻：最后那一步落脚、转镜头。 */
    static final int ARRIVAL_SLACK = 1;

    private StrikeCadence() {}

    /**
     * 这一刻该不该往出手带里走。
     *
     * @param ticksToStrike 离下一刀还差几刻（攻击条、主手拿没拿稳、它身上的受击红闪取大的）
     * @param gap           离出手带外沿还差多远（格）；已在带里为 0 或负
     * @param perTick       这会儿每刻走多远（格）
     */
    static boolean stepIn(int ticksToStrike, double gap, double perTick) {
        if (ticksToStrike <= 0) return true;
        return ticksToStrike <= walkTicks(gap, perTick) + ARRIVAL_SLACK;
    }

    /** 走过这段差距要几刻；已在带里为 0。 */
    static int walkTicks(double gap, double perTick) {
        if (gap <= 0) return 0;
        return (int) Math.ceil(gap / Math.max(SLOWEST, perTick));
    }
}
