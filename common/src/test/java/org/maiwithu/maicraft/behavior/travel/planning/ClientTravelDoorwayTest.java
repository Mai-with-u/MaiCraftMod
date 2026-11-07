// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.planning;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 去门、台阶、门口要站准：门那一格、门前门后顺着进出方向的那一格、门口脚下那块地、楼梯和下半台阶算；
 * 门旁边沿着墙摆的箱子、床，堵在门前的箱子，门框旁边的空格都不算，照普通目的地停。
 */
class ClientTravelDoorwayTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 离线的一小块世界：没写的格是空气。 */
    private static final class FakeWorld implements BlockGetter {
        final Map<BlockPos, BlockState> cells = new HashMap<>();

        FakeWorld put(BlockPos pos, BlockState state) {
            cells.put(pos, state);
            return this;
        }

        FakeWorld put(BlockPos pos, Block block) {
            return put(pos, block.defaultBlockState());
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
    }

    private static final Predicate<BlockPos> LOADED = pos -> true;
    private static final BlockPos DOOR = new BlockPos(0, 64, 0);

    /** 一面沿东西走向的墙上开着一扇朝北的门：从北边进、往南出；地面在 63 层。 */
    private static FakeWorld doorInWall() {
        FakeWorld world = new FakeWorld();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) world.put(new BlockPos(x, 63, z), Blocks.STONE);
        }
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
        world.put(DOOR, lower.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        world.put(DOOR.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        return world;
    }

    private static boolean snug(FakeWorld world, BlockPos cell) {
        return ClientTravelWorldView.doorwayOrStep(world, LOADED, cell);
    }

    @Test
    void 门那一格和门前门后都算门口() {
        FakeWorld world = doorInWall();
        assertTrue(snug(world, DOOR), "门那一格");
        assertTrue(snug(world, DOOR.north()), "门前");
        assertTrue(snug(world, DOOR.south()), "门后");
        assertTrue(snug(world, DOOR.north().above()), "门前头那层");
        assertTrue(snug(world, DOOR.north().below()), "门口脚下那块地");
    }

    @Test
    void 门旁边的箱子床_堵在门前的箱子_门框旁边的空格都不算() {
        FakeWorld world = doorInWall().put(DOOR.east(), Blocks.CHEST).put(DOOR.west(), Blocks.RED_BED);
        assertFalse(snug(world, DOOR.east()), "门旁边沿着墙摆的箱子");
        assertFalse(snug(world, DOOR.west()), "门旁边的床");
        assertFalse(snug(doorInWall().put(DOOR.north(), Blocks.CHEST), DOOR.north()), "堵在门前的箱子");
        assertFalse(snug(doorInWall(), DOOR.east()), "门框旁边的空格不是进出的方向");
    }

    @Test
    void 栅栏门按它的朝向认门口() {
        FakeWorld world = new FakeWorld()
                .put(DOOR, Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.EAST));
        assertTrue(snug(world, DOOR.east()));
        assertTrue(snug(world, DOOR.west()));
        assertFalse(snug(world, DOOR.north()), "栅栏门旁边沿着栅栏的那一格");
    }

    @Test
    void 楼梯下半台阶算_空地不算_没加载不算() {
        assertTrue(snug(new FakeWorld().put(DOOR, Blocks.OAK_STAIRS), DOOR), "楼梯那一格");
        assertTrue(snug(new FakeWorld().put(DOOR.below(), Blocks.STONE_SLAB), DOOR), "站在下半台阶上");
        assertFalse(snug(new FakeWorld().put(DOOR.below(), Blocks.STONE), DOOR), "平地");
        assertFalse(ClientTravelWorldView.doorwayOrStep(doorInWall(), pos -> false, DOOR), "没加载");
    }
}
