// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 直线走行走廊：整个身体不能擦进障碍，脚下沿途要有连续支撑，岩浆、禁入格、未加载格都不放行；
 * 跳跃前的顶棚要沿途连续，下坑走行的断口柱列交给调用方逐柱放行。
 */
class GroundCorridorTest {

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

    private static final Predicate<BlockPos> 全加载 = pos -> true;
    private static final Vec3 起点 = new Vec3(0.5, 64.0, 0.5);
    private static final Vec3 终点 = new Vec3(2.5, 64.0, 0.5);

    /** 平地：y63 铺一层石头地面，其余是空气；站高 1.8、身宽 0.6。 */
    private static FakeWorld flatGround() {
        FakeWorld world = new FakeWorld();
        for (int x = -2; x <= 4; x++) {
            for (int z = -2; z <= 2; z++) {
                world.put(new BlockPos(x, 63, z), Blocks.STONE);
            }
        }
        return world;
    }

    private static GroundCorridor corridor(FakeWorld world, Predicate<BlockPos> loaded, LongSet forbidden) {
        return new GroundCorridor(world, loaded, 0.6, 1.8, forbidden, PhysicalObstacleSnapshot.EMPTY);
    }

    @Test
    void 平直地面走得过去() {
        assertTrue(corridor(flatGround(), 全加载, LongSets.emptySet()).clear(起点, 终点));
    }

    @Test
    void 身体撞进墙就不放行() {
        FakeWorld world = flatGround();
        world.put(new BlockPos(1, 64, 0), Blocks.STONE);
        assertFalse(corridor(world, 全加载, LongSets.emptySet()).clear(起点, 终点));
    }

    @Test
    void 脚下地面断了就不放行() {
        FakeWorld world = flatGround();
        world.cells.remove(new BlockPos(1, 63, 0));
        assertFalse(corridor(world, 全加载, LongSets.emptySet()).clear(起点, 终点));
    }

    @Test
    void 路上的岩浆地面和禁入格都不放行() {
        FakeWorld magma = flatGround();
        magma.put(new BlockPos(1, 63, 0), Blocks.MAGMA_BLOCK);
        assertFalse(corridor(magma, 全加载, LongSets.emptySet()).clear(起点, 终点));

        LongSet forbidden = new LongOpenHashSet();
        forbidden.add(new BlockPos(1, 64, 0).asLong());
        assertFalse(corridor(flatGround(), 全加载, forbidden).clear(起点, 终点));
    }

    @Test
    void 沿途读到没加载的格就不给这条线放行() {
        assertFalse(corridor(flatGround(), pos -> !pos.equals(new BlockPos(1, 63, 0)), LongSets.emptySet())
                .clear(起点, 终点));
    }

    @Test
    void 跳跃前的顶棚要沿途连续() {
        FakeWorld open = flatGround();
        for (int x = 0; x <= 3; x++) {
            open.put(new BlockPos(x, 66, 0), Blocks.STONE);
        }
        assertTrue(corridor(open, 全加载, LongSets.emptySet()).hasContinuousCeiling(起点, 终点, 2.0));

        FakeWorld broken = flatGround();
        broken.cells.remove(new BlockPos(1, 66, 0));
        assertFalse(corridor(broken, 全加载, LongSets.emptySet()).hasContinuousCeiling(起点, 终点, 2.0));

        // 顶棚高度上灌了水也不算顶棚：顶上要的是完整的实心方块。
        FakeWorld flooded = flatGround();
        for (int x = 0; x <= 3; x++) {
            flooded.put(new BlockPos(x, 66, 0), Blocks.STONE);
        }
        flooded.put(new BlockPos(1, 66, 0), Blocks.WATER);
        assertFalse(corridor(flooded, 全加载, LongSets.emptySet()).hasContinuousCeiling(起点, 终点, 2.0));
    }

    @Test
    void 下坑走行的断口柱列由放行判据逐柱决定() {
        FakeWorld pit = flatGround();
        pit.cells.remove(new BlockPos(1, 63, 0));
        // 断口柱列连同身体扫掠跨到断口边缘的邻柱都会被点名：放行判据认下这一段坑口，走行才成立。
        assertTrue(corridor(pit, 全加载, LongSets.emptySet())
                .descentWalk(起点, 终点, pos -> pos.getY() == 64 && pos.getZ() == 0
                        && pos.getX() >= 0 && pos.getX() <= 2));
        // 不放行：断口就是断口。
        assertFalse(corridor(pit, 全加载, LongSets.emptySet())
                .descentWalk(起点, 终点, pos -> false));
    }
}
