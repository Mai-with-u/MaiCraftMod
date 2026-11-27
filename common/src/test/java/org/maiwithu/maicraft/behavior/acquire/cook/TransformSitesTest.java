// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.cook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 哪一格水能扔料：认的流体的源格、水不往外流、头顶空着；排先后时漂着别人东西的往后、再按远近。 */
class TransformSitesTest {

    private static final Set<String> WATER = Set.of("minecraft:water", "minecraft:flowing_water");
    private static final BlockPos POOL = new BlockPos(0, 64, 0);

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 一格水坑：四面和底下是石头，头顶是空气。 */
    private static Pond pond() {
        Pond pond = new Pond();
        pond.blocks.put(POOL, Blocks.WATER.defaultBlockState());
        pond.blocks.put(POOL.below(), Blocks.STONE.defaultBlockState());
        for (Direction side : Direction.Plane.HORIZONTAL) pond.blocks.put(POOL.relative(side), Blocks.STONE.defaultBlockState());
        return pond;
    }

    @Test
    void 石头围着的一格水源_头顶空着_能扔料() {
        assertTrue(TransformSites.usable(pond(), POOL, WATER));
    }

    @Test
    void 头顶盖着_流动的水_别的流体_都不能扔() {
        Pond covered = pond();
        covered.blocks.put(POOL.above(), Blocks.STONE.defaultBlockState());
        assertFalse(TransformSites.usable(covered, POOL, WATER), "头顶盖着扔不进去");

        Pond flowing = pond();
        flowing.blocks.put(POOL, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 2));
        assertFalse(TransformSites.usable(flowing, POOL, WATER), "流动的水不是源格");

        Pond lava = pond();
        lava.blocks.put(POOL, Blocks.LAVA.defaultBlockState());
        assertFalse(TransformSites.usable(lava, POOL, WATER), "配方认的是水");
    }

    @Test
    void 水往旁边流的源格不扔_东西会漂走() {
        Pond leaking = pond();
        // 东边一格是更低的流水：这格水源往东流，扔进去的料会被冲开。
        leaking.blocks.put(POOL.east(), Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3));
        leaking.blocks.put(POOL.east().below(), Blocks.STONE.defaultBlockState());
        assertFalse(TransformSites.usable(leaking, POOL, WATER));
    }

    @Test
    void 排先后_没漂着东西的在前_再按远近() {
        BlockPos near = new BlockPos(1, 64, 0);
        BlockPos far = new BlockPos(9, 64, 0);
        BlockPos crowdedNear = new BlockPos(0, 64, 1);
        List<BlockPos> order = TransformSites.order(List.of(
                new TransformSites.Pool(crowdedNear, 1.0, true),
                new TransformSites.Pool(far, 9.0, false),
                new TransformSites.Pool(near, 1.0, false)));
        assertEquals(List.of(near, far, crowdedNear), order);
    }

    @Test
    void 优先挑有两三格退路的浅水岸边格_避开贴墙窄坑与深水区() {
        // 1) 距离最近但处于 2 格深水中央、四周无干岸的水格。
        BlockPos deepCenter = new BlockPos(2, 64, 0);
        Pond world = new Pond();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.blocks.put(deepCenter.offset(dx, 0, dz), Blocks.WATER.defaultBlockState());
                world.blocks.put(deepCenter.offset(dx, -1, dz), Blocks.WATER.defaultBlockState());
                world.blocks.put(deepCenter.offset(dx, -2, dz), Blocks.STONE.defaultBlockState());
            }
        }
        // 2) 中距离但贴墙无退路的窄水坑（干岸后面紧贴两格石墙）。
        BlockPos crampedPool = new BlockPos(10, 64, 0);
        world.blocks.put(crampedPool, Blocks.WATER.defaultBlockState());
        world.blocks.put(crampedPool.below(), Blocks.STONE.defaultBlockState());
        for (Direction side : Direction.Plane.HORIZONTAL) {
            world.blocks.put(crampedPool.relative(side), Blocks.STONE.defaultBlockState());
            world.blocks.put(crampedPool.relative(side).above(), Blocks.STONE.defaultBlockState());
            world.blocks.put(crampedPool.relative(side).above(2), Blocks.STONE.defaultBlockState());
        }
        // 只留西边 1 格能站，且紧挨着西边的退路是墙。
        world.blocks.remove(crampedPool.west().above());
        world.blocks.remove(crampedPool.west().above(2));
        world.blocks.put(crampedPool.west(2).above(), Blocks.STONE.defaultBlockState());
        world.blocks.put(crampedPool.west(2).above(2), Blocks.STONE.defaultBlockState());

        // 3) 稍远但浅水且岸边背离水格方向有 3 格宽敞平地退路的水格。
        BlockPos openShallow = new BlockPos(15, 64, 0);
        world.blocks.put(openShallow, Blocks.WATER.defaultBlockState());
        world.blocks.put(openShallow.below(), Blocks.STONE.defaultBlockState());
        for (int step = 1; step <= 4; step++) {
            world.blocks.put(openShallow.east(step), Blocks.STONE.defaultBlockState());
        }

        TransformSites.Pool deepEval = TransformSites.evaluate(world, deepCenter, 2.0, false);
        TransformSites.Pool crampedEval = TransformSites.evaluate(world, crampedPool, 10.0, false);
        TransformSites.Pool openEval = TransformSites.evaluate(world, openShallow, 15.0, false);

        assertFalse(deepEval.shallow(), "两格水深不应判为浅水");
        assertFalse(deepEval.hasDryBank(), "深水中央四邻全是水，没有干岸");
        assertTrue(crampedEval.hasDryBank() && crampedEval.retreatDepth() == 0, "贴墙窄坑应识别为无退路");
        assertTrue(openEval.shallow() && openEval.hasDryBank() && openEval.retreatDepth() >= 3,
                "浅水岸边且有 3 格平地应拿满退路评分");

        List<BlockPos> ordered = TransformSites.order(List.of(deepEval, crampedEval, openEval));
        assertEquals(List.of(openShallow, crampedPool, deepCenter), ordered,
                "排序应优先有退路的浅水岸边，其次贴墙干岸，最后才是深水中央");
    }

    /** 摆了几格方块的世界替身，其余是空气。 */
    private static final class Pond implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        @Override public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }

        @Override public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override public int getHeight() {
            return 384;
        }

        @Override public int getMinBuildHeight() {
            return -64;
        }
    }
}
