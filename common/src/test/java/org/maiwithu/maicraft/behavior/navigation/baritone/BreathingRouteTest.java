// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 换气通道的几何观察：眼睛露在水面上才算可呼吸，身体整段上浮通道都要能穿过去；
 * 开阔水柱走快捷直上，头顶被挡住就承认这条路不通，深海一时见不到水面仍给出已验证的上浮前缀。
 */
class BreathingRouteTest {

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

    /** 角色身体尺寸：宽 0.6、高 1.8、眼高 1.62，与真实站姿一致。 */
    private static BreathingRoute.View view(FakeWorld world, Predicate<BlockPos> loaded, LongSet forbidden) {
        return BreathingRoute.geometry(world, loaded, forbidden, 0.6, 1.8, 1.62);
    }

    @Test
    void 眼睛露在水面上才算可呼吸() {
        FakeWorld world = new FakeWorld();
        for (int y = 63; y <= 65; y++) {
            world.put(new BlockPos(0, y, 0), Blocks.WATER);
        }
        BreathingRoute.View geometry = view(world, 全加载, LongSets.emptySet());
        // 眼睛 65.95：还在顶层水格里，但已高出水面一点，能露头。
        assertTrue(geometry.breathable(new Vec3(0.5, 64.33, 0.5)));
        // 眼睛 65.62：还在顶层水格的水面之下，不能呼吸。
        assertFalse(geometry.breathable(new Vec3(0.5, 64.0, 0.5)));
        // 眼睛所在格是空气：能呼吸。
        assertTrue(geometry.breathable(new Vec3(0.5, 65.6, 0.5)));
    }

    @Test
    void 上浮通道被实心方块挡住就算不通() {
        FakeWorld world = new FakeWorld();
        world.put(new BlockPos(2, 60, 2), Blocks.WATER);
        world.put(new BlockPos(2, 61, 2), Blocks.WATER);
        world.put(new BlockPos(2, 62, 2), Blocks.STONE);
        assertFalse(view(world, 全加载, LongSets.emptySet()).clear(
                new Vec3(2.5, 60.0, 2.5), new Vec3(2.5, 60.5, 2.5)));
    }

    @Test
    void 通道里的岩浆禁入格和未加载格都不放行() {
        FakeWorld lava = new FakeWorld();
        lava.put(new BlockPos(2, 62, 2), Blocks.LAVA);
        assertFalse(view(lava, 全加载, LongSets.emptySet()).clear(
                new Vec3(2.5, 60.0, 2.5), new Vec3(2.5, 61.0, 2.5)));

        FakeWorld plain = new FakeWorld();
        LongSet forbidden = new LongOpenHashSet();
        forbidden.add(new BlockPos(2, 61, 2).asLong());
        assertFalse(view(plain, 全加载, forbidden).clear(
                new Vec3(2.5, 60.0, 2.5), new Vec3(2.5, 61.0, 2.5)));

        FakeWorld partly = new FakeWorld();
        assertFalse(view(partly, pos -> !pos.equals(new BlockPos(2, 60, 2)), LongSets.emptySet()).clear(
                new Vec3(2.5, 60.0, 2.5), new Vec3(2.5, 60.5, 2.5)));
    }

    @Test
    void 起点就能呼吸就原地算到() {
        BreathingRoute.Route route = BreathingRoute.ascent(view(new FakeWorld(), 全加载, LongSets.emptySet()),
                new Vec3(0.5, 64.0, 0.5));
        assertNotNull(route);
        assertTrue(route.reachesAir());
        assertEquals(0, route.ticks(), 1.0e-9);
        assertEquals(1, route.points().size());
    }

    @Test
    void 开阔水柱直上半格就露出水面() {
        FakeWorld world = new FakeWorld();
        world.put(new BlockPos(0, 59, 0), Blocks.STONE);
        for (int y = 60; y <= 63; y++) {
            world.put(new BlockPos(0, y, 0), Blocks.WATER);
        }
        BreathingRoute.Route route = BreathingRoute.ascent(view(world, 全加载, LongSets.emptySet()),
                new Vec3(0.5, 62.0, 0.5));
        assertNotNull(route);
        assertTrue(route.reachesAir(), "水柱顶上没有遮挡，直上就能呼吸");
    }

    @Test
    void 头顶被挡住就承认这条路不通() {
        FakeWorld world = new FakeWorld();
        world.put(new BlockPos(0, 59, 0), Blocks.STONE);
        for (int y = 60; y <= 61; y++) {
            world.put(new BlockPos(0, y, 0), Blocks.WATER);
        }
        world.put(new BlockPos(0, 62, 0), Blocks.GLASS);
        assertNull(BreathingRoute.ascent(view(world, 全加载, LongSets.emptySet()), new Vec3(0.5, 60.0, 0.5)));
    }

    @Test
    void 深海一时见不到水面仍给出已验证的上浮前缀() {
        FakeWorld world = new FakeWorld();
        world.put(new BlockPos(0, 39, 0), Blocks.STONE);
        for (int y = 40; y <= 70; y++) {
            world.put(new BlockPos(0, y, 0), Blocks.WATER);
        }
        BreathingRoute.Route route = BreathingRoute.ascent(view(world, 全加载, LongSets.emptySet()),
                new Vec3(0.5, 41.0, 0.5));
        // 十六格以内没有水面：路线不为空，可以照着前缀先浮上去，但如实说这一段还没见到空气。
        assertNotNull(route);
        assertFalse(route.reachesAir());
        assertEquals(2, route.points().size());
        assertEquals(41.0 + 16, route.points().get(1).y, 1.0e-9);
    }
}
