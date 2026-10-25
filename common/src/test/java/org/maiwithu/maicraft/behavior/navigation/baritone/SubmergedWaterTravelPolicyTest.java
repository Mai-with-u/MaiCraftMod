// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 湿侧的通行空间与水面定位：水面下两格都是能游的清水、头顶露在空气里、脚下不是会烫脚的东西，
 * 这一列才算安全；找水面要顺着水柱爬到最顶格，通道里卡着含水实体就不能越过它只认上层。
 */
class SubmergedWaterTravelPolicyTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // 离线没有数据包，水与岩浆的标签要手动绑上，流体读数才认得出是水。
        BuiltInRegistries.FLUID.bindTags(Map.of(
                FluidTags.WATER, List.of(BuiltInRegistries.FLUID.wrapAsHolder(Fluids.WATER),
                        BuiltInRegistries.FLUID.wrapAsHolder(Fluids.FLOWING_WATER)),
                FluidTags.LAVA, List.of(BuiltInRegistries.FLUID.wrapAsHolder(Fluids.LAVA),
                        BuiltInRegistries.FLUID.wrapAsHolder(Fluids.FLOWING_LAVA))));
    }

    /** 用一张坐标表冒充世界：没写的格子一律当空气。 */
    private static final class FakeWorld implements BlockGetter {
        final Map<BlockPos, BlockState> cells = new HashMap<>();

        void put(BlockPos pos, Block block) {
            cells.put(pos, block.defaultBlockState());
        }

        void putWaterlogged(BlockPos pos, Block block) {
            cells.put(pos, block.defaultBlockState().setValue(StairBlock.WATERLOGGED, Boolean.TRUE));
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

        @Override public int getMinBuildHeight() { return -64; }
        @Override public int getMaxBuildHeight() { return 320; }
        @Override public int getHeight() { return getMaxBuildHeight() - getMinBuildHeight(); }
        @Override public boolean isOutsideBuildHeight(BlockPos pos) {
            return pos.getY() < getMinBuildHeight() || pos.getY() >= getMaxBuildHeight();
        }
    }

    /** 摆一根标准水面柱：62、63 两格是水，64 是空气，61 是石头地面。 */
    private static FakeWorld openWater() {
        FakeWorld world = new FakeWorld();
        world.put(new BlockPos(0, 61, 0), Blocks.STONE);
        world.put(new BlockPos(0, 62, 0), Blocks.WATER);
        world.put(new BlockPos(0, 63, 0), Blocks.WATER);
        return world;
    }

    @Test
    void 水面下两格清水头顶空气脚下实地算安全() {
        assertTrue(SubmergedWaterTravelPolicy.safeSurfaceColumn(openWater(), new BlockPos(0, 63, 0)));
    }

    @Test
    void 头顶还有一层水说明这里不是水面() {
        FakeWorld world = openWater();
        world.put(new BlockPos(0, 64, 0), Blocks.WATER);
        world.put(new BlockPos(0, 65, 0), Blocks.WATER);
        assertFalse(SubmergedWaterTravelPolicy.safeSurfaceColumn(world, new BlockPos(0, 64, 0)));
    }

    @Test
    void 头顶被封住的柱列没有呼吸口() {
        FakeWorld glass = openWater();
        glass.put(new BlockPos(0, 64, 0), Blocks.GLASS);
        assertFalse(SubmergedWaterTravelPolicy.safeSurfaceColumn(glass, new BlockPos(0, 63, 0)));

        FakeWorld stone = openWater();
        stone.put(new BlockPos(0, 64, 0), Blocks.STONE);
        assertFalse(SubmergedWaterTravelPolicy.safeSurfaceColumn(stone, new BlockPos(0, 63, 0)));
    }

    @Test
    void 脚下是岩浆块灵魂沙或岩浆都不站() {
        FakeWorld magma = openWater();
        magma.put(new BlockPos(0, 61, 0), Blocks.MAGMA_BLOCK);
        assertFalse(SubmergedWaterTravelPolicy.safeSurfaceColumn(magma, new BlockPos(0, 63, 0)));

        FakeWorld soulSand = openWater();
        soulSand.put(new BlockPos(0, 61, 0), Blocks.SOUL_SAND);
        assertFalse(SubmergedWaterTravelPolicy.safeSurfaceColumn(soulSand, new BlockPos(0, 63, 0)));

        FakeWorld lava = openWater();
        lava.put(new BlockPos(0, 61, 0), Blocks.LAVA);
        assertFalse(SubmergedWaterTravelPolicy.safeSurfaceColumn(lava, new BlockPos(0, 63, 0)));
    }

    @Test
    void 水柱中间是气泡柱不算畅通() {
        FakeWorld world = openWater();
        world.put(new BlockPos(0, 62, 0), Blocks.BUBBLE_COLUMN);
        assertFalse(SubmergedWaterTravelPolicy.safeSurfaceColumn(world, new BlockPos(0, 63, 0)));
    }

    @Test
    void 脚下那格不是水就没法在这里游() {
        FakeWorld world = openWater();
        world.cells.remove(new BlockPos(0, 62, 0));
        assertFalse(SubmergedWaterTravelPolicy.safeSurfaceColumn(world, new BlockPos(0, 63, 0)));
    }

    @Test
    void 找水面顺着水柱爬到最顶上一格() {
        FakeWorld deep = new FakeWorld();
        for (int y = 58; y <= 63; y++) {
            deep.put(new BlockPos(4, y, 7), Blocks.WATER);
        }
        assertEquals(new BlockPos(4, 63, 7),
                SubmergedWaterTravelPolicy.findWaterSurface(deep, new BlockPos(4, 58, 7)));
    }

    @Test
    void 从水面上方的空气开始先往下探回水面() {
        assertEquals(new BlockPos(0, 63, 0),
                SubmergedWaterTravelPolicy.findWaterSurface(openWater(), new BlockPos(0, 64, 0)));
    }

    @Test
    void 通道里卡着含水台阶就不能越过它只认上层() {
        FakeWorld world = openWater();
        world.putWaterlogged(new BlockPos(0, 62, 0), Blocks.OAK_STAIRS);
        assertNull(SubmergedWaterTravelPolicy.findWaterSurface(world, new BlockPos(0, 62, 0)));
    }

    @Test
    void 整列没有水就没有水面() {
        assertNull(SubmergedWaterTravelPolicy.findWaterSurface(new FakeWorld(), new BlockPos(0, 63, 0)));
    }
}
