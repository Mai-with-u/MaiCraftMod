// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 功能方块：被困时也不挖的那一类，按方块实体、方块类别、原版标签认，普通的泥土木板玻璃不在其中。 */
class FunctionalBlocksTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 存东西的_进出口_机关_铁轨_都是功能方块() {
        for (Block block : new Block[] {Blocks.CHEST, Blocks.BARREL, Blocks.FURNACE, Blocks.RED_BED, Blocks.OAK_SIGN,
                Blocks.SHULKER_BOX, Blocks.OAK_DOOR, Blocks.OAK_TRAPDOOR, Blocks.OAK_FENCE_GATE, Blocks.LEVER,
                Blocks.STONE_BUTTON, Blocks.STONE_PRESSURE_PLATE, Blocks.REDSTONE_WIRE, Blocks.REPEATER,
                Blocks.OBSERVER, Blocks.RAIL, Blocks.POWERED_RAIL}) {
            assertTrue(FunctionalBlocks.functional(block.defaultBlockState()), block.toString());
        }
    }

    @Test
    void 堵人用的普通方块_不是功能方块() {
        for (Block block : new Block[] {Blocks.DIRT, Blocks.COBBLESTONE, Blocks.OAK_PLANKS, Blocks.GLASS,
                Blocks.WHITE_WOOL, Blocks.SAND, Blocks.OAK_FENCE, Blocks.SCAFFOLDING}) {
            assertFalse(FunctionalBlocks.functional(block.defaultBlockState()), block.toString());
        }
    }

    @Test
    void 读不到方块按功能方块算() {
        assertTrue(FunctionalBlocks.functional(null), "拿不准就不挖");
    }
}
