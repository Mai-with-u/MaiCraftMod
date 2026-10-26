// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

import net.minecraft.core.BlockPos;

/**
 * 环扫找脚位的纯函数内核：在一片范围里逐格（逐柱）试「这里能不能站」，把收下的格子按调用方给的远近排序交回去。
 * 范围大小、扫描步长、排序与条数上限都是参数；「能不能站」由调用方注入（一格一判的谓词，或一柱一挑的列函数），
 * 内核不认识干地、水岸这些具体规矩——那些在 {@link BlockPassability} 与各领域自己手里。
 *
 * <p>扫描顺序是内核口径的一部分：列扫先 x 后 z，立方体扫先 y 后 z 再 x；排序稳定，代价相同的格子保持扫描先后的次序。
 * 调用方靠这个次序在「一样近挑哪个」上得到确定的结果。
 */
public final class SpotScan {

    private SpotScan() {}

    /**
     * 在矩形范围里逐柱扫能站的脚位：每柱由 {@code columnFeet} 挑一个脚位（挑不出给空），
     * 收下的按 {@code order} 排序，只留前 {@code limit} 个。
     *
     * @param minX minZ maxX maxZ 范围边界（含）；x 从西到东、z 从北到南扫
     * @param step 每隔几列看一列（1 为每列都看）：大片落脚处挨着时隔几列看一列就够
     */
    public static List<BlockPos> columns(int minX, int minZ, int maxX, int maxZ, int step,
            BiFunction<Integer, Integer, Optional<BlockPos>> columnFeet,
            Comparator<BlockPos> order, int limit) {
        List<BlockPos> spots = new ArrayList<>();
        for (int x = minX; x <= maxX; x += step) {
            for (int z = minZ; z <= maxZ; z += step) {
                columnFeet.apply(x, z).ifPresent(spots::add);
            }
        }
        // 排序是稳定的：一样近的格子保持扫描先后，调用方据此得到确定的选择。
        spots.sort(order);
        return spots.size() > limit ? List.copyOf(spots.subList(0, limit)) : List.copyOf(spots);
    }

    /**
     * 同 {@link #columns(int, int, int, int, int, BiFunction, Comparator, int)}，收下的全部交回、不设上限。
     */
    public static List<BlockPos> columns(int minX, int minZ, int maxX, int maxZ, int step,
            BiFunction<Integer, Integer, Optional<BlockPos>> columnFeet, Comparator<BlockPos> order) {
        return columns(minX, minZ, maxX, maxZ, step, columnFeet, order, Integer.MAX_VALUE);
    }

    /**
     * 一列里找离参考格最近的能站位：先同高，再高一格、低一格交替往外，最多看 {@code maxOffset} 格。
     * 撤退挑落脚处、飞行挑落点、让路挑站位都是这个找法，这里只留一份。
     */
    public static Optional<BlockPos> inColumn(BlockPos reference, int maxOffset, Predicate<BlockPos> standable) {
        for (int offset = 0; offset <= maxOffset; offset++) {
            BlockPos up = reference.above(offset);
            if (standable.test(up)) return Optional.of(up);
            if (offset == 0) continue;
            BlockPos down = reference.below(offset);
            if (standable.test(down)) return Optional.of(down);
        }
        return Optional.empty();
    }

    /**
     * 从一格往下找第一个能站的：落差 0 到 {@code maxDrop}。下船、下跳台这类只往低处找落脚处时用它，
     * 不往上看——上面的格不属于「从这儿下去」。
     */
    public static Optional<BlockPos> downward(BlockPos top, int maxDrop, Predicate<BlockPos> standable) {
        for (int drop = 0; drop <= maxDrop; drop++) {
            BlockPos lower = top.below(drop);
            if (standable.test(lower)) return Optional.of(lower);
        }
        return Optional.empty();
    }

    /**
     * 立方体范围里（中心水平 ±{@code horizontalRadius}、竖直 ±{@code verticalReach}）有没有一格满足 {@code accept}：
     * 找到一格就停。只要「有没有」不要「哪个」的判断用它，范围圈、排除集这些条件折进谓词里。
     */
    public static boolean exists(BlockPos center, int horizontalRadius, int verticalReach, Predicate<BlockPos> accept) {
        for (int dy = -verticalReach; dy <= verticalReach; dy++) {
            for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
                for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
                    if (accept.test(center.offset(dx, dy, dz))) return true;
                }
            }
        }
        return false;
    }

    /**
     * 立方体范围里按 {@code cost} 最小的那一格：代价相同的保留扫描先扫到的（先低层、先北后南、先西后东）。
     * 「最近的危险格」这类既要在邻域里找、又要结果确定的扫描用它。
     */
    public static Optional<BlockPos> nearest(BlockPos center, int horizontalRadius, int verticalReach,
            Predicate<BlockPos> accept, ToDoubleFunction<BlockPos> cost) {
        BlockPos best = null;
        double bestCost = Double.MAX_VALUE;
        for (int dy = -verticalReach; dy <= verticalReach; dy++) {
            for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
                for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!accept.test(pos)) continue;
                    double value = cost.applyAsDouble(pos);
                    if (value < bestCost) {
                        bestCost = value;
                        best = pos.immutable();
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * 立方体范围里满足 {@code accept} 的格子全收下来（扫描先后次序）。收完由调用方按自己的代价排序、
     * 再逐个核对弹道这类二次条件的扫描用它——内核只管「把候选收齐」。
     */
    public static List<BlockPos> collect(BlockPos center, int horizontalRadius, int verticalReach,
            Predicate<BlockPos> accept) {
        List<BlockPos> spots = new ArrayList<>();
        for (int dy = -verticalReach; dy <= verticalReach; dy++) {
            for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
                for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (accept.test(pos)) spots.add(pos.immutable());
                }
            }
        }
        return spots;
    }
}
