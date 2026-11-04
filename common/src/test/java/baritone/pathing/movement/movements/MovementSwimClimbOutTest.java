// SPDX-License-Identifier: GPL-3.0-only
package baritone.pathing.movement.movements;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

/**
 * 从水面攀上一格高岸沿算不算一步：一格深的水池，岸沿那块比水面高出约 1.1 格，算路时这一步算得出代价；
 * 人还没浮到水面、岸顶上面被挡住、岸沿高两格都不算。
 */
class MovementSwimClimbOutTest {

    /** 只有水和实心方块的小世界：其余都是空气。 */
    private static final class World implements MovementSwimClimbOut.Shore {
        final Set<BlockPos> water = new HashSet<>();
        final Set<BlockPos> solid = new HashSet<>();

        @Override public boolean water(int x, int y, int z) {
            return water.contains(new BlockPos(x, y, z));
        }

        @Override public boolean standOn(int x, int y, int z) {
            return solid.contains(new BlockPos(x, y, z));
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
    }

    @Test
    void aOneBlockRimAboveTheWaterSurfaceIsAClimbableStep() {
        // 岸顶比水面（y=63 那格水的顶面约 63.9）高出约 1.1 格：算路时这一步算得出代价。
        assertTrue(MovementSwimClimbOut.climbable(new World().poolWithRimEast(), 0, 63, 0, 1, 0));
    }

    @Test
    void notYetAtTheSurfaceOrABlockedTopIsNotAStep() {
        World deeper = new World().poolWithRimEast();
        deeper.water.add(new BlockPos(0, 64, 0));
        assertFalse(MovementSwimClimbOut.climbable(deeper, 0, 63, 0, 1, 0), "头上那格还是水：先浮上去");

        World capped = new World().poolWithRimEast();
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
