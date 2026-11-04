// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.burning;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 着火时在地上迈的那几步：站进火里往哪一格迈出来，身上着火时几步内走得到的最近的水在哪。
 * 走的是能站的地面格（脚格、头格没碰撞，不是岩浆、火，脚下踩得住），一步上下最多一格。纯函数，离线可测。
 */
public final class BurningSteps {

    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    /** 一步只看同层、高一格、低一格，同层的先看。 */
    private static final int[] STEP_HEIGHTS = {0, 1, -1};

    private BurningSteps() {}

    /** 在地上找落脚、找水要看的格子事实。 */
    public interface Cells extends LavaExits.Cells {
        /** 这一格是水，身体进得去（没碰撞）。 */
        boolean water(BlockPos cell);
    }

    /**
     * 身体（脚底那一点）离一格脚位（格底中心）有多远，上下也算：贴着岸往上蹿时水平不动、脚在升高，也是在靠近。
     */
    public static double toStand(double x, double y, double z, BlockPos cell) {
        double dx = cell.getX() + 0.5 - x;
        double dy = cell.getY() - y;
        double dz = cell.getZ() + 0.5 - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * 几步内走得到的水：哪一格、要走几步（迈进水里那一步也算）。
     *
     * @param cell  要走进去的那格水
     * @param steps 从脚下起要迈几步
     */
    public record Water(BlockPos cell, int steps) {}

    /** 这一格能不能站着落脚：脚格、头格都没碰撞，不是岩浆、火，脚下踩得住。 */
    public static boolean standable(Cells cells, BlockPos feet) {
        BlockPos head = feet.above();
        return cells.open(feet) && cells.open(head) && !cells.lava(feet) && !cells.lava(head)
                && !cells.fire(feet) && !cells.fire(head) && cells.firm(feet.below());
    }

    /** 站进火里时往哪一格迈出来：四个邻格里能站着落脚的那一格，同层的先；一格都没有时给空。 */
    public static Optional<BlockPos> outOfFire(Cells cells, BlockPos feet) {
        for (int dy : STEP_HEIGHTS) {
            for (Direction side : HORIZONTAL) {
                BlockPos next = feet.relative(side).offset(0, dy, 0);
                if (standable(cells, next)) return Optional.of(next);
            }
        }
        return Optional.empty();
    }

    /**
     * 最多 maxSteps 步内走得到的最近的水：沿能站着落脚的地面一步步往外找，碰到同层、高一格或低一格是水的邻格就是它。
     * 步数一样时同层的先、按找到的先后。没有给空。
     */
    public static Optional<Water> nearestWater(Cells cells, BlockPos feet, int maxSteps) {
        Map<BlockPos, Integer> steps = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        steps.put(feet, 0);
        queue.add(feet);
        while (!queue.isEmpty()) {
            BlockPos at = queue.poll();
            int taken = steps.get(at) + 1;
            if (taken > maxSteps) continue;
            for (Direction side : HORIZONTAL) {
                for (int dy : STEP_HEIGHTS) {
                    BlockPos next = at.relative(side).offset(0, dy, 0);
                    if (steps.containsKey(next)) continue;
                    if (cells.water(next)) return Optional.of(new Water(next, taken));
                    if (standable(cells, next)) {
                        steps.put(next, taken);
                        queue.add(next);
                    }
                }
            }
        }
        return Optional.empty();
    }
}
