// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 一格方块算不算空了：空气算空了，只剩挖之前泡着的水也算空了，别的方块不算空。
 */
class BlockGoneTest {

    @BeforeAll
    static void 备好方块表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 空气算这一格空了() {
        assertTrue(BlockGone.gone(Blocks.AIR.defaultBlockState()));
    }

    @Test
    void 只剩流体也算这一格空了() {
        assertTrue(BlockGone.gone(Blocks.WATER.defaultBlockState()));
    }

    @Test
    void 别的方块不算空() {
        assertFalse(BlockGone.gone(Blocks.STONE.defaultBlockState()));
    }
}
