// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.drops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 走开方向挑选：背对丢出点起步，同时避开刚丢落点与实心障碍方块，绕不开就如实硬走。 */
class ClientStepsAsideTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void walksStraightBackWhenTheWayIsClear() {
        // 角色在丢出点后方一格：背对方向是 -Z，直走不踩落点。
        Vec3 direction = ClientStepsAside.steppingDirection(0, -1, candidate -> false);
        assertEquals(0.0, direction.x, 1.0e-6);
        assertEquals(-1.0, direction.z, 1.0e-6);
    }

    @Test
    void sidestepsWhenStraightBackStepsIntoTheLanding() {
        // 正后方就是刚丢的落点：往侧向绕，仍然不朝丢出点走。
        Vec3 direction = ClientStepsAside.steppingDirection(0, -1, candidate ->
                Math.abs(candidate.x) < 0.01 && candidate.z < 0);
        assertTrue(direction.z >= 0 || Math.abs(direction.x) > 0.01,
                "不该再朝正后方的落点走：" + direction);
    }

    @Test
    void fallsBackToStraightBackWhenEveryCandidateHitsTheLanding() {
        // 四面都是落点（被围住）：回原方向硬走，到期走不出去由期限如实报失败。
        Vec3 direction = ClientStepsAside.steppingDirection(0, -1, candidate -> true);
        assertEquals(0.0, direction.x, 1.0e-6);
        assertEquals(-1.0, direction.z, 1.0e-6);
    }

    @Test
    void noHorizontalOffsetPicksAFixedDirection() {
        // 角色还站在丢出点上没有水平位移：随便朝一个固定方向走，不除以零。
        Vec3 direction = ClientStepsAside.steppingDirection(0, 0, candidate -> false);
        assertEquals(1.0, direction.length(), 1.0e-6);
    }

    @Test
    void sidestepsAroundSolidWallDirectlyBehindPlayer() {
        // 窄池边正后方（-Z）是一堵墙：落点不在正后方，但障碍判定拦住正后方时顺偏转表侧绕可走方向。
        Vec3 direction = ClientStepsAside.steppingDirection(0, -1,
                candidate -> false,
                candidate -> candidate.z < -0.5);
        assertTrue(direction.z >= -0.5 && Math.abs(direction.x) > 0.5,
                "正后方有墙时应侧绕开而不是硬顶墙：" + direction);
    }

    @Test
    void avoidsBothFreshDropsAndWallsSimultaneously() {
        // 正后方是墙，右后（+X）是刚丢落点，只有左后/左侧（-X）既不撞墙也不踩落点。
        Vec3 direction = ClientStepsAside.steppingDirection(0, -1,
                candidate -> candidate.x > 0.1,
                candidate -> Math.abs(candidate.x) < 0.2 && candidate.z < 0);
        assertTrue(direction.x < -0.1, "应同时避开正后方墙体与右侧刚丢落点：" + direction);
    }

    @Test
    void detectsTwoBlockWallAsBlockedAndOneBlockBankAsStepUp() {
        // 摆一块平地：脚在 (0, 64, 0)，正北 (0, 64, -1) 与 (0, 65, -1) 是两格高石墙；正东 (1, 64, 0) 是一格高池岸。
        MiniWorld world = new MiniWorld();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                world.blocks.put(new BlockPos(x, 63, z), Blocks.STONE.defaultBlockState());
            }
        }
        world.blocks.put(new BlockPos(0, 64, -1), Blocks.STONE.defaultBlockState());
        world.blocks.put(new BlockPos(0, 65, -1), Blocks.STONE.defaultBlockState());
        world.blocks.put(new BlockPos(1, 64, 0), Blocks.STONE.defaultBlockState());

        Vec3 feet = new Vec3(0.5, 64.0, 0.5);
        assertTrue(ClientStepsAside.directionBlocked(world, feet, new Vec3(0, 0, -1)), "两格高墙应判为挡住");
        assertFalse(ClientStepsAside.directionBlocked(world, feet, new Vec3(0, 0, 1)), "开阔平地应可通畅退开");
        assertFalse(ClientStepsAside.directionBlocked(world, feet, new Vec3(1, 0, 0)), "一格高池岸可跳上，不算堵死");
        assertTrue(ClientStepsAside.needsStepUpJump(world, feet, new Vec3(1, 0, 0)), "一格高池岸应触发跳跃输入");
    }

    @Test
    void returnsEmptyInsteadOfSteppingIntoLavaOrDeepDropWhenSurroundedByHazards() {
        // 角色站在一格孤岛上，四面全是岩浆或 4 格深渊：所有方向均为 HAZARDOUS，选向必须返回空而不是硬走/起跳入坑。
        MiniWorld world = new MiniWorld();
        world.blocks.put(new BlockPos(0, 63, 0), Blocks.STONE.defaultBlockState());
        world.blocks.put(new BlockPos(0, 63, -1), Blocks.LAVA.defaultBlockState());
        world.blocks.put(new BlockPos(0, 64, -1), Blocks.LAVA.defaultBlockState());
        world.blocks.put(new BlockPos(1, 63, 0), Blocks.LAVA.defaultBlockState());
        world.blocks.put(new BlockPos(-1, 63, 0), Blocks.LAVA.defaultBlockState());
        // 南侧是 4 格深坑（Y=59 才见底，超过 MAX_SAFE_DROP=3）。
        world.blocks.put(new BlockPos(0, 59, 1), Blocks.STONE.defaultBlockState());

        Vec3 feet = new Vec3(0.5, 64.0, 0.5);
        assertEquals(ClientStepsAside.PathClearance.HAZARDOUS,
                ClientStepsAside.directionClearance(world, feet, new Vec3(0, 0, -1)), "正后方岩浆应判 HAZARDOUS");
        assertEquals(ClientStepsAside.PathClearance.HAZARDOUS,
                ClientStepsAside.directionClearance(world, feet, new Vec3(0, 0, 1)), "四格深坑应判 HAZARDOUS");

        Optional<Vec3> chosen = ClientStepsAside.chooseSteppingDirection(0, -1,
                candidate -> false,
                candidate -> ClientStepsAside.directionClearance(world, feet, candidate));
        assertTrue(chosen.isEmpty(), "四周都是岩浆或深坑时绝不能硬选一个方向走进去：" + chosen);
    }

    @Test
    void tracksStepUpCursorForRisingSlopeAndDeepPitBehindBank() {
        // 东侧是逐级上升的一格缓坡：(1,64,0) 升到 Y=65，再 (2,65,0) 升到 Y=66，脚高随台阶上升后仍畅通。
        // 西侧是一格池岸 (-1,64,0) 后紧跟 4 格深坑（(-2,65,0) 往下 4 格无落脚面），脚高升到 Y=65 后必须检出深坑危险。
        MiniWorld world = new MiniWorld();
        world.blocks.put(new BlockPos(0, 63, 0), Blocks.STONE.defaultBlockState());
        world.blocks.put(new BlockPos(1, 63, 0), Blocks.STONE.defaultBlockState());
        world.blocks.put(new BlockPos(1, 64, 0), Blocks.STONE.defaultBlockState());
        world.blocks.put(new BlockPos(2, 64, 0), Blocks.STONE.defaultBlockState());
        world.blocks.put(new BlockPos(2, 65, 0), Blocks.STONE.defaultBlockState());

        world.blocks.put(new BlockPos(-1, 63, 0), Blocks.STONE.defaultBlockState());
        world.blocks.put(new BlockPos(-1, 64, 0), Blocks.STONE.defaultBlockState());
        world.blocks.put(new BlockPos(-2, 60, 0), Blocks.STONE.defaultBlockState());

        Vec3 feet = new Vec3(0.5, 64.0, 0.5);
        assertEquals(ClientStepsAside.PathClearance.CLEAR,
                ClientStepsAside.directionClearance(world, feet, new Vec3(1, 0, 0)),
                "连续一格上升缓坡应随脚高更新判为 CLEAR，不误判为两格墙");
        assertEquals(ClientStepsAside.PathClearance.HAZARDOUS,
                ClientStepsAside.directionClearance(world, feet, new Vec3(-1, 0, 0)),
                "一格池岸后紧跟四格深坑应判为 HAZARDOUS，防止跳上池沿后直接坠坑");
    }

    /** 离线极简世界替身：仅存放摆放好的方块，其余为空气。 */
    private static final class MiniWorld implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    }
}
