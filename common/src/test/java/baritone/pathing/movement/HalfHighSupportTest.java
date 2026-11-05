// SPDX-License-Identifier: GPL-3.0-only
package baritone.pathing.movement;

import baritone.pathing.precompute.Ternary;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 寻路把床、石切机这类矮平顶当成能站的地面，站立高度按下半台阶算：从整块地面一步迈上去，站在上面只能登上
 * 另一块矮平顶，而且实际要抬的高度够得着。
 */
class HalfHighSupportTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 床和石切机按形状认作能站_点着的营火不算() {
        assertEquals(Ternary.YES, MovementHelper.canWalkOnBlockState(Blocks.RED_BED.defaultBlockState()));
        assertEquals(Ternary.YES, MovementHelper.canWalkOnBlockState(Blocks.STONECUTTER.defaultBlockState()));
        assertNotEquals(Ternary.YES, MovementHelper.canWalkOnBlockState(Blocks.CAMPFIRE.defaultBlockState()));
        assertTrue(MovementHelper.isHalfHigh(Blocks.RED_BED.defaultBlockState()));
        assertTrue(MovementHelper.isHalfHigh(Blocks.STONE_SLAB.defaultBlockState()), "下半台阶照旧");
        assertFalse(MovementHelper.isHalfHigh(Blocks.STONE.defaultBlockState()));
    }

    @Test
    void 从地面迈上床不按跳_站在床上往上不算迈() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState bed = Blocks.RED_BED.defaultBlockState();
        BlockState slab = Blocks.STONE_SLAB.defaultBlockState();
        assertTrue(MovementHelper.stepsUpWithoutJump(stone, bed), "从地面一步迈上床");
        assertTrue(MovementHelper.stepsUpWithoutJump(stone, slab), "下半台阶照旧");
        assertFalse(MovementHelper.stepsUpWithoutJump(bed, bed), "床到高一格的床要跳");
        assertFalse(MovementHelper.stepsUpWithoutJump(stone, stone), "整块到整块要跳");
    }

    @Test
    void 站在床和下半台阶上不起跳跑酷() {
        assertTrue(MovementHelper.parkourTakeoffBlocked(Blocks.RED_BED.defaultBlockState()));
        assertTrue(MovementHelper.parkourTakeoffBlocked(Blocks.DAYLIGHT_DETECTOR.defaultBlockState()));
        assertTrue(MovementHelper.parkourTakeoffBlocked(Blocks.STONE_SLAB.defaultBlockState()));
        assertTrue(MovementHelper.parkourTakeoffBlocked(Blocks.OAK_STAIRS.defaultBlockState()));
        assertFalse(MovementHelper.parkourTakeoffBlocked(Blocks.STONE.defaultBlockState()));
    }

    @Test
    void 站在矮平顶上只能登上够得着的另一块矮平顶() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState bed = Blocks.RED_BED.defaultBlockState();
        BlockState slab = Blocks.STONE_SLAB.defaultBlockState();
        BlockState snow3 = Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 3);
        BlockState detector = Blocks.DAYLIGHT_DETECTOR.defaultBlockState();
        BlockState unlitCampfire = Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false);
        assertTrue(MovementHelper.ascendReachable(stone, bed), "从地面迈上床");
        assertTrue(MovementHelper.ascendReachable(stone, stone), "整块到整块照旧");
        assertFalse(MovementHelper.ascendReachable(bed, stone), "从床上跳不上高一格的整块（要抬 1.44 格）");
        assertTrue(MovementHelper.ascendReachable(bed, bed), "床到高一格的床：抬一格");
        assertTrue(MovementHelper.ascendReachable(slab, bed), "下半台阶到高一格的床：抬 1.06 格");
        assertTrue(MovementHelper.ascendReachable(bed, slab), "床到高一格的下半台阶");
        assertTrue(MovementHelper.ascendReachable(detector, unlitCampfire), "阳光探测器到营火：抬 1.06 格");
        assertFalse(MovementHelper.ascendReachable(snow3, bed), "三层雪到高一格的床：要抬 1.31 格，跳不上");
        assertFalse(MovementHelper.ascendReachable(detector, bed), "阳光探测器到高一格的床：要抬 1.19 格，跳不稳");
    }
}
