// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import baritone.api.IBaritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.movements.MovementTraverse;
import baritone.utils.pathing.PathBase;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 坑边格沿算路起点与直线平滑守护测试：验证角色踩在 1×1 坑边（着地但脚下体素为空）时，
 * 支撑格探测能找出碰撞箱实际搭着的邻格，且地面平滑绝不把首步错误合并成会当场自毁的直线段。
 */
class PitEdgePathStartTest {

    @BeforeAll
    static void 引导游戏注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.FLUID.bindTags(Map.of(
                FluidTags.WATER, List.of(BuiltInRegistries.FLUID.wrapAsHolder(Fluids.WATER),
                        BuiltInRegistries.FLUID.wrapAsHolder(Fluids.FLOWING_WATER)),
                FluidTags.LAVA, List.of(BuiltInRegistries.FLUID.wrapAsHolder(Fluids.LAVA),
                        BuiltInRegistries.FLUID.wrapAsHolder(Fluids.FLOWING_LAVA))));
    }

    /** 简单平地世界替身：仅在指定格放实心石砖，其余为空气。 */
    private static final class FlatWorld implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        void floor(int x, int y, int z) {
            blocks.put(new BlockPos(x, y, z), Blocks.STONE_BRICKS.defaultBlockState());
        }

        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    }

    /** 仅满足 Movement 构造时读取 playerContext 的轻量离线引擎替身。 */
    private static final IBaritone BARITONE = (IBaritone) Proxy.newProxyInstance(
            IBaritone.class.getClassLoader(),
            new Class<?>[]{IBaritone.class},
            (proxy, method, args) -> null);

    /** 只含平地遍历步骤的离线路径替身，用于检验 GroundPathSmoothing 的合并边界。 */
    private static final class SimplePath extends PathBase {
        private final List<BetterBlockPos> positions;
        private final List<IMovement> movements;
        private final Goal goal;

        SimplePath(List<BetterBlockPos> positions) {
            this.positions = List.copyOf(positions);
            List<IMovement> list = new ArrayList<>();
            for (int i = 0; i + 1 < positions.size(); i++) {
                list.add(new MovementTraverse(BARITONE, positions.get(i), positions.get(i + 1)) {
                    @Override public double getCost() { return 1.0; }
                });
            }
            this.movements = List.copyOf(list);
            this.goal = new GoalBlock(positions.getLast());
        }

        @Override public List<IMovement> movements() { return movements; }
        @Override public List<BetterBlockPos> positions() { return positions; }
        @Override public Goal getGoal() { return goal; }
        @Override public int getNumNodesConsidered() { return positions.size(); }
    }

    @Test
    void 踩在坑边格沿_八邻格探测找出碰撞箱搭着的真实支撑格() {
        // 角色体素脚位在 1×1 坑口 (-356, 66, 76)，水平坐标偏向东南角，前三个正交邻格被挡，只有对角邻格可站。
        BetterBlockPos feet = new BetterBlockPos(-356, 66, 76);
        BetterBlockPos diagonalSupport = new BetterBlockPos(-355, 66, 77);
        Set<BetterBlockPos> walkable = Set.of(diagonalSupport);
        BetterBlockPos chosen = PitEdgeSupport.selectSupport(feet, -355.25, 76.75, 0.6, walkable::contains);
        assertEquals(diagonalSupport, chosen, "碰撞箱搭在对角格沿时应选到真实支撑格，不被前四名截断");
    }

    @Test
    void 远离碰撞箱覆盖范围的邻格不误判为支撑格() {
        BetterBlockPos feet = new BetterBlockPos(-358, 65, 44);
        // 角色贴在西侧边界 (-357.95, 44.50)，东侧邻格 (-357, 65, 44) 中心距为 1.45 > 0.82，碰撞箱够不到。
        BetterBlockPos tooFarEast = new BetterBlockPos(-357, 65, 44);
        BetterBlockPos chosen = PitEdgeSupport.selectSupport(feet, -357.95, 44.50, 0.6, tooFarEast::equals);
        assertEquals(feet, chosen, "任一轴超出碰撞箱半宽加方块半宽的邻格不能冒充脚下支撑");
    }

    @Test
    void 施工坑边起点与脚位分离_首步保留原生移动不合成会自毁的直线段() {
        // 复现 G4331 现场：脚位 (-356, 66, 76) 下方是坑（空气），支撑起点在 (-356, 66, 77)，随后一路平地向南。
        FlatWorld world = new FlatWorld();
        for (int z = 77; z <= 82; z++) {
            world.floor(-356, 65, z);
        }
        GroundCorridor corridor = new GroundCorridor(world, pos -> true, 0.6, 1.8, LongSets.EMPTY_SET,
                PhysicalObstacleSnapshot.EMPTY);
        List<BetterBlockPos> cells = List.of(
                new BetterBlockPos(-356, 66, 77),
                new BetterBlockPos(-356, 66, 78),
                new BetterBlockPos(-356, 66, 79),
                new BetterBlockPos(-356, 66, 80),
                new BetterBlockPos(-356, 66, 81));
        IPath raw = new SimplePath(cells);
        // 角色真实坐标在坑口边沿，anchor 为悬空脚位 (-356, 66, 76)，与路径起点 (-356, 66, 77) 分离。
        BlockPos pitFeet = new BlockPos(-356, 66, 76);
        Vec3 pitEdgePos = new Vec3(-355.5, 66.0, 76.72);
        IPath smoothed = GroundPathSmoothing.smooth(BARITONE, raw, corridor, pitFeet, pitEdgePos);

        assertInstanceOf(MovementTraverse.class, smoothed.movements().getFirst(),
                "踩在坑边且脚位与路径起点分离时，第 0 步必须保留原生遍历脱离坑边，不能合并为同刻自毁的直线段");
        assertTrue(smoothed.movements().size() < raw.movements().size(),
                "脱离坑边之后的实心平地路段仍可正常拉直合并");
    }

    @Test
    void 出行坑边悬空坐标作为首步起点_不合并无支撑的直线段() {
        // 复现 G4233 现场：脚位 (-358, 65, 44) 正下方无支撑，路径起点退回脚位自身且后续多格实心平路。
        FlatWorld world = new FlatWorld();
        for (int z = 43; z >= 38; z--) {
            world.floor(-358, 64, z);
        }
        GroundCorridor corridor = new GroundCorridor(world, pos -> true, 0.6, 1.8, LongSets.EMPTY_SET,
                PhysicalObstacleSnapshot.EMPTY);
        List<BetterBlockPos> cells = List.of(
                new BetterBlockPos(-358, 65, 44),
                new BetterBlockPos(-358, 65, 43),
                new BetterBlockPos(-358, 65, 42),
                new BetterBlockPos(-358, 65, 41),
                new BetterBlockPos(-358, 65, 40));
        IPath raw = new SimplePath(cells);
        BlockPos pitFeet = new BlockPos(-358, 65, 44);
        Vec3 pitEdgePos = new Vec3(-357.5, 65.0, 44.28);
        IPath smoothed = GroundPathSmoothing.smooth(BARITONE, raw, corridor, pitFeet, pitEdgePos);

        assertFalse(smoothed.movements().getFirst() instanceof MovementGroundStraight,
                "真实脚底悬空无法通过走廊连续支撑校验时，首步不得合成直线段");
        assertInstanceOf(MovementTraverse.class, smoothed.movements().getFirst());
    }
}
