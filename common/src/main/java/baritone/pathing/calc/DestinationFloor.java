// SPDX-License-Identifier: GPL-3.0-only
package baritone.pathing.calc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.movement.IMovement;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import net.minecraft.core.BlockPos;

/**
 * 目的地自己脚下的地板：一条路线走完时它被挖掉了、又没垫回去，这条路线就不走。
 *
 * <p>真人不会为了够到一个地方，先把那个地方的地板拆了。算路时每一步都按开工前的世界算，看不出前面几步已经把地板挖掉：
 * 实机里点着的营火挡住走廊唯一的入口，引擎交回一条"绕到墙外往下挖、从墙底下钻到目的地正下方、挖穿地板、
 * 再踩着这块地板爬进目的地"的整条路线，照着走只会徒手挖半分钟然后卡住。找不到整条路线时给的半截路要挖穿地板也一样白挖。
 *
 * <p>身上没有能垫回去的方块时，算路本身就不挖这一格（{@code CalculationContext} 把它当成挖不得的格），引擎直接去找别的进法；
 * 这里是算完之后的核对：有能垫的方块时挖穿了，要看后面哪一步往这格垫块，垫回去了（挖穿后垫着方块爬上来）路线照走，
 * 没垫回去的（比如斜着走进目的地，这一步不垫块）整条不走。
 */
final class DestinationFloor {

    private DestinationFloor() {}

    /**
     * 路线里的一步：要挖开的实心格，和这一步站上去之前缺了会往哪一格垫块（不垫块的走法为空）。
     *
     * @param broken 要挖开的实心格
     * @param placed 缺了会垫块的格，可为空
     */
    record Step(Collection<BlockPos> broken, BlockPos placed) {}

    /** 这条路线（已校验，每一步移动组装好）走完时，目的地脚下的地板是不是被挖掉了没垫回去。 */
    static boolean leftDug(Goal goal, IPath path, CalculationContext context) {
        return leftDug(goal, steps(path, context), context.hasThrowaway);
    }

    /** 按顺序走完这些步，目的地脚下的地板是不是挖掉了没垫回去；目标没有要恰好站进的格时一律答否。 */
    static boolean leftDug(Goal goal, List<Step> steps, boolean canPlace) {
        boolean dug = false;
        for (Step step : steps) {
            // 先挖后垫：这一步挖开的格里有地板就算挖掉了；这一步站上去前要往地板那格垫块、身上又有能垫的，就补回来了。
            for (BlockPos cell : step.broken()) {
                if (goal.isFloor(cell.getX(), cell.getY(), cell.getZ())) dug = true;
            }
            BlockPos placed = step.placed();
            if (dug && canPlace && placed != null && goal.isFloor(placed.getX(), placed.getY(), placed.getZ())) {
                dug = false;
            }
        }
        return dug;
    }

    // 每一步身体要穿过的格里，此刻不是空气的就是要挖开的；垫块的格照这一步的走法给，到时缺了才垫。
    private static List<Step> steps(IPath path, CalculationContext context) {
        List<Step> steps = new ArrayList<>();
        for (IMovement step : path.movements()) {
            if (!(step instanceof Movement movement)) continue;
            List<BlockPos> broken = new ArrayList<>();
            for (BlockPos cell : movement.toBreakAll()) {
                if (!context.get(cell).isAir()) broken.add(cell);
            }
            steps.add(new Step(broken, movement.positionToPlace()));
        }
        return steps;
    }
}
