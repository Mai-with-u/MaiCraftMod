// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 沿连通的同种流体做三维广度优先搜索，从一格流动流体回溯找到最近的同种流体源格。
 *
 * <p>空桶点到流动水或流动岩浆时，只允许沿相邻的同种流体格（源格或流动格）向源格追溯，
 * 绝不跨墙、跨空气或跨不同种流体跳到另一片不连通水体（例如隔墙的农田水坑或机器洗格水）。
 */
final class ConnectedFluidSource {

    /** 按格读取与起点同种流体的形态：非同种流体、空气、实心方块或未加载均返回 NONE。 */
    @FunctionalInterface
    interface FluidReader {
        UseSeams.ReadsWorld.Fluid sameFluidAt(BlockPos cell);
    }

    private ConnectedFluidSource() {}

    /**
     * 在以 {@code start} 为心、欧氏半径 {@code radius} 格以内，沿六邻接连通的同种流体寻找最近的源格。
     * 优先返回连通步数最少的源格；同一步数层命中多格时，取离起点直线距离最近的一格。
     */
    static Optional<BlockPos> find(BlockPos start, int radius, FluidReader reader) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(reader, "reader");
        if (radius < 0) return Optional.empty();
        UseSeams.ReadsWorld.Fluid origin = reader.sameFluidAt(start);
        if (origin == UseSeams.ReadsWorld.Fluid.NONE) return Optional.empty();
        if (origin == UseSeams.ReadsWorld.Fluid.SOURCE) return Optional.of(start.immutable());

        long maxDistSqr = (long) radius * radius;
        BlockPos originCell = start.immutable();
        Queue<BlockPos> frontier = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        frontier.add(originCell);
        visited.add(originCell);

        // 逐层扩展连通的同种流体格：遇到源格记为本层候选，整层扫完即挑直线最近者返回，绝不穿过非同种流体格。
        while (!frontier.isEmpty()) {
            int layerSize = frontier.size();
            BlockPos bestInLayer = null;
            double bestDistSqr = Double.MAX_VALUE;
            for (int i = 0; i < layerSize; i++) {
                BlockPos current = frontier.poll();
                for (Direction side : Direction.values()) {
                    BlockPos next = current.relative(side);
                    if (visited.contains(next)) continue;
                    double distSqr = next.distSqr(originCell);
                    if (distSqr > maxDistSqr) continue;
                    visited.add(next);
                    UseSeams.ReadsWorld.Fluid kind = reader.sameFluidAt(next);
                    if (kind == UseSeams.ReadsWorld.Fluid.SOURCE) {
                        if (distSqr < bestDistSqr) {
                            bestDistSqr = distSqr;
                            bestInLayer = next;
                        }
                    } else if (kind == UseSeams.ReadsWorld.Fluid.FLOWING) {
                        frontier.add(next);
                    }
                }
            }
            if (bestInLayer != null) {
                return Optional.of(bestInLayer);
            }
        }
        return Optional.empty();
    }
}
