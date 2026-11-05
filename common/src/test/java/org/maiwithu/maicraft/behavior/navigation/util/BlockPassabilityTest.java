// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.util;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 寻路共用的方块通行判断里，传送门内格不能当普通可走格（实机 198：出发点紧邻下界门时
 * 路线穿门抄近路，身体被原版机制传到另一维度）；走向点名要进的门格由寻路的目标编译放行，
 * 那条放行名单的测试在寻路目标一侧，这里守的是"默认一律不可走、不可站"的底。
 */
class BlockPassabilityTest {

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

    @Test
    void portalCellsAreNeverWalkableByDefault() {
        Grid world = new Grid();
        world.put(new BlockPos(3, 64, 3), Blocks.NETHER_PORTAL.defaultBlockState());
        world.put(new BlockPos(6, 64, 3), Blocks.END_PORTAL.defaultBlockState());
        world.put(new BlockPos(9, 64, 3), Blocks.STONE.defaultBlockState());

        assertFalse(BlockPassability.canWalkThrough(world, new BlockPos(3, 64, 3)),
                "下界门内格默认不可走：走进去会被传去另一维度");
        assertFalse(BlockPassability.canWalkThrough(world, new BlockPos(6, 64, 3)),
                "末地门格同样默认不可走");
        assertFalse(BlockPassability.isStandable(world, new BlockPos(3, 64, 3)),
                "门格不是能站的格子：不做站立候选");
        assertFalse(BlockPassability.canWalkThrough(world, new BlockPos(9, 64, 3)),
                "石头当然不可穿，判断没有顺手放空");
        assertTrue(BlockPassability.canWalkThrough(world, new BlockPos(9, 65, 3)),
                "门格上方与普通空气的判定不受影响");
    }

    @Test
    void fireStaysBlockedWhileNetherPortalJoinedTheList() {
        Grid world = new Grid();
        world.put(new BlockPos(3, 64, 3), Blocks.FIRE.defaultBlockState());
        assertFalse(BlockPassability.canWalkThrough(world, new BlockPos(3, 64, 3)),
                "原有的危险方块拦截没有被门格改动挤掉");
    }

