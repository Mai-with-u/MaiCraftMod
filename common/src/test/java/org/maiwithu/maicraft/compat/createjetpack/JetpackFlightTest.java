// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.createjetpack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 喷气背包落地后的说明：准准落在矮方块上的落点不报落偏，真落偏了才写实际落在哪。 */
class JetpackFlightTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 准准落在灵魂沙半砖耕地上的落点不报落偏() {
        // 落点是方块上面那一格；人陷在灵魂沙里、站在下半砖或耕地上时 y 比落点低一点，仍算落在落点上。
        World world = new World();
        world.put(new BlockPos(0, 64, 0), Blocks.SOUL_SAND.defaultBlockState());
        world.put(new BlockPos(2, 64, 0), Blocks.STONE_SLAB.defaultBlockState());
        world.put(new BlockPos(4, 64, 0), Blocks.FARMLAND.defaultBlockState());
        assertEquals("", JetpackFlight.offLanding(world, new Vec3(0.5, 64.875, 0.5), new BlockPos(0, 65, 0)), "灵魂沙");
        assertEquals("", JetpackFlight.offLanding(world, new Vec3(2.5, 64.5, 0.5), new BlockPos(2, 65, 0)), "下半砖");
        assertEquals("", JetpackFlight.offLanding(world, new Vec3(4.5, 64.9375, 0.5), new BlockPos(4, 65, 0)), "耕地");
    }

    @Test
    void 真落偏了写实际落在哪() {
        // 落点在 (0, 65, 0) 的灵魂沙上，人落在旁边石头地上：说明里补一句实际落在的那一格。
        World world = new World();
        world.put(new BlockPos(0, 64, 0), Blocks.SOUL_SAND.defaultBlockState());
        world.put(new BlockPos(3, 64, 0), Blocks.STONE.defaultBlockState());
        assertEquals("（实际落在 3, 65, 0）",
                JetpackFlight.offLanding(world, new Vec3(3.5, 65.0, 0.5), new BlockPos(0, 65, 0)));
    }

    /** 替身世界：没摆的格子都是空气。 */
    private static final class World implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        void put(BlockPos pos, BlockState state) {
            blocks.put(pos.immutable(), state);
        }

        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    }
}
