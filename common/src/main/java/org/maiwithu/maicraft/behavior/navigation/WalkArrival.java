// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.behavior.navigation.calc.NavGoal;

/**
 * 到达判断：站着算到的纯函数。目标格判断通过、身体落地才算到；
 * 跳到最高点、空中经过都不算。宽松的范围目标允许水里漂进范围（泅渡到达），
 * 要站进一格、站到方块旁或采矿站位的目标必须站在干地上——泡在浅水里脚踩着地
 * 也不是干地：到达与乘船腿的出发点按同一份干地事实判，不然走路把水里判成到了、
 * 船又拒绝从水里出发，同一片岸两边互相矛盾。要不要站实由目标自己回答
 * （{@link NavGoal#acceptsWaterArrival()}），这里不按目标类型分别写一遍。
 */
public final class WalkArrival {

    private WalkArrival() {}

    /**
     * @param goal     编译前的导航目标，到达条件以它的判断为准
     * @param feet     这一刻脚所在的方块格
     * @param onGround 身体是否已落地站实
     * @param inWater  身体是否在水中（泅渡中或泡在浅水里）
     */
    public static boolean reached(NavGoal goal, BlockPos feet, boolean onGround, boolean inWater) {
        if (!goal.isAt(feet)) {
            return false;
        }
        // 宽松目标：落地或水里漂进范围都算到。要求站实的目标必须站在干地上：
        // 浅水里脚踩着地、身上还是湿的，站在那儿与"脚下是水"是同一个事实。
        return goal.acceptsWaterArrival() ? (onGround || inWater) : (onGround && !inWater);
    }
}
