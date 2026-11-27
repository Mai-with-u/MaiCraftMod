// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.cook;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.FluidState;

import org.maiwithu.maicraft.behavior.acquire.spi.FluidTransform;
import org.maiwithu.maicraft.behavior.acquire.spi.FluidTransforms;
import org.maiwithu.maicraft.behavior.navigation.util.BlockPassability;

/**
 * 往水里扔料要看的现场：角色所在的世界里有哪些配方、附近哪几格静水能扔料。只读；生产实现读客户端世界，测试用替身。
 */
public interface TransformSites {

    /** 评估投水站位退路时检查的最远连续步数（格）：离开拾取范围需要退开 2–3 格。 */
    int RETREAT_CHECK_STEPS = 3;

    /** 这份配方表在角色此刻所在的世界里有哪些配方；不在世界里给空。 */
    Optional<List<FluidTransform>> recipes(FluidTransforms transforms);

    /**
     * 角色周围能扔料的水格，按无杂物、有干岸退路、浅水优先、远近排好先后。
     *
     * @param fluids 认哪几种流体（流体 ID）
     */
    Pools pools(BlockPos center, Set<String> fluids, int radius);

    /**
     * 找到的水格。
     *
     * @param cells    能扔料的格，排好了先后
     * @param complete 附近扫完了没有；没扫完时没找到不等于没有
     */
    record Pools(List<BlockPos> cells, boolean complete) {

        public Pools {
            cells = List.copyOf(cells);
        }
    }

    /**
     * 一格水的现场评分，排先后用。
     *
     * @param cell         静水格坐标
     * @param distance     离角色的直线距离
     * @param crowded      周围一格半内是否漂着其他掉落物
     * @param hasDryBank   四邻是否存在干燥岸边投水站位
     * @param retreatDepth 最佳岸边投水站位沿背离水格方向（或侧向）连续可退步数（0..3）
     * @param shallow      水格下方是否为实心池底（1 格浅水），避开需要频繁换气的深水区
     */
    record Pool(BlockPos cell, double distance, boolean crowded, boolean hasDryBank, int retreatDepth, boolean shallow) {
        /** 兼容仅指定远近与杂物的构造：默认视作有干岸、有退路的浅水格。 */
        public Pool(BlockPos cell, double distance, boolean crowded) {
            this(cell, distance, crowded, true, RETREAT_CHECK_STEPS, true);
        }
    }

    /**
     * 这一格能不能扔料，纯判断：是认的那种流体的源格（流动的水格不算源）、水不往外流（流向为零，东西不会漂走）、
     * 头顶是空气（扔得进去）。
     */
    static boolean usable(BlockGetter level, BlockPos pos, Set<String> fluids) {
        FluidState fluid = level.getFluidState(pos);
        if (fluid.isEmpty() || !fluid.isSource()) return false;
        if (!fluids.contains(BuiltInRegistries.FLUID.getKey(fluid.getType()).toString())) return false;
        if (!level.getBlockState(pos.above()).isAir()) return false;
        return fluid.getFlow(level, pos).lengthSqr() < 1.0e-6;
    }

    /**
     * 结合周围地形评估一格静水的投水条件：水深是否浅水（脚踩池底不需频繁换气）、四邻有无干燥岸边站位，
     * 以及站位背离水格方向有无 2–3 格可走退路，供排序优先挑出岸边宽敞浅水格。
     */
    static Pool evaluate(BlockGetter level, BlockPos pos, double distance, boolean crowded) {
        boolean shallow = isShallow(level, pos);
        boolean hasDryBank = false;
        int bestRetreat = 0;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos stand = pos.relative(side).above(dy);
                if (!BlockPassability.isDryStandable(level, stand) || lavaBeside(level, stand)) continue;
                hasDryBank = true;
                int straight = retreatSteps(level, stand, side);
                int lateral = Math.max(retreatSteps(level, stand, side.getClockWise()),
                        retreatSteps(level, stand, side.getCounterClockWise()));
                // 正后方直退最优；正后方受阻时允许侧绕退路，上限计为 2 格以便正后方 3 格开阔位排在更前。
                int depth = Math.max(straight, Math.min(2, lateral));
                if (depth > bestRetreat) bestRetreat = depth;
            }
        }
        return new Pool(pos.immutable(), distance, crowded, hasDryBank, bestRetreat, shallow);
    }

    /** 水格正下方是非液体实心池底（1 格浅水）返回 true；下方仍是水或悬空（深水区）返回 false。 */
    static boolean isShallow(BlockGetter level, BlockPos pool) {
        BlockPos below = pool.below();
        return level.getFluidState(below).isEmpty() && BlockPassability.canWalkOn(level, below);
    }

    // 沿指定水平方向连续向前探测最多 3 格干燥可走退路（支持同层或上下 1 格台阶），遇到墙或危险立即止步。
    private static int retreatSteps(BlockGetter level, BlockPos start, Direction dir) {
        BlockPos cursor = start;
        int steps = 0;
        for (int i = 0; i < RETREAT_CHECK_STEPS; i++) {
            BlockPos next = nextDryStep(level, cursor, dir);
            if (next == null) break;
            cursor = next;
            steps++;
        }
        return steps;
    }

    private static BlockPos nextDryStep(BlockGetter level, BlockPos from, Direction dir) {
        BlockPos flat = from.relative(dir);
        if (BlockPassability.isDryStandable(level, flat) && !lavaBeside(level, flat)) return flat;
        BlockPos up = flat.above();
        if (BlockPassability.canWalkThrough(level, from.above(2))
                && BlockPassability.isDryStandable(level, up) && !lavaBeside(level, up)) {
            return up;
        }
        BlockPos down = flat.below();
        if (BlockPassability.canWalkThrough(level, flat) && BlockPassability.canWalkThrough(level, flat.above())
                && BlockPassability.isDryStandable(level, down) && !lavaBeside(level, down)) {
            return down;
        }
        return null;
    }

    private static boolean lavaBeside(BlockGetter level, BlockPos pos) {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (BlockPassability.isHazard(level, pos.relative(side))) return true;
        }
        return false;
    }

    /**
     * 排先后：先避开旁边漂着杂物的水格，再按「有干岸且有 2–3 格退路 > 有干岸 > 浅水无干岸 > 深水无干岸」分档，
     * 同档内浅水优于深水、退路更深优先，最后按远近排序。
     */
    static List<BlockPos> order(List<Pool> pools) {
        return pools.stream()
                .sorted(Comparator.comparing(Pool::crowded)
                        .thenComparingInt(TransformSites::siteTier)
                        .thenComparing(pool -> !pool.shallow())
                        .thenComparingInt(pool -> -pool.retreatDepth())
                        .thenComparingDouble(Pool::distance))
                .map(Pool::cell)
                .toList();
    }

    private static int siteTier(Pool pool) {
        if (pool.hasDryBank() && pool.retreatDepth() >= 2) return 0;
        if (pool.hasDryBank()) return 1;
        if (pool.shallow()) return 2;
        return 3;
    }
}
