// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 天然树的辨认：顶上有树冠的原木是树，光秃秃的原木柱、玩家放的持久树叶都不是。 */
class NaturalTreesTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 替身世界：没摆的格子都是空气。 */
    private static final class World implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        void log(int x, int y, int z) {
            blocks.put(new BlockPos(x, y, z), Blocks.OAK_LOG.defaultBlockState());
        }

        void leaf(int x, int y, int z, int distance, boolean persistent) {
            blocks.put(new BlockPos(x, y, z), Blocks.OAK_LEAVES.defaultBlockState()
                    .setValue(LeavesBlock.DISTANCE, distance).setValue(LeavesBlock.PERSISTENT, persistent));
        }

        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    }

    private static World tree(boolean persistentLeaves) {
        World world = new World();
        for (int y = 64; y <= 67; y++) world.log(0, y, 0);
        // 树冠：树顶两层四周贴着的叶子距离 1，再往外一圈距离 2，一共十几片。
        for (int y = 66; y <= 67; y++) {
            world.leaf(1, y, 0, 1, persistentLeaves);
            world.leaf(-1, y, 0, 1, persistentLeaves);
            world.leaf(0, y, 1, 1, persistentLeaves);
            world.leaf(0, y, -1, 1, persistentLeaves);
            world.leaf(2, y, 0, 2, persistentLeaves);
            world.leaf(-2, y, 0, 2, persistentLeaves);
        }
        return world;
    }

    @Test
    void 顶上有树冠的原木是树_从树干哪一格看都一样() {
        World world = tree(false);
        assertTrue(NaturalTrees.partOfNaturalTree(new BlockPos(0, 64, 0), world, pos -> true, new HashMap<>()));
        assertTrue(NaturalTrees.partOfNaturalTree(new BlockPos(0, 67, 0), world, pos -> true, new HashMap<>()));
    }

    @Test
    void 光秃秃的原木柱不是树_木屋的横梁也不是() {
        World world = new World();
        for (int y = 64; y <= 66; y++) world.log(5, y, 0);
        assertFalse(NaturalTrees.partOfNaturalTree(new BlockPos(5, 65, 0), world, pos -> true, new HashMap<>()));
    }

    @Test
    void 玩家放的持久树叶不算树冠() {
        World world = tree(true);
        assertFalse(NaturalTrees.partOfNaturalTree(new BlockPos(0, 64, 0), world, pos -> true, new HashMap<>()));
    }

    /** 云杉/枞树这类锥形冠：竖干顶梢一两层光秃，叶环挂在中上部。 */
    private static World conifer() {
        World world = new World();
        for (int y = 133; y <= 139; y++) world.log(30, y, 33);
        // 叶环在 136、137 两层：贴干一圈距离 1，再往外一圈距离 2；顶上 138、139 光秃。
        for (int y = 136; y <= 137; y++) {
            world.leaf(31, y, 33, 1, false);
            world.leaf(29, y, 33, 1, false);
            world.leaf(30, y, 34, 1, false);
            world.leaf(30, y, 32, 1, false);
            world.leaf(32, y, 33, 2, false);
            world.leaf(28, y, 33, 2, false);
        }
        return world;
    }

    @Test
    void 顶梢光秃叶环挂中上部的锥形冠是树() {
        World world = conifer();
        // 从树干哪一格看都一样，含实机现场 (30,133,33)~(30,139,33) 的两端。
        assertTrue(NaturalTrees.partOfNaturalTree(new BlockPos(30, 139, 33), world, pos -> true, new HashMap<>()));
        assertTrue(NaturalTrees.partOfNaturalTree(new BlockPos(30, 133, 33), world, pos -> true, new HashMap<>()));
    }

    @Test
    void 两列并排的粗干锥形冠也是树() {
        World world = new World();
        for (int y = 130; y <= 140; y++) {
            world.log(10, y, 20);
            world.log(11, y, 20);
        }
        // 叶环挂在中上部，整圈包住两列干，含四角。
        for (int y = 136; y <= 138; y++) {
            world.leaf(9, y, 20, 1, false);
            world.leaf(12, y, 20, 1, false);
            world.leaf(10, y, 19, 1, false);
            world.leaf(11, y, 19, 1, false);
            world.leaf(10, y, 21, 1, false);
            world.leaf(11, y, 21, 1, false);
            world.leaf(9, y, 19, 2, false);
            world.leaf(12, y, 21, 2, false);
        }
        assertTrue(NaturalTrees.partOfNaturalTree(new BlockPos(10, 140, 20), world, pos -> true, new HashMap<>()));
        assertTrue(NaturalTrees.partOfNaturalTree(new BlockPos(11, 132, 20), world, pos -> true, new HashMap<>()));
    }

    @Test
    void 木屋的原木墙和横梁不是树() {
        World world = new World();
        // 一面 3x3 的原木墙，旁边搭着持久树叶当装饰。
        for (int x = 0; x <= 2; x++) {
            for (int y = 64; y <= 66; y++) world.log(x, y, 0);
        }
        world.leaf(1, 65, 1, 1, true);
        assertFalse(NaturalTrees.partOfNaturalTree(new BlockPos(1, 65, 0), world, pos -> true, new HashMap<>()));
        // 横着的梁：原木只有一格高，旁边挨着别的原木也不算冠。
        World beam = new World();
        beam.log(0, 70, 0);
        beam.log(1, 70, 0);
        beam.log(2, 70, 0);
        assertFalse(NaturalTrees.partOfNaturalTree(new BlockPos(1, 70, 0), beam, pos -> true, new HashMap<>()));
    }

    @Test
    void 叶子只长在树干下半段不算树冠() {
        World world = new World();
        for (int y = 64; y <= 71; y++) world.log(0, y, 0);
        // 下半段贴一圈非持久叶，上半段全光秃：树冠不在树上部的样子不是立树。
        for (int y = 64; y <= 66; y++) {
            world.leaf(1, y, 0, 1, false);
            world.leaf(-1, y, 0, 1, false);
            world.leaf(0, y, 1, 1, false);
            world.leaf(0, y, -1, 1, false);
        }
        assertFalse(NaturalTrees.partOfNaturalTree(new BlockPos(0, 71, 0), world, pos -> true, new HashMap<>()));
    }
}
