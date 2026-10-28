// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.settle;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.travel.planning.TravelDestination;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 出行的下掘分段判断：目的地在脚下深处、一段走路半途收场时，只要这一段真的往下挖深了，
 * 就从挖到的地方重开一段走路接着算路接着挖——下掘靠一路挖天然方块推进，一段算一段；
 * 有一段没能比上一段更深，就带着挖到哪、还差多深如实收场。纯函数，离线测试直接喂事实。
 *
 * <p>与 {@link ClimbSegments} 同一件事的另一个方向：长爬升拆给往上挖楼梯的动作，
 * 长下掘拆给重新上路的走路本身，都不把一段的收场当成整趟到不了。
 */
public final class DescentSegments {

    private DescentSegments() {}

    /** 目的地在脚下下方还有多深；高度没核实、目的地不在下方时给 0。 */
    public static int remaining(TravelDestination destination, BlockPos feet) {
        if (destination == null || feet == null || !destination.heightConfirmed()) {
            return 0;
        }
        return Math.max(feet.getY() - destination.position().y(), 0);
    }

    /** 目的地核实过高度、还在脚下没到，就值得从原地重开一段接着挖；段数上限由拆段的任务自己记着。 */
    public static boolean needsLeg(TravelDestination destination, BlockPos feet) {
        return remaining(destination, feet) > 0;
    }

    /** 这一段比出发时挖得更深（至少低一格）才算有进展；原地重走不给自己续命。 */
    public static boolean legDugDeeper(BlockPos legStart, BlockPos feet) {
        return legStart != null && feet != null && feet.getY() < legStart.getY();
    }

    /** 拆了下掘段还是到不了：如实交代拆了几段、最深挖到哪、还差多深；原来的收尾说明只取结论句，不整段重抄。 */
    public static Problem stillShort(TravelDestination destination, BlockPos feet, int legs, Problem underlying) {
        return Problem.of(Problem.Kind.UNREACHABLE,
                "下掘分了 " + legs + " 段，挖到 (" + feet.getX() + ", " + feet.getY() + ", " + feet.getZ() + ")"
                        + "，离目的地还深 " + remaining(destination, feet) + " 格，还是走不到；" + underlying.headline(),
                underlying.suggestion());
    }
}
