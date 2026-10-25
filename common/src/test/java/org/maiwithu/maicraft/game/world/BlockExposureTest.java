// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 六面暴露判断：埋在实心里的一格必须判成看不见（再近也不能进候选），
 * 只要有一面邻着空气、水、玻璃这类不挡光的方块就算露出来。
 */
class BlockExposureTest {

    /** 用一张坐标表冒充世界：没写的格子一律当空气。 */
    private static final class FakeWorld implements BlockGetter {
        final Map<BlockPos, BlockState> cells = new HashMap<>();

        void put(BlockPos pos, Block block) {
            cells.put(pos, block.defaultBlockState());
        }

        @Override public BlockState getBlockState(BlockPos pos) {
            return cells.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }

        @Override public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        // LevelHeightAccessor 的边界桩：暴露判断只读六格邻居，用不到世界高度。
        @Override public int getMinBuildHeight() { return -64; }
        @Override public int getMaxBuildHeight() { return 320; }
        @Override public int getHeight() { return getMaxBuildHeight() - getMinBuildHeight(); }
        @Override public boolean isOutsideBuildHeight(BlockPos pos) {
            return pos.getY() < getMinBuildHeight() || pos.getY() >= getMaxBuildHeight();
        }
    }

    @Test
    void 六面全是实心石头算埋着() {
        FakeWorld world = new FakeWorld();
        BlockPos at = new BlockPos(4, 60, 4);
        world.put(at, Blocks.STONE);
        for (Direction face : Direction.values()) {
            world.put(at.relative(face), Blocks.STONE);
        }
        assertFalse(BlockExposure.hasExposedFace(world, at));
    }

    @Test
    void 顶上邻空气就算有面露出() {
        FakeWorld world = new FakeWorld();
        BlockPos at = new BlockPos(4, 60, 4);
        world.put(at, Blocks.STONE);
        for (Direction face : Direction.values()) {
            if (face != Direction.UP) {
                world.put(at.relative(face), Blocks.STONE);
            }
        }
        assertTrue(BlockExposure.hasExposedFace(world, at));
    }

    @Test
    void 邻着玻璃和水这类不挡光方块也算露出() {
        FakeWorld glass = new FakeWorld();
        BlockPos inGlass = new BlockPos(0, 60, 0);
        glass.put(inGlass, Blocks.STONE);
        for (Direction face : Direction.values()) {
            glass.put(inGlass.relative(face), face == Direction.NORTH ? Blocks.GLASS : Blocks.STONE);
        }
        assertTrue(BlockExposure.hasExposedFace(glass, inGlass));

        FakeWorld water = new FakeWorld();
        BlockPos inWater = new BlockPos(0, 60, 0);
        water.put(inWater, Blocks.STONE);
        for (Direction face : Direction.values()) {
            water.put(inWater.relative(face), face == Direction.DOWN ? Blocks.WATER : Blocks.STONE);
        }
        assertTrue(BlockExposure.hasExposedFace(water, inWater));
    }

    @Test
    void 邻着台阶这类不遮整格的方块也算露出() {
        FakeWorld world = new FakeWorld();
        BlockPos at = new BlockPos(4, 60, 4);
        world.put(at, Blocks.STONE);
        for (Direction face : Direction.values()) {
            world.put(at.relative(face), face == Direction.UP ? Blocks.STONE_SLAB : Blocks.STONE);
        }
        // 台阶不遮整格：isSolidRender 不把它当挡光实心，这一格按有面露出算。
        assertTrue(BlockExposure.hasExposedFace(world, at));
    }
}
