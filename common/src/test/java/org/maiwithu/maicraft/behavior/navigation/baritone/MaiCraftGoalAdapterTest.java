// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.navigation.calc.NavGoal;
import org.maiwithu.maicraft.behavior.navigation.calc.StandingDry;

/**
 * 引擎目标的干地收线（离线）：要求站实落地的目标，水面格不再算搜索终点，
 * 引擎收线的那格与到达判据同判一份干地事实，不再出现引擎说到头了、到达又不认的死等。
 * 世界用一张方块表替身摆出来。
 */
class MaiCraftGoalAdapterTest {

    /** 方块表世界：没摆的格是空气；液态按方块自身的流体状态算。 */
    private final class GridWorld implements BlockGetter {
        private final Map<Long, BlockState> cells = new HashMap<>();

        void put(int x, int y, int z, BlockState state) {
            cells.put(new BlockPos(x, y, z).asLong(), state);
        }

        @Override public BlockState getBlockState(BlockPos pos) {
            return cells.getOrDefault(pos.asLong(), Blocks.AIR.defaultBlockState());
        }

        @Override public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
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

    @BeforeAll
    static void 引导方块注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 要站实的目标_水面格不算搜索终点() {
        // 一片水：x=5 那柱水面在 y=64，旁边 x=0 是石头地；目的地要站在 (0,65) 附近的干地上。
        GridWorld world = new GridWorld();
        for (int x = 3; x <= 8; x++) {
            world.put(x, 63, 0, Blocks.STONE.defaultBlockState());
            world.put(x, 64, 0, Blocks.WATER.defaultBlockState());
        }
        world.put(0, 64, 0, Blocks.STONE.defaultBlockState());
        MaiCraftGoalAdapter goal = new MaiCraftGoalAdapter(
                new StandingDry(NavGoal.near(new BlockPos(0, 65, 0), 8.0)), () -> world);
        // 水面上的格子位置条件本来满足，但人在水里永远过不了到达判据，搜索不再把它当终点。
        assertFalse(goal.isInGoal(5, 65, 0), "水面格不是要站实的目标的终点");
        assertFalse(goal.isInGoal(5, 64, 0), "水里那格也不是");
        assertTrue(goal.isInGoal(0, 65, 0), "干地上照旧是终点");
    }

    @Test
    void 采矿和捡东西的站位_水底的格照样是搜索终点() {
        // 湖底的黏土：(5,62,0) 是黏土，上面两格是水，站位 (5,63,0) 泡在水里。站实但不要干地的目标水底站位照样是终点，
        // 不然湖底、河床的东西全报走不到；同一格套上出行目的地的干地要求就不算。
        GridWorld world = new GridWorld();
        world.put(5, 62, 0, Blocks.CLAY.defaultBlockState());
        world.put(5, 63, 0, Blocks.WATER.defaultBlockState());
        world.put(5, 64, 0, Blocks.WATER.defaultBlockState());
        NavGoal stance = NavGoal.exact(new BlockPos(5, 63, 0));
        assertTrue(new MaiCraftGoalAdapter(stance, () -> world).isInGoal(5, 63, 0), "水底站位是终点");
        NavGoal field = NavGoal.composite(List.of(stance, NavGoal.exact(new BlockPos(6, 63, 0))));
        assertTrue(new MaiCraftGoalAdapter(field, () -> world).isInGoal(5, 63, 0), "一组采矿、捡掉落物的站位同样");
        assertFalse(new MaiCraftGoalAdapter(new StandingDry(stance), () -> world).isInGoal(5, 63, 0),
                "出行目的地要干地：水底那格不是终点");
    }

    @Test
    void 宽松目标_水里漂进范围照旧算到() {
        GridWorld world = new GridWorld();
        world.put(5, 63, 0, Blocks.STONE.defaultBlockState());
        world.put(5, 64, 0, Blocks.WATER.defaultBlockState());
        // 只到那一柱列的目标本来允许水里漂到：干地核验不加在这类目标上。
        MaiCraftGoalAdapter goal = new MaiCraftGoalAdapter(NavGoal.column(5, 0), () -> world);
        assertTrue(goal.isInGoal(5, 64, 0));
    }

    @Test
    void 世界还没读到时_只按位置条件放行_干地由到达判据兜底() {
        MaiCraftGoalAdapter goal = new MaiCraftGoalAdapter(
                new StandingDry(NavGoal.exact(new BlockPos(5, 65, 0))), () -> null);
        assertTrue(goal.isInGoal(5, 65, 0));
        assertFalse(goal.isInGoal(5, 66, 0));
    }

    @Test
    void 干地与否是世界状态_不进目标指纹() {
        GridWorld dry = new GridWorld();
        dry.put(5, 64, 0, Blocks.STONE.defaultBlockState());
        GridWorld wet = new GridWorld();
        wet.put(5, 63, 0, Blocks.STONE.defaultBlockState());
        wet.put(5, 64, 0, Blocks.WATER.defaultBlockState());
        NavGoal delegate = new StandingDry(NavGoal.exact(new BlockPos(5, 65, 0)));
        // 同一个目标每刻重新包装要认得出是同一个搜索请求，不能因为脚下干了湿了就让旧失败作废重来。
        assertTrue(new MaiCraftGoalAdapter(delegate, () -> dry)
                .equals(new MaiCraftGoalAdapter(delegate, () -> wet)));
    }
}
