// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 挖楼梯或水平通道时每一级的格子几何形状：哪几格要先挖通、迈下去落脚在哪一格、挖开后四周会露出哪几格。
 */
final class StairStepShape {

    /** 往哪边挖、这一步是往下还是横着。 */
    record Heading(Direction heading, boolean down) {}

    private StairStepShape() {}

    /** 往下的一级要清三格（齐头、齐脚、脚下）；横着的一步只清两格（齐头、齐脚）。都从上往下挖。 */
    static List<BlockPos> stepCells(BlockPos feet, Direction heading, boolean down) {
        BlockPos ahead = feet.relative(heading);
        return down ? List.of(ahead.above(), ahead, ahead.below()) : List.of(ahead.above(), ahead);
    }

    /** 挖通这一级后站到哪：往下是前方脚下那格，横着是前方齐脚那格。 */
    static BlockPos nextFeet(BlockPos feet, Heading way) {
        BlockPos ahead = feet.relative(way.heading());
        return way.down() ? ahead.below() : ahead;
    }

    /** 挖开这一级会露出来的四周：要挖那几格的水平邻格、最上面那格的头顶、下一级的脚下；自己站的这一列是来路，不算。 */
    static List<BlockPos> surroundings(BlockPos feet, Heading way) {
        List<BlockPos> cells = stepCells(feet, way.heading(), way.down());
        Set<BlockPos> around = new LinkedHashSet<>();
        for (BlockPos cell : cells) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                around.add(cell.relative(side));
            }
        }
        around.add(cells.getFirst().above());
        around.add(nextFeet(feet, way).below());
        cells.forEach(around::remove);
        around.remove(feet.below());
        around.remove(feet);
        around.remove(feet.above());
        return List.copyOf(around);
    }

    /** 这一级试探方向的先后：首级从朝向起顺时针试四面，之后保持原向优先再试左右与反方向。 */
    static List<Direction> headingOrder(Direction heading, Direction facing) {
        List<Direction> order = new ArrayList<>(4);
        if (heading == null) {
            Direction cur = facing;
            for (int turn = 0; turn < 4; turn++) {
                order.add(cur);
                cur = cur.getClockWise();
            }
        } else {
            order.add(heading);
            order.add(heading.getCounterClockWise());
            order.add(heading.getClockWise());
            order.add(heading.getOpposite());
        }
        return order;
    }

    /** 四面上下任一面紧挨水或岩浆返回真：连锁一下挖开会灌进通道。 */
    static boolean touchesFluid(ClientStairsDown.Site site, TickContext context, BlockPos cell) {
        for (Direction side : Direction.values()) {
            if (site.ground(context, cell.relative(side)) == ClientStairsDown.Ground.FLUID) return true;
        }
        return false;
    }

    /** 横着再往前一格是不是现成的安全通道：齐头齐脚空、脚下踩得住不烫脚、四周和头顶不挨流体。 */
    static boolean openStretch(ClientStairsDown.Site site, TickContext context, BlockPos at) {
        if (site.ground(context, at) != ClientStairsDown.Ground.OPEN
                || site.ground(context, at.above()) != ClientStairsDown.Ground.OPEN) {
            return false;
        }
        ClientStairsDown.Ground floor = site.ground(context, at.below());
        if (floor == ClientStairsDown.Ground.OPEN || floor == ClientStairsDown.Ground.FLUID
                || floor == ClientStairsDown.Ground.UNBREAKABLE) {
            return false;
        }
        if (site.hurtsToStandOn(context, at.below())) return false;
        for (BlockPos cell : List.of(at, at.above())) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                if (site.ground(context, cell.relative(side)) == ClientStairsDown.Ground.FLUID) return false;
            }
        }
        return site.ground(context, at.above(2)) != ClientStairsDown.Ground.FLUID;
    }

    /** 走步失败后检查下一级落脚格、头顶格或本级剖面是否又被落沙/沙砾回填成可挖方块。 */
    static boolean stepRefilled(ClientStairsDown.Site site, TickContext context, BlockPos stepTarget,
            BlockPos feet, Direction heading, boolean down) {
        if (stepTarget != null && (site.ground(context, stepTarget) == ClientStairsDown.Ground.DIGGABLE
                || site.ground(context, stepTarget.above()) == ClientStairsDown.Ground.DIGGABLE)) {
            return true;
        }
        if (feet == null || heading == null) return false;
        for (BlockPos cell : stepCells(feet, heading, down)) {
            if (site.ground(context, cell) == ClientStairsDown.Ground.DIGGABLE) return true;
        }
        return false;
    }
}
