// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 对液体的第一人称命中（实机 237 残余：相邻水面坐标有的能出手、有的报"看不到任何一面"）：
 * 水面是一整片，射线要到远处水格的表面会先擦过近处水格的水面——近处的水不挡视线，
 * 挡住视线的只有实心方块。水面一高一低（近处满格、远处流动的低水面）也按同一条规则判。
 */
class LiquidSurfaceHitTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 替身世界：没摆的格子都是空气；射线与方块形状读真实原版规则。 */
    private static final class World implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        void put(int x, int y, int z, BlockState state) {
            blocks.put(new BlockPos(x, y, z), state);
        }

        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    }

    /** 一条水道：岸在 z=-47 一侧，水面格子沿 x 摆开，顶面在 y=65（水格占 y=64）。 */
    private static World channel() {
        World world = new World();
        for (int x = 79; x <= 84; x++) {
            world.put(x, 63, -49, Blocks.DIRT.defaultBlockState());
            world.put(x, 64, -49, Blocks.WATER.defaultBlockState());
        }
        return world;
    }

    /** 视线：眼睛指向目标格表面上的一个点，返回方向向量。 */
    private static Vec3 directionTo(Vec3 eye, Vec3 surfacePoint) {
        return surfacePoint.subtract(eye);
    }

    @Test
    void aFartherWaterCellPastNearerWaterIsStillPointedAt() {
        // 实机几何：角色站水道边，(80,64,-49) 能出手，隔两格的 (82,64,-49) 却报看不到。
        // 射线到远处水格的表面先擦过近处水格的水面（在 (81,64,-49) 的水面扎进去）：
        // 近处的水不挡视线，照样判得到远处水格的表面。
        World world = channel();
        BlockPos target = new BlockPos(82, 64, -49);
        Vec3 eye = new Vec3(80.5, 65.62, -48.5);
        Vec3 surfacePoint = new Vec3(82.5, 64.2, -48.6);
        BlockHitResult whole = world.clip(new ClipContext(eye,
                eye.add(directionTo(eye, surfacePoint).normalize().scale(4.5)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, CollisionContext.empty()));
        assertEquals(new BlockPos(81, 64, -49), whole.getBlockPos(),
                "替身几何要先成立：整格射线先碰到近处水格的水面");
        VoxelShape fluid = world.getBlockState(target).getFluidState().getShape(world, target);
        BlockHitResult hit = FirstPersonInteractionTargeting.liquidSurfaceHit(world, eye,
                directionTo(eye, surfacePoint), target, fluid, 4.5, null, CollisionContext.empty());
        assertNotNull(hit, "隔着近处水面也判得到远处水格的表面");
        assertEquals(target, hit.getBlockPos());
    }

    @Test
    void aLowerFlowingSurfacePastFullWaterIsStillPointedAt() {
        // 水面一高一低：近处是满格水源（水面约 64.89），远处是流动水（水面约 64.44）。
        // 朝低水面瞄，射线先穿过近处满格水的水面高度，但不该因此把远处的低水面判成看不到。
        World world = channel();
        BlockPos target = new BlockPos(83, 64, -49);
        world.put(target.getX(), 64, target.getZ(), Blocks.WATER.defaultBlockState()
                .setValue(LiquidBlock.LEVEL, 4));
        Vec3 eye = new Vec3(80.5, 65.62, -48.5);
        Vec3 surfacePoint = new Vec3(83.8, 64.42, -48.6);
        BlockHitResult whole = world.clip(new ClipContext(eye,
                eye.add(directionTo(eye, surfacePoint).normalize().scale(4.5)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, CollisionContext.empty()));
        assertEquals(new BlockPos(82, 64, -49), whole.getBlockPos(),
                "替身几何要先成立：整格射线先碰到近处满格水的水面");
        VoxelShape fluid = world.getBlockState(target).getFluidState().getShape(world, target);
        BlockHitResult hit = FirstPersonInteractionTargeting.liquidSurfaceHit(world, eye,
                directionTo(eye, surfacePoint), target, fluid, 4.5, null, CollisionContext.empty());
        assertNotNull(hit, "近处满格水不挡远处的低水面");
        assertEquals(target, hit.getBlockPos());
    }

    @Test
    void aSolidBlockInFrontStillBlocksTheSight() {
        // 挡视线的只有实心方块：近处水道里砌起一块石头，射线先撞上它，远处的水面就是看不到。
        World world = channel();
        world.put(81, 64, -49, Blocks.STONE.defaultBlockState());
        BlockPos target = new BlockPos(82, 64, -49);
        Vec3 eye = new Vec3(80.5, 65.62, -48.5);
        Vec3 surfacePoint = new Vec3(82.5, 64.2, -48.6);
        VoxelShape fluid = world.getBlockState(target).getFluidState().getShape(world, target);
        BlockHitResult hit = FirstPersonInteractionTargeting.liquidSurfaceHit(world, eye,
                directionTo(eye, surfacePoint), target, fluid, 4.5, null, CollisionContext.empty());
        assertNull(hit, "实心方块挡在前面就是看不到");
    }

    @Test
    void aimingDownAtTheSurfaceSeesTheTopFace() {
        // 从上方压低视线瞄近处一格的水面：命中就是水面向上的那一面（点名顶面时认这一条）。
        World world = channel();
        BlockPos target = new BlockPos(80, 64, -49);
        Vec3 eye = new Vec3(80.5, 65.62, -48.5);
        Vec3 surfacePoint = new Vec3(80.7, 64.8, -48.8);
        VoxelShape fluid = world.getBlockState(target).getFluidState().getShape(world, target);
        BlockHitResult hit = FirstPersonInteractionTargeting.liquidSurfaceHit(world, eye,
                directionTo(eye, surfacePoint), target, fluid, 4.5, Direction.UP, CollisionContext.empty());
        assertNotNull(hit);
        assertEquals(target, hit.getBlockPos());
        assertEquals(Direction.UP, hit.getDirection());
    }

    @Test
    void beyondReachIsNotPointedAt() {
        World world = channel();
        BlockPos target = new BlockPos(84, 64, -49);
        Vec3 eye = new Vec3(80.5, 65.62, -48.5);
        Vec3 surfacePoint = new Vec3(84.5, 64.4, -48.6);
        VoxelShape fluid = world.getBlockState(target).getFluidState().getShape(world, target);
        BlockHitResult hit = FirstPersonInteractionTargeting.liquidSurfaceHit(world, eye,
                directionTo(eye, surfacePoint), target, fluid, 2.0, null, CollisionContext.empty());
        assertNull(hit, "超出触及距离的水面不算指到");
    }
}
