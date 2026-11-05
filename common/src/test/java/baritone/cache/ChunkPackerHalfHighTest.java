// SPDX-License-Identifier: GPL-3.0-only
package baritone.cache;

import baritone.utils.pathing.PathingBlockType;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 远处区块缓存只记方块大类：床、熄灭的营火这类矮平顶和下半台阶一样记成要避开的，近了再按实况算。 */
class ChunkPackerHalfHighTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 矮平顶和下半台阶一样记成要避开的() {
        assertEquals(PathingBlockType.AVOID,
                ChunkPacker.getPathingBlockType(Blocks.RED_BED.defaultBlockState(), null, 0, 0, 0));
        assertEquals(PathingBlockType.AVOID, ChunkPacker.getPathingBlockType(
                Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false), null, 0, 0, 0));
        assertEquals(PathingBlockType.AVOID,
                ChunkPacker.getPathingBlockType(Blocks.STONE_SLAB.defaultBlockState(), null, 0, 0, 0));
        assertEquals(PathingBlockType.SOLID,
                ChunkPacker.getPathingBlockType(Blocks.STONE.defaultBlockState(), null, 0, 0, 0));
    }
}
