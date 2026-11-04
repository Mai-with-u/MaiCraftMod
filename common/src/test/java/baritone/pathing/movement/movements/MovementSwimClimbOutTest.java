// SPDX-License-Identifier: GPL-3.0-only
package baritone.pathing.movement.movements;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

/**
 * 从水面攀上岸沿算不算一步：脚出水后最多蹿到水面以上约 1 格，实心方块的岸沿（顶比水面高约 1.1 格）上不去，
 * 开发客户端逐刻实测每轮最高到水面以上 1.025 格就落回去；下半台阶这类矮一截的岸沿算得出代价，正好在 1 格上的也算；
 * 人还没浮到水面、岸顶上面被挡住、岸沿高两格都不算。
 */
class MovementSwimClimbOutTest {

    /** 只有水和实心方块的小世界：其余都是空气。 */
    private static final class World implements MovementSwimClimbOut.Shore {
        final Set<BlockPos> water = new HashSet<>();
        final Set<BlockPos> solid = new HashSet<>();
        /** 不是整块的方块顶面离格底多高；没写的实心方块按整块 1 算。 */
        final Map<BlockPos, Double> tops = new HashMap<>();

        @Override public boolean water(int x, int y, int z) {
            return water.contains(new BlockPos(x, y, z));
        }

        // 水都是满的水源：水面在格底以上 8/9 格。
        @Override public double waterSurface(int x, int y, int z) {
            return water(x, y, z) ? 8.0 / 9.0 : 0;
        }

        @Override public boolean standOn(int x, int y, int z) {
            return solid.contains(new BlockPos(x, y, z));
        }

        @Override public double topOf(int x, int y, int z) {
            BlockPos at = new BlockPos(x, y, z);
            return solid.contains(at) ? tops.getOrDefault(at, 1.0) : 0;
        }

        @Override public boolean passable(int x, int y, int z) {
            BlockPos at = new BlockPos(x, y, z);
            return !solid.contains(at) && !water.contains(at);
        }

        // 一格深的水池：水在 y=63，东边岸沿是 y=64 那一块，岸顶在 y=65。
        World poolWithRimEast() {
            water.add(new BlockPos(0, 63, 0));
            solid.add(new BlockPos(0, 62, 0));
            solid.add(new BlockPos(1, 63, 0));
            solid.add(new BlockPos(1, 64, 0));
            return this;
        }

        // 同一个水池，岸沿那块顶面只有 top 高（下半台阶是 0.5）。
        World poolWithLowRimEast(double top) {
            poolWithRimEast();
            tops.put(new BlockPos(1, 64, 0), top);
            return this;
        }
    }

    @Test
    void aFullBlockRimAboveTheWaterSurfaceCannotBeClimbed() {
        // 岸顶比水面（y=63 那格水的顶面约 63.9）高出约 1.1 格：脚出水后最高只到水面以上约 1 格，上不去，不算一步。
        assertFalse(MovementSwimClimbOut.climbable(new World().poolWithRimEast(), 0, 63, 0, 1, 0));
    }

    @Test
    void aRimWithinOneBlockOfTheSurfaceIsStillAStep() {
        // 下半台阶的岸沿：顶比水面高 0.61 格，蹿得上去。
        assertTrue(MovementSwimClimbOut.climbable(new World().poolWithLowRimEast(0.5), 0, 63, 0, 1, 0));
        // 岸顶正好比水面高 1 格：还算；再高一点点就不算。
        assertTrue(MovementSwimClimbOut.climbable(new World().poolWithLowRimEast(8.0 / 9.0), 0, 63, 0, 1, 0));
        assertFalse(MovementSwimClimbOut.climbable(new World().poolWithLowRimEast(0.9), 0, 63, 0, 1, 0));
    }

    @Test
    void notYetAtTheSurfaceOrABlockedTopIsNotAStep() {
        World deeper = new World().poolWithLowRimEast(0.5);
        deeper.water.add(new BlockPos(0, 64, 0));
        assertFalse(MovementSwimClimbOut.climbable(deeper, 0, 63, 0, 1, 0), "头上那格还是水：先浮上去");

        World capped = new World().poolWithLowRimEast(0.5);
        capped.solid.add(new BlockPos(1, 66, 0));
        assertFalse(MovementSwimClimbOut.climbable(capped, 0, 63, 0, 1, 0), "岸顶上只空一格，人站不进去");
    }

    @Test
    void aTwoBlockRimIsNotAStep() {
        // 岸沿高两格：y+1 那格是空的、站不住，这一步不算。
        World tall = new World();
        tall.water.add(new BlockPos(0, 63, 0));
        tall.solid.add(new BlockPos(1, 63, 0));
        tall.solid.add(new BlockPos(1, 64, 0));
        tall.solid.add(new BlockPos(1, 65, 0));
        assertFalse(MovementSwimClimbOut.climbable(tall, 0, 63, 0, 1, 0));
    }
}
