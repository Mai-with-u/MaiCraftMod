// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.cook;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.permission.FarmlandIrrigation;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsOwnHome;
import org.maiwithu.maicraft.behavior.permission.ReadsStructureProtection;
import org.maiwithu.maicraft.behavior.permission.SeesVillagers;
import org.maiwithu.maicraft.behavior.permission.StructureProtection;
import org.maiwithu.maicraft.behavior.worldmemory.RememberedRegion;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/** 流体转化选水格的保护判断测试：避开圈地、建筑水池、机器洗格水与农田非无限灌溉水源。 */
class LiveTransformSitesProtectionTest {

    private static final String DIM = "minecraft:overworld";

    @Test
    void 流体转化选水格避开圈地建筑机器与农田灌溉水_野外静水放行() {
        WorldPosition regionPool = new WorldPosition(1, 64, 1, DIM);
        WorldPosition machinePool = new WorldPosition(2, 64, 2, DIM);
        WorldPosition farmPool = new WorldPosition(3, 64, 3, DIM);
        WorldPosition wildPool = new WorldPosition(4, 64, 4, DIM);

        ReadsStructureProtection structures = pos -> pos.equals(machinePool)
                ? StructureProtection.machine("鼓风洗矿机") : StructureProtection.NONE;
        FarmlandIrrigation.WaterGrid farmGrid = new FarmlandIrrigation.WaterGrid() {
            @Override public String dimension() { return DIM; }
            @Override public boolean isWaterSource(BlockPos pos) { return pos.equals(new BlockPos(3, 64, 3)); }
            @Override public boolean isSolidFloor(BlockPos pos) { return true; }
            @Override public boolean isFarmland(BlockPos pos) { return pos.equals(new BlockPos(5, 64, 3)); }
        };
        Protection protection = new Protection((d, x, y, z) -> Optional.empty(),
                () -> List.of(new RememberedRegion("邻居花园", regionPool, 1)),
                name -> Optional.empty(), "self", SeesVillagers.NOBODY, ReadsOwnHome.NOWHERE, id -> id,
                structures, farmGrid);

        assertFalse(LiveTransformSites.isAllowedPool(protection, regionPool));
        assertFalse(LiveTransformSites.isAllowedPool(protection, machinePool));
        assertFalse(LiveTransformSites.isAllowedPool(protection, farmPool));
        assertTrue(LiveTransformSites.isAllowedPool(protection, wildPool));
    }
}
