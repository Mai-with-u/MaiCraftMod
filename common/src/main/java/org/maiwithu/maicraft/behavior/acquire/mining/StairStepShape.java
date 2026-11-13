// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

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
}
