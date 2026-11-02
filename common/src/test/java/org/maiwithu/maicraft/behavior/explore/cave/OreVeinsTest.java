// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore.cave;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** 矿脉：同种相连（斜着挨着也算）的是一条，带块数与离角色最近的那一格。 */
class OreVeinsTest {

    @Test
    void 同种相连算一条_不同种分开() {
        Map<BlockPos, String> seen = new LinkedHashMap<>();
        seen.put(new BlockPos(10, 60, 0), "minecraft:iron_ore");
        seen.put(new BlockPos(11, 61, 0), "minecraft:iron_ore");
        seen.put(new BlockPos(12, 61, 1), "minecraft:iron_ore");
        seen.put(new BlockPos(13, 61, 1), "minecraft:coal_ore");
        seen.put(new BlockPos(30, 60, 0), "minecraft:iron_ore");

        List<OreVeins.Vein> veins = OreVeins.group(seen, new BlockPos(0, 60, 0));

        assertEquals(3, veins.size());
        assertEquals(new OreVeins.Vein("minecraft:iron_ore", 3, new BlockPos(10, 60, 0)), veins.get(0));
        assertEquals("minecraft:coal_ore", veins.get(1).blockId());
        assertEquals(1, veins.get(2).blocks(), "隔得远的同种矿是另一条");
    }
}
