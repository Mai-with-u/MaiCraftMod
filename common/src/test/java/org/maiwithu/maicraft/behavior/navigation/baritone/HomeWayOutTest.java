// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * 被困在家里拆墙出去的这一段：拆完最后一格人还在屋里时不补，穿过缺口、离开屋里才补；半路收场只写真挖开了的格，
 * 一格没挖开写"没拆成"、不算拆了别人的；补完收起，同一趟再被困重新找墙；补到一半被叫停照实记下。
 * 选墙时不拆的格：床、家具、太硬的、上面压着床；拆了会带掉东西的（贴着的火把梯子、上面的沙子）、掉不回原物的另排。
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

    /** 屋里 3×3（x、z 从 0 到 2，脚在 64 层），东墙 x=3 上拆脚那层、头那层两格。 */
    private static final BlockPos LOWER = new BlockPos(3, 64, 1);
    private static final BlockPos UPPER = new BlockPos(3, 65, 1);
    private static final BlockPos INSIDE = new BlockPos(1, 64, 1);
    private static final BlockPos BESIDE_GAP = new BlockPos(2, 64, 1);
    private static final BlockPos OUTSIDE = new BlockPos(4, 64, 1);

    private static HomeWayOut breaching(Map<BlockPos, String> placedBy) {
        LongSet pocket = new LongOpenHashSet();
        for (int x = 0; x <= 2; x++) {
            for (int z = 0; z <= 2; z++) pocket.add(new BlockPos(x, 64, z).asLong());
        }
        HomeBreach breach = new HomeBreach(BESIDE_GAP, Direction.EAST, List.of(LOWER, UPPER), LongSets.emptySet(), pocket);
        HomeWayOut wayOut = new HomeWayOut();
        wayOut.begin(breach, Map.of(LOWER, Blocks.OAK_PLANKS, UPPER, Blocks.OAK_PLANKS), placedBy);
        return wayOut;
    }

    // 两格都挖开，卡坑判断说出得去了：拆完最后一格的那一刻。
    private static HomeWayOut dugBoth() {
        HomeWayOut wayOut = breaching(Map.of());
        wayOut.noteDug(LOWER);
        wayOut.noteDug(UPPER);
        wayOut.exited();
        return wayOut;
    }

    @Test
    void 拆完最后一格人还在屋里_不补_穿过缺口离开屋里才补() {
        HomeWayOut wayOut = dugBoth();
        assertEquals(HomeWayOut.Stage.REFILL_PENDING, wayOut.stage());
        assertEquals(HomeWayOut.Step.WALK_ON, wayOut.leaveGap(false, BESIDE_GAP), "人还站在屋里，不能把自己封回去");
        assertEquals(HomeWayOut.Step.WALK_ON, wayOut.leaveGap(true, LOWER), "正穿过缺口");
        assertEquals(HomeWayOut.Step.STARTED, wayOut.leaveGap(false, OUTSIDE), "穿出来了，停下补");
        assertEquals(HomeWayOut.Stage.REFILLING, wayOut.stage());
        assertEquals(Optional.of(LOWER), wayOut.refillingCell(), "从下往上补");
    }

    @Test
    void 进过缺口又退回屋里_不补() {
        HomeWayOut wayOut = dugBoth();
        wayOut.leaveGap(true, LOWER);
        assertEquals(HomeWayOut.Step.WALK_ON, wayOut.leaveGap(false, INSIDE));
        assertEquals(HomeWayOut.Stage.REFILL_PENDING, wayOut.stage());
    }

    @Test
    void 一格都没挖开就出得去了_收起这一段_不补也不记经过() {
        HomeWayOut wayOut = breaching(Map.of(LOWER, "玩家甲"));
        wayOut.exited();
        assertFalse(wayOut.started());
        assertEquals("", wayOut.unfinishedStory());
        assertFalse(wayOut.brokeOthers());
    }

    @Test
    void 拆墙半路收场_一格没挖开_写没拆成_不算拆了别人的() {
        HomeWayOut wayOut = breaching(Map.of(LOWER, "玩家甲", UPPER, "玩家甲"));
        String story = wayOut.unfinishedStory();
        assertTrue(story.contains("一格也没拆成"), story);
        assertFalse(story.contains("：水平拆开") || story.contains("拆开了没补"), "没挖开的不写成拆开了：" + story);
        assertFalse(wayOut.brokeOthers(), "选好了没挖开，不报拆了别人的方块");
    }

    @Test
    void 只挖开脚那层就收场_分开写拆开了没补和没拆成_拆了别人的只算挖开的() {
        HomeWayOut wayOut = breaching(Map.of(UPPER, "玩家甲"));
        wayOut.noteDug(LOWER);
        String story = wayOut.unfinishedStory();
        assertTrue(story.contains("(3, 64, 1) 的 minecraft:oak_planks，拆开了没补"), story);
        assertTrue(story.contains("(3, 65, 1) 的 minecraft:oak_planks 没拆成"), story);
        assertFalse(story.contains("出来"), "还没穿出去，不说出来了：" + story);
        assertFalse(wayOut.brokeOthers(), "别人放的是头那层，没挖开");
        wayOut.noteDug(UPPER);
        assertTrue(wayOut.brokeOthers());
        assertTrue(wayOut.unfinishedStory().contains("（玩家甲 放的），拆开了没补"), wayOut.unfinishedStory());
    }

    @Test
    void 挖开的不是选中的墙_不记() {
        HomeWayOut wayOut = breaching(Map.of());
        wayOut.noteDug(new BlockPos(3, 66, 1));
        assertTrue(wayOut.unfinishedStory().contains("一格也没拆成"), wayOut.unfinishedStory());
    }

    @Test
    void 补完收起这一段_再被困重新找墙_补过的格不算垫路() {
        HomeWayOut wayOut = dugBoth();
        wayOut.leaveGap(true, LOWER);
        wayOut.leaveGap(false, OUTSIDE);
        wayOut.settleCell("已补回");
        wayOut.settleCell("没补回：身上没有 minecraft:oak_planks");
        assertEquals(Optional.empty(), wayOut.refillingCell());
        assertEquals(HomeWayOut.Stage.DONE, wayOut.stage());
        String story = wayOut.story();
        assertTrue(story.contains("(3, 64, 1) 的 minecraft:oak_planks，已补回"), story);
        assertTrue(story.contains("没补回：身上没有 minecraft:oak_planks 出来"), story);
        wayOut.reset();
        assertFalse(wayOut.started(), "收起后再被困，走在家里拆墙还是坑里脱困，由脚下在不在家里重新定");
        assertEquals("", wayOut.unfinishedStory(), "已经记过的经过收场时不再记一遍");
        assertTrue(wayOut.refilledWall(LOWER), "确认晚到时补回的墙不进垫块清单");
        assertFalse(wayOut.refilledWall(OUTSIDE));
    }

    @Test
    void 补到一半被叫停_没补的都记被叫停_收场照实交出经过() {
        HomeWayOut wayOut = dugBoth();
        wayOut.leaveGap(true, LOWER);
        wayOut.leaveGap(false, OUTSIDE);
        wayOut.settleCell("已补回");
        wayOut.stopRefilling(false);
        assertEquals(HomeWayOut.Stage.DONE, wayOut.stage());
        String story = wayOut.unfinishedStory();
        assertTrue(story.contains("(3, 64, 1) 的 minecraft:oak_planks，已补回"), story);
        assertTrue(story.contains("(3, 65, 1) 的 minecraft:oak_planks，没补回：被叫停"), story);
    }

    @Test
    void 只挖开一格就穿出去_只补挖开的那格() {
        HomeWayOut wayOut = breaching(Map.of());
        wayOut.noteDug(LOWER);
        wayOut.exited();
        wayOut.leaveGap(true, LOWER);
        wayOut.leaveGap(false, OUTSIDE);
        assertEquals(Optional.of(LOWER), wayOut.refillingCell());
        wayOut.settleCell("已补回");
        assertEquals(Optional.empty(), wayOut.refillingCell());
        assertFalse(wayOut.refilledWall(UPPER), "头那层本来就没拆开，不补");
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