    /** 造一格流动水：水面含量低于满格就是流动（会推开角色的那种水）。 */
    private static BlockState flowingWater() {
        return Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3);
    }

    @Test
    void flowingWaterIsWaterForTheDryStandableJudgement() {
        // 流动水没有自己的方块类型：按方块类型判水认不出它，水面一段流动的水被当成实地，靠岸点挑到了水上。
        assertTrue(BlockPassability.isWater(flowingWater()), "流动水也是水");
        assertTrue(BlockPassability.isWater(Blocks.WATER.defaultBlockState()), "水源也是水");
        assertFalse(BlockPassability.isWater(Blocks.ICE.defaultBlockState()), "冰不算水：冰面另按冻住的水分类");

        Grid world = new Grid();
        world.put(new BlockPos(3, 64, 3), flowingWater());
        assertFalse(BlockPassability.isDryStandable(world, new BlockPos(3, 65, 3)),
                "流动水面不构成干地");
    }

    @Test
    void functionBackedStandableMatchesDryStandable() {
        BlockPos feet = new BlockPos(3, 65, 3);
        Map<BlockPos, BlockState> overWater = Map.of(
                feet, Blocks.AIR.defaultBlockState(),
                feet.above(), Blocks.AIR.defaultBlockState(),
                feet.below(), flowingWater());
        Map<BlockPos, BlockState> overStone = Map.of(
                feet, Blocks.AIR.defaultBlockState(),
                feet.above(), Blocks.AIR.defaultBlockState(),
                feet.below(), Blocks.STONE.defaultBlockState());
        assertFalse(BlockPassability.standable(overWater::get, feet), "流动水面不是干燥可站的脚位");
        assertTrue(BlockPassability.standable(overStone::get, feet), "石头地上的脚位干燥可站");
        // 脚、头、地面有一格没读到（null）时按不能站处理：不凭空给出能站的结论。
        assertFalse(BlockPassability.standable(
                        cell -> cell.equals(feet.below()) ? Blocks.STONE.defaultBlockState() : null, feet),
                "脚和头没读到时按不能站处理");
    }

    /** 小片世界替身：按坐标记方块，没记的当空气。 */
    @Test
    void standingOnTopOfALowBlockCountsTheCellAbove() {
        // 站在箱子、附魔台顶上：脚位是上面那一格，寻路才有起点（原先读成方块里面那一格，人下不来）。
        Grid world = new Grid();
        world.put(new BlockPos(3, 64, 3), Blocks.CHEST.defaultBlockState());
        world.put(new BlockPos(6, 64, 3), Blocks.ENCHANTING_TABLE.defaultBlockState());
        world.put(new BlockPos(9, 64, 3), Blocks.WHITE_CARPET.defaultBlockState());
        assertEquals(new BlockPos(3, 65, 3), BlockPassability.playerFeet(world, 3.5, 64.8749999, 3.5),
                "箱子顶（0.875 高，坐标差一点点也认）");
        assertEquals(new BlockPos(6, 65, 3), BlockPassability.playerFeet(world, 6.5, 64.75, 3.5), "附魔台顶（0.75 高）");
        assertEquals(new BlockPos(9, 64, 3), BlockPassability.playerFeet(world, 9.5, 64.0625, 3.5),
                "地毯不到半格：人还在地毯这一格");
    }

    @Test
    void standingOnSoulSandFarmlandOrBottomSlabCountsTheCellAbove() {
        // 站在灵魂沙、泥（14/16 高）、耕地、土径（15/16 高）、下半砖上：人的 y 比上面那一格低一点，
        // 脚位仍是上面那一格；寻死判走到站位、站进格子都按这个脚位，和寻路判已经到了同一个口径。
        Grid world = new Grid();
        world.put(new BlockPos(0, 64, 0), Blocks.SOUL_SAND.defaultBlockState());
        world.put(new BlockPos(2, 64, 0), Blocks.MUD.defaultBlockState());
        world.put(new BlockPos(4, 64, 0), Blocks.FARMLAND.defaultBlockState());
        world.put(new BlockPos(6, 64, 0), Blocks.DIRT_PATH.defaultBlockState());
        world.put(new BlockPos(8, 64, 0), Blocks.STONE_SLAB.defaultBlockState());
        assertEquals(new BlockPos(0, 65, 0), BlockPassability.playerFeet(world, 0.5, 64.875, 0.5), "灵魂沙");
        assertEquals(new BlockPos(2, 65, 0), BlockPassability.playerFeet(world, 2.5, 64.875, 0.5), "泥");
        assertEquals(new BlockPos(4, 65, 0), BlockPassability.playerFeet(world, 4.5, 64.9375, 0.5), "耕地");
        assertEquals(new BlockPos(6, 65, 0), BlockPassability.playerFeet(world, 6.5, 64.9375, 0.5), "土径");
        assertEquals(new BlockPos(8, 65, 0), BlockPassability.playerFeet(world, 8.5, 64.5, 0.5), "下半砖");
    }

    @Test
    void lowFlatTopsCountAsGroundByShape() {
        // 实机 11-05：起点旁边一张床，寻路把床当墙，从床底挖过去；从柱顶往床上落，先在床上垫一块圆石。
        // 顶面平、盖满整格、高出 2/16 又不超过一步高的都按形状认作地面，不写方块名单。
        assertEquals(9.0 / 16, BlockPassability.halfHighTop(Blocks.RED_BED.defaultBlockState()), 1.0e-9, "床脚那半");
        assertEquals(9.0 / 16, BlockPassability.halfHighTop(
                Blocks.RED_BED.defaultBlockState().setValue(BedBlock.PART, BedPart.HEAD)), 1.0e-9, "床头那半");
        assertEquals(9.0 / 16, BlockPassability.halfHighTop(Blocks.STONECUTTER.defaultBlockState()), 1.0e-9);
        assertEquals(6.0 / 16, BlockPassability.halfHighTop(Blocks.DAYLIGHT_DETECTOR.defaultBlockState()), 1.0e-9);
        assertEquals(3.0 / 16, BlockPassability.halfHighTop(Blocks.OAK_TRAPDOOR.defaultBlockState()), 1.0e-9,
                "关着、贴底的活板门");
        assertEquals(4.0 / 16, BlockPassability.halfHighTop(
                Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 3)), 1.0e-9, "三层雪");
        assertEquals(8.0 / 16, BlockPassability.halfHighTop(
                Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 5)), 1.0e-9, "五层雪");
        assertEquals(7.0 / 16, BlockPassability.halfHighTop(
                Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false)), 1.0e-9, "熄灭的营火");
        assertEquals(0.5, BlockPassability.halfHighTop(Blocks.STONE_SLAB.defaultBlockState()), 1.0e-9,
                "下半台阶照旧按半格");

        Grid world = new Grid();
        world.put(new BlockPos(3, 64, 3), Blocks.RED_BED.defaultBlockState());
        assertTrue(BlockPassability.canWalkOn(world, new BlockPos(3, 64, 3)), "床能站");
        assertTrue(BlockPassability.isStandable(world, new BlockPos(3, 65, 3)), "床上面那一格是能站的脚位");
    }

    @Test
    void burningCampfireAndSummoningShriekerAreNotGround() {
        // 点着的营火站上去会烫，能招来监守者的幽匿尖啸体踩上去会叫：按方块状态的属性排除，熄灭、不能招的照常能站。
        assertTrue(Double.isNaN(BlockPassability.halfHighTop(Blocks.CAMPFIRE.defaultBlockState())), "点着的营火");
        assertTrue(Double.isNaN(BlockPassability.halfHighTop(Blocks.SOUL_CAMPFIRE.defaultBlockState())));
        assertTrue(Double.isNaN(BlockPassability.halfHighTop(Blocks.SCULK_SHRIEKER.defaultBlockState()
                .setValue(BlockStateProperties.CAN_SUMMON, true))), "能招来监守者的尖啸体");
        assertEquals(0.5, BlockPassability.halfHighTop(Blocks.SCULK_SHRIEKER.defaultBlockState()
                .setValue(BlockStateProperties.CAN_SUMMON, false)), 1.0e-9, "玩家摆的尖啸体不招监守者");
        Grid world = new Grid();
        world.put(new BlockPos(3, 64, 3), Blocks.CAMPFIRE.defaultBlockState());
        assertFalse(BlockPassability.canWalkOn(world, new BlockPos(3, 64, 3)), "点着的营火不当地面");
    }

    @Test
    void thinLayersAndTallOrPartialTopsStayAsBefore() {
        // 地毯、一两层雪、中继器这类不高于 2/16 的薄层照旧；高过一步的、顶面没盖满整格的、开着的或贴顶的活板门不算矮平顶。
        assertTrue(Double.isNaN(BlockPassability.halfHighTop(Blocks.WHITE_CARPET.defaultBlockState())), "地毯");
        assertTrue(Double.isNaN(BlockPassability.halfHighTop(
                Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 2))), "两层雪");
        assertTrue(Double.isNaN(BlockPassability.halfHighTop(Blocks.REPEATER.defaultBlockState())), "中继器");
        assertTrue(Double.isNaN(BlockPassability.halfHighTop(Blocks.COMPARATOR.defaultBlockState())), "比较器");
        assertTrue(Double.isNaN(BlockPassability.halfHighTop(Blocks.ENCHANTING_TABLE.defaultBlockState())), "高过一步");
        assertTrue(Double.isNaN(BlockPassability.halfHighTop(Blocks.CAKE.defaultBlockState())), "顶面没盖满整格");
        assertTrue(Double.isNaN(BlockPassability.halfHighTop(
                Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.OPEN, true))), "开着的活板门");
        assertTrue(Double.isNaN(BlockPassability.halfHighTop(
                Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.HALF, Half.TOP))), "贴顶的活板门");
        assertTrue(Double.isNaN(BlockPassability.halfHighTop(Blocks.STONE.defaultBlockState())), "整块");
    }

    @Test
    void standingOnLowFlatTopsCountsTheCellAbove() {
        // 站在床、阳光探测器、关着的活板门上：脚位是上面那一格，和寻路把矮平顶当成站在它上面一格一致。
        Grid world = new Grid();
        world.put(new BlockPos(0, 64, 0), Blocks.RED_BED.defaultBlockState());
        world.put(new BlockPos(2, 64, 0), Blocks.DAYLIGHT_DETECTOR.defaultBlockState());
        world.put(new BlockPos(4, 64, 0), Blocks.OAK_TRAPDOOR.defaultBlockState());
        assertEquals(new BlockPos(0, 65, 0), BlockPassability.playerFeet(world, 0.5, 64.5625, 0.5), "床");
        assertEquals(new BlockPos(2, 65, 0), BlockPassability.playerFeet(world, 2.5, 64.375, 0.5), "阳光探测器");
        assertEquals(new BlockPos(4, 65, 0), BlockPassability.playerFeet(world, 4.5, 64.1875, 0.5), "关着的活板门");
    }

    @Test
    void magmaBlockHurtsToStandOnAndIsNotGround() {
        // 岩浆块站上去烫脚：挖台阶的下一级脚下是它就拐开，寻路也不把它当地面；同是下界方块的下界岩照常能站。
        Grid world = new Grid();
        world.put(new BlockPos(0, 64, 0), Blocks.MAGMA_BLOCK.defaultBlockState());
        world.put(new BlockPos(2, 64, 0), Blocks.NETHERRACK.defaultBlockState());
        assertTrue(BlockPassability.hurtsToStandOn(Blocks.MAGMA_BLOCK.defaultBlockState()));
        assertFalse(BlockPassability.hurtsToStandOn(Blocks.NETHERRACK.defaultBlockState()));
        assertFalse(BlockPassability.canWalkOn(world, new BlockPos(0, 64, 0)), "岩浆块不当地面");
        assertTrue(BlockPassability.canWalkOn(world, new BlockPos(2, 64, 0)), "下界岩照常能站");
    }

    private static final class Grid implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        void put(BlockPos pos, BlockState state) {
            blocks.put(pos.immutable(), state);
        }

        BlockState at(BlockPos pos) {
            return blocks.getOrDefault(pos.immutable(), Blocks.AIR.defaultBlockState());
        }

        @Override public BlockState getBlockState(BlockPos pos) {
            return at(pos);
        }

        @Override public FluidState getFluidState(BlockPos pos) {
            return at(pos).getFluidState();
        }

        @Override public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override public int getHeight() {
            return 384;
        }

        @Override public int getMinBuildHeight() {
            return -64;
        }
    }
}
