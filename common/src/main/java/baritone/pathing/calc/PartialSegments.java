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
 * 找不到整条路线时搜索给的"离目标最近的半截路"值不值得走。
 *
 * <p>半截路要挖掉目的地自己脚下的地板就不走：照着走下去，就算走到了终点，目的地也站不住了，
 * 还要先徒手挖上半天才发现到不了（实机：点着的营火挡住唯一入口时，它绕到墙外往目的地底下挖，挖了 600 刻报卡住）。
 *
 * <p>"终点比起点离目的地还远"的半截路搜索本来就不会给：起点自己就在各档排位里，换下它的节点估价必定比它小，
 * 离起点 5 格以外又没有比起点近的节点时，搜索交回的是没有路线。
 */
final class PartialSegments {

    private PartialSegments() {}

    /** 这段半截路一路上要挖开的实心格里，有没有目的地自己脚下的地板。路线要先校验过（每一步移动组装好）。 */
    static boolean digsDestinationFloor(Goal goal, IPath path, CalculationContext context) {
        return digsFloor(goal, solidCellsBroken(path, context));
    }

    /** 要挖开的格里有没有目的地脚下的地板；目标没有要恰好站进的格时一律答否。 */
    static boolean digsFloor(Goal goal, Collection<BlockPos> broken) {
        for (BlockPos cell : broken) {
            if (goal.isFloor(cell.getX(), cell.getY(), cell.getZ())) return true;
        }
        return false;
    }

    // 每一步身体要穿过的格里，此刻不是空气的就是要挖开的。
    private static List<BlockPos> solidCellsBroken(IPath path, CalculationContext context) {
        List<BlockPos> broken = new ArrayList<>();
        for (IMovement step : path.movements()) {
            if (!(step instanceof Movement movement)) continue;
            for (BlockPos cell : movement.toBreakAll()) {
                if (!context.get(cell).isAir()) broken.add(cell);
            }
        }
        return broken;
    }
}
