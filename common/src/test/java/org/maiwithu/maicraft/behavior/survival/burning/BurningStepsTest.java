// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.burning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

/** 着火时地上那几步：从火里往哪迈出来，几步内走得到的水在哪、要走几步。 */
class BurningStepsTest {

    private static final BlockPos FEET = new BlockPos(0, 64, 0);

    @Test
    void climbingStraightUpAgainstTheBankGetsCloser() {
        // 顶在岸边：水平离出口中心 0.8 格不动，脚从低一格往上升，到出口脚位的距离一刻比一刻近。
        BlockPos exit = new BlockPos(2, 65, 0);
        double before = Double.MAX_VALUE;
        for (int tick = 0; tick <= 10; tick++) {
            double now = BurningSteps.toStand(exit.getX() + 0.5 - 0.8, 64.0 + tick * 0.1, exit.getZ() + 0.5, exit);
            assertTrue(now < before, "第 " + tick + " 刻 " + now + " 不比 " + before + " 近");
            before = now;
        }
        assertEquals(0.8, before, 1e-9);
    }

    // 平地：y=63 是地面，人站在 (0,64,0)。
    private static BurningCellsFake flat() {
        return new BurningCellsFake().floor(-8, -8, 8, 8, 63);
    }

    @Test
    void stepsOutOfFireOntoANeighbourThatIsNotBurning() {
        BurningCellsFake ground = flat();
        ground.fire.add(FEET);
        ground.fire.add(FEET.north());
        ground.fire.add(FEET.east());
        BlockPos step = BurningSteps.outOfFire(ground, FEET).orElseThrow();
        assertFalse(ground.fire.contains(step));
        assertTrue(step.equals(FEET.south()) || step.equals(FEET.west()));
    }

    @Test
    void noWayOutOfFireWhenEveryNeighbourBurnsOrIsBlocked() {
        BurningCellsFake ground = flat();
        ground.fire.add(FEET);
        ground.fire.add(FEET.north());
        ground.fire.add(FEET.east());
        ground.fire.add(FEET.south());
        for (int y = 64; y <= 66; y++) ground.solid.add(new BlockPos(-1, y, 0));
        assertTrue(BurningSteps.outOfFire(ground, FEET).isEmpty());
    }

    @Test
    void findsAPondTwoStepsAway() {
        // 往东两格是一格水：迈一步再迈进水里，两步。
        BurningCellsFake ground = flat();
        ground.water.add(new BlockPos(2, 64, 0));
        BurningSteps.Water water = BurningSteps.nearestWater(ground, FEET, 4).orElseThrow();
        assertEquals(new BlockPos(2, 64, 0), water.cell());
        assertEquals(2, water.steps());
        assertTrue(BurningSteps.nearestWater(ground, FEET, 1).isEmpty(), "一步之内够不着");
    }

    @Test
    void stepsDownIntoAPondSurfaceOneLevelLower() {
        // 池塘水面比脚低一格：走到池边往下一迈就进水。
        BurningCellsFake ground = flat();
        ground.solid.remove(new BlockPos(3, 63, 0));
        ground.water.add(new BlockPos(3, 63, 0));
        assertEquals(3, BurningSteps.nearestWater(ground, FEET, 4).orElseThrow().steps());
    }

    @Test
    void waterBehindAWallOrAcrossLavaIsNotWithinReach() {
        BurningCellsFake walled = flat();
        walled.water.add(new BlockPos(2, 64, 0));
        for (int z = -8; z <= 8; z++) {
            for (int y = 64; y <= 66; y++) walled.solid.add(new BlockPos(1, y, z));
        }
        assertTrue(BurningSteps.nearestWater(walled, FEET, 4).isEmpty(), "墙后的水走不过去");

        BurningCellsFake across = flat();
        across.water.add(new BlockPos(3, 64, 0));
        for (int z = -8; z <= 8; z++) across.lava.add(new BlockPos(1, 64, z));
        for (int z = -8; z <= 8; z++) across.lava.add(new BlockPos(2, 64, z));
        assertTrue(BurningSteps.nearestWater(across, FEET, 4).isEmpty(), "不踩着岩浆过去");
    }
}
