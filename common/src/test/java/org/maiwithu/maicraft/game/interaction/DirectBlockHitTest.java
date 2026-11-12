// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

/**
 * 直发包方块命中构造的几何规则：未指明方向时挑朝向眼睛最近的面，自上而下挖掘挑顶面，
 * 部件点击落在部件框内，且任何凸缘形状构造出的发包点离方块中心各轴偏移都严格小于原版服务端上限。
 */
class DirectBlockHitTest {

    private static final BlockPos TARGET = new BlockPos(4, 64, 2);
    private static final double VANILLA_PACKET_LIMIT = 1.0000001D;

    @Test
    void nearestFaceTowardEyeIsChosenWhenNoFaceIsRequested() {
        // 角色站在目标北侧（z 更小）：直发包命中朝北面，落在北面中心。
        Vec3 northEye = new Vec3(4.5, 64.5, 0.2);
        BlockHitResult fromNorth = FirstPersonInteractionTargeting.constructDirectHit(
                northEye, TARGET, null, null, null, false, 6.5);
        assertNotNull(fromNorth);
        assertEquals(Direction.NORTH, fromNorth.getDirection());
        assertEquals(TARGET, fromNorth.getBlockPos());
        assertWithinServerOffsetLimit(fromNorth, TARGET);

        // 角色站在目标正上方：直发包命中朝顶面；preferTopFace 在侧面站位也优先取顶面。
        Vec3 sideEye = new Vec3(2.5, 65.2, 2.5);
        BlockHitResult topPreferred = FirstPersonInteractionTargeting.constructDirectHit(
                sideEye, TARGET, null, null, null, true, 6.5);
        assertNotNull(topPreferred);
        assertEquals(Direction.UP, topPreferred.getDirection());
        assertWithinServerOffsetLimit(topPreferred, TARGET);
    }

    @Test
    void requestedFaceAndSlabBoundsDetermineHitPoint() {
        // 下半砖 [0, 0.5] 点名顶面：命中方向锁定 UP，高度落在半砖顶面 64.5 而不是整格顶面 65.0。
        AABB bottomSlab = new AABB(0.0, 0.0, 0.0, 1.0, 0.5, 1.0);
        Vec3 eye = new Vec3(2.5, 65.62, 2.5);
        BlockHitResult hit = FirstPersonInteractionTargeting.constructDirectHit(
                eye, TARGET, bottomSlab, Direction.UP, null, false, 6.5);
        assertNotNull(hit);
        assertEquals(Direction.UP, hit.getDirection());
        assertEquals(64.5, hit.getLocation().y, 1.0e-6);
        assertWithinServerOffsetLimit(hit, TARGET);
    }

    @Test
    void partHitLandsInsideOverlappingPartAndRejectsDisjointPart() {
        AABB panel = new AABB(4.125, 64.125, 2.0, 4.875, 64.875, 2.125);
        Vec3 eye = new Vec3(4.5, 64.5, 0.5);
        BlockHitResult hit = FirstPersonInteractionTargeting.constructDirectHit(
                eye, TARGET, null, null, panel, false, 6.5);
        assertNotNull(hit);
        assertTrue(panel.inflate(1.0e-3).contains(hit.getLocation()));
        assertWithinServerOffsetLimit(hit, TARGET);

        // 部件框与该格方块形状完全不交（例如面板不在这半格里）：返回空，不胡乱点方块中心。
        AABB bottomHalf = new AABB(0.0, 0.0, 0.0, 1.0, 0.25, 1.0);
        AABB highPanel = new AABB(4.2, 64.6, 2.0, 4.8, 64.9, 2.2);
        assertNull(FirstPersonInteractionTargeting.constructDirectHit(
                eye, TARGET, bottomHalf, null, highPanel, false, 6.5));
    }

    @Test
    void oversizedFlangeHitIsClampedWithinServerPacketLimit() {
        // 链条输送机这类外形伸出格外一格半的轮缘：直接拿轮缘外沿发包会被服务端 handleUseItemOn 丢弃，必须夹回安全偏移内。
        AABB wideFlange = new AABB(-0.75, 0.0, -0.75, 1.75, 1.0, 1.75);
        Vec3 eye = new Vec3(7.0, 64.5, 2.5);
        BlockHitResult constructed = FirstPersonInteractionTargeting.constructDirectHit(
                eye, TARGET, wideFlange, Direction.EAST, null, false, 6.5);
        assertNotNull(constructed);
        assertWithinServerOffsetLimit(constructed, TARGET);

        BlockHitResult rawFlange = new BlockHitResult(new Vec3(5.6, 64.875, 2.5), Direction.UP, TARGET, false);
        BlockHitResult clamped = FirstPersonInteractionTargeting.clampHitForPacket(rawFlange, TARGET);
        assertEquals(Direction.UP, clamped.getDirection());
        assertWithinServerOffsetLimit(clamped, TARGET);
    }

    @Test
    void reachBoundaryRespectsStrictServerLimit() {
        // 目标在 x=4..5，眼睛在 (-1.0, 64.5, 2.5) 时到方块西面 x=4.0 距离正好 5.0 格：
        // 旧服务端 5.5 放行，压到 5.0 严格小于边界时拒绝。
        Vec3 fiveBlocksAway = new Vec3(-1.0, 64.5, 2.5);
        assertNotNull(FirstPersonInteractionTargeting.constructDirectHit(
                fiveBlocksAway, TARGET, null, null, null, false, 5.5));
        assertNull(FirstPersonInteractionTargeting.constructDirectHit(
                fiveBlocksAway, TARGET, null, null, null, false, 5.0));
    }

    private static void assertWithinServerOffsetLimit(BlockHitResult hit, BlockPos pos) {
        Vec3 diff = hit.getLocation().subtract(Vec3.atCenterOf(pos));
        assertTrue(Math.abs(diff.x) < VANILLA_PACKET_LIMIT, "dx=" + diff.x);
        assertTrue(Math.abs(diff.y) < VANILLA_PACKET_LIMIT, "dy=" + diff.y);
        assertTrue(Math.abs(diff.z) < VANILLA_PACKET_LIMIT, "dz=" + diff.z);
    }
}
