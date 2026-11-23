// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.containers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.permission.FarmlandIrrigation;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsOwnHome;
import org.maiwithu.maicraft.behavior.permission.ReadsStructureProtection;
import org.maiwithu.maicraft.behavior.permission.SeesVillagers;
import org.maiwithu.maicraft.behavior.permission.StructureProtection;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/** 箱顶挖盖结构保护测试：箱子上方压着建筑墙体或机器格时不自动挖盖，箱子本身取存仍放行。 */
class ContainerLidProtectionTest {

    private static final String DIM = "minecraft:overworld";

    @Test
    void 箱顶压着建筑或机器格标为不可自动挖盖_野外泥土照常可挖盖() {
        WorldPosition buildingRoof = new WorldPosition(10, 65, 10, DIM);
        WorldPosition machineCasing = new WorldPosition(20, 65, 20, DIM);
        WorldPosition chestUnderRoof = new WorldPosition(10, 64, 10, DIM);
        WorldPosition wildDirtLid = new WorldPosition(30, 65, 30, DIM);

        ReadsStructureProtection structures = pos -> {
            if (pos.equals(buildingRoof) || pos.equals(chestUnderRoof)) return StructureProtection.building("储藏室");
            if (pos.equals(machineCasing)) return StructureProtection.machine("粉碎线");
            return StructureProtection.NONE;
        };
        Protection protection = new Protection((d, x, y, z) -> Optional.empty(), List::of,
                name -> Optional.empty(), "self", SeesVillagers.NOBODY, ReadsOwnHome.NOWHERE, id -> id,
                structures, FarmlandIrrigation.WaterGrid.EMPTY);

        assertEquals(ContainerChooser.Lid.BLOCKED_BY_FOREIGN,
                ClientSpotsContainers.lidStatus(protection, buildingRoof, "minecraft:oak_planks"),
                "建筑顶盖不自动挖开");
        assertEquals(ContainerChooser.Lid.BLOCKED_BY_FOREIGN,
                ClientSpotsContainers.lidStatus(protection, machineCasing, "create:andesite_casing"),
                "机器部件顶盖不自动挖开");
        assertEquals(ContainerChooser.Lid.BLOCKED_BY_NATURAL,
                ClientSpotsContainers.lidStatus(protection, wildDirtLid, "minecraft:dirt"),
                "野外天然顶盖允许按原规则挖开");
        assertTrue(protection.mayUseStorage(chestUnderRoof, "minecraft:chest", Set.of()),
                "建筑内的箱子本身取存始终放行");
    }
}
