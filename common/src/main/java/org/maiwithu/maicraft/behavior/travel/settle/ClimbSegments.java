// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.settle;

import java.util.function.Predicate;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.travel.planning.TravelDestination;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 出行的爬升分段判断：目的地在头顶高处、一次路线规划算不收敛时，把爬升拆成几段——
 * 先挖楼梯爬到中途落脚点，再从新落脚点接着算下一段路。纯函数，离线测试直接喂事实。
 *
 * <p>实机 008 存档的坑-台地地形里，四十级上下的爬升走一段算一段能爬上去，八十八级的直爬
 * 长时间规划不出、空转收场。分段之后每一段都落在规划接得住的范围内；拆满还没到，就带着
 * 已爬到的高度如实收场。
 */
public final class ClimbSegments {

    /** 目的地比脚下高出这么多级以上，一次规划常常算不收敛：把爬升拆出去。实机 008：四十级上下以内走一段算一段爬得动。 */
    static final int SPLIT_ABOVE = 45;
    /** 一段爬升爬到离目的地还差这么多级以内就停，剩下的交回走路：留出余地，不贴着规划上限交接。 */
    static final int HAND_OVER_REMAINING = 30;
    /** 最多拆几段爬升：拆满还没到就如实收场，不在崖壁上无休止地挖。 */
    static final int MAX_LEGS = 3;

    private ClimbSegments() {}

    /** 目的地在脚下上方还有多少级；高度没核实、目的地不在上方时给 0。 */
    public static int remaining(TravelDestination destination, BlockPos feet) {
        if (destination == null || feet == null || !destination.heightConfirmed()) {
            return 0;
        }
        return Math.max(destination.position().y() - feet.getY(), 0);
    }

    /** 要不要拆出一段爬升：目的地核实过高度、还在头顶高出一次规划盖得住的范围。 */
    public static boolean needsLeg(TravelDestination destination, BlockPos feet) {
        return remaining(destination, feet) > SPLIT_ABOVE;
    }

    /** 一段爬升的停点判断：站到这一格（脚的位置）离目的地近到走路接得住，就算这一段爬完了。 */
    public static Predicate<BlockPos> stopWhenClose(TravelDestination destination) {
        return feet -> remaining(destination, feet) <= HAND_OVER_REMAINING;
    }

    /** 拆了爬升段还是到不了：如实交代拆了几段、爬到多高、还差几级；原来的收尾说明只取结论句，不整段重抄。 */
    public static Problem stillShort(TravelDestination destination, BlockPos feet, int legs, Problem underlying) {
        return Problem.of(Problem.Kind.UNREACHABLE,
                "分段爬了 " + legs + " 段楼梯，爬到 (" + feet.getX() + ", " + feet.getY() + ", " + feet.getZ() + ")"
                        + "，离目的地还高 " + remaining(destination, feet) + " 级，还是走不到；" + underlying.headline(),
                underlying.suggestion());
    }
}
