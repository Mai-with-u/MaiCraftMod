// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 被困在家里拆墙出去时选墙：不拆的格（床、家具、太硬的、上面压着床）；拆了会带掉东西的（贴着的火把梯子、上面的沙子）、
 * 掉不回原物的另排。
 */
class HomeWayOutTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void 摘掉世界保护() {
        NavigationProtection.installWorldRule(NavigationProtection.CellRule.NONE);
    }

    private static final BlockPos LOWER = new BlockPos(3, 64, 1);
    private static final BlockPos UPPER = new BlockPos(3, 65, 1);

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

    // 家里的格子都按"在家里、不是功能方块"答能拆；别的由拆墙这一层自己看。
    private static void everyCellAtHome() {
        NavigationProtection.installWorldRule(new NavigationProtection.CellRule() {
            @Override public boolean protects(int x, int y, int z, BlockState state) {
                return false;
            }

            @Override public boolean breakableAtHome(int x, int y, int z, BlockState state) {
                return true;
            }
        });
    }

    @Test
    void 木板墙能拆_床家具黑曜石空气上面压着床的不拆_这一趟保护的不拆() {
        everyCellAtHome();
        LongSet none = LongSets.emptySet();
        assertTrue(HomeWayOut.breakableWall(new FakeWorld().put(LOWER, Blocks.OAK_PLANKS), LOWER, none));
        assertFalse(HomeWayOut.breakableWall(new FakeWorld().put(LOWER, Blocks.RED_BED), LOWER, none), "床");
        assertFalse(HomeWayOut.breakableWall(new FakeWorld().put(LOWER, Blocks.CHEST), LOWER, none), "箱子");
        assertFalse(HomeWayOut.breakableWall(new FakeWorld().put(LOWER, Blocks.OAK_DOOR), LOWER, none), "门");
        assertFalse(HomeWayOut.breakableWall(new FakeWorld().put(LOWER, Blocks.OBSIDIAN), LOWER, none), "黑曜石太硬");
        assertFalse(HomeWayOut.breakableWall(new FakeWorld(), LOWER, none), "空气");
        assertFalse(HomeWayOut.breakableWall(new FakeWorld().put(LOWER, Blocks.OAK_PLANKS).put(UPPER, Blocks.RED_BED),
                LOWER, none), "上面压着床");
        LongSet sacred = new LongOpenHashSet();
        sacred.add(LOWER.asLong());
        assertFalse(HomeWayOut.breakableWall(new FakeWorld().put(LOWER, Blocks.OAK_PLANKS), LOWER, sacred), "这一趟保护的");
    }

    @Test
    void 不在家里的不拆() {
        assertFalse(HomeWayOut.breakableWall(new FakeWorld().put(LOWER, Blocks.OAK_PLANKS), LOWER, LongSets.emptySet()));
    }

    @Test
    void 拆了会带掉东西的墙_贴着的火把梯子_立在上面的_吊在下面的_上面的沙子() {
        BlockPos east = LOWER.east();
        BlockState torchFacingEast = Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.EAST);
        assertTrue(HomeWayOut.knocksOff(new FakeWorld().put(LOWER, Blocks.OAK_PLANKS).put(east, torchFacingEast), LOWER),
                "朝外挂在这格墙上的火把");
        BlockState torchFacingWest = Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.WEST);
        assertFalse(HomeWayOut.knocksOff(new FakeWorld().put(LOWER, Blocks.OAK_PLANKS).put(east, torchFacingWest), LOWER),
                "旁边那格的火把挂在别的墙上");
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.WEST);
        assertTrue(HomeWayOut.knocksOff(new FakeWorld().put(LOWER, Blocks.OAK_PLANKS).put(LOWER.west(), ladder), LOWER),
                "屋里贴着这格墙的梯子");
        assertTrue(HomeWayOut.knocksOff(new FakeWorld().put(LOWER, Blocks.OAK_PLANKS).put(UPPER, Blocks.TORCH), LOWER),
                "窗台上立着的火把");
        assertTrue(HomeWayOut.knocksOff(new FakeWorld().put(LOWER, Blocks.OAK_PLANKS).put(UPPER, Blocks.FLOWER_POT), LOWER),
                "窗台上的花盆");
        BlockState hanging = Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true);
        assertTrue(HomeWayOut.knocksOff(new FakeWorld().put(UPPER, Blocks.OAK_PLANKS).put(LOWER, hanging), UPPER),
                "吊在这格下面的灯笼");
        assertTrue(HomeWayOut.knocksOff(new FakeWorld().put(LOWER, Blocks.OAK_PLANKS).put(UPPER, Blocks.SAND), LOWER),
                "上面压着沙子");
        assertFalse(HomeWayOut.knocksOff(new FakeWorld().put(LOWER, Blocks.OAK_PLANKS).put(UPPER, Blocks.OAK_PLANKS)
                .put(east, Blocks.STONE), LOWER), "四周都是普通方块");
        assertFalse(HomeWayOut.knocksOff(new FakeWorld().put(LOWER, Blocks.OAK_PLANKS).put(UPPER, Blocks.GRINDSTONE), LOWER),
                "砂轮自己立得住");
    }

    @Test
    void 掉不回原物的墙_石头玻璃板书架_木板圆石铁栏杆不算() {
        assertTrue(HomeWayOut.fragile(Blocks.STONE.defaultBlockState()), "石头掉圆石");
        assertTrue(HomeWayOut.fragile(Blocks.GLASS.defaultBlockState()), "玻璃碎了");
        assertTrue(HomeWayOut.fragile(Blocks.GLASS_PANE.defaultBlockState()), "玻璃板碎了");
        assertTrue(HomeWayOut.fragile(Blocks.BOOKSHELF.defaultBlockState()), "书架掉书");
        assertTrue(HomeWayOut.fragile(Blocks.GRASS_BLOCK.defaultBlockState()), "草方块掉泥土");
        assertFalse(HomeWayOut.fragile(Blocks.OAK_PLANKS.defaultBlockState()));
        assertFalse(HomeWayOut.fragile(Blocks.COBBLESTONE.defaultBlockState()));
        assertFalse(HomeWayOut.fragile(Blocks.IRON_BARS.defaultBlockState()), "铁栏杆掉自己");
    }
}
