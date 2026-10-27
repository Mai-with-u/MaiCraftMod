// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 蜂蜜胶的补胶计划，纯函数：一片要一起动的方块（车身）里，相邻两格要同在一块胶框里才粘在一起；
 * 现有的胶框（蜂蜜胶、强力胶都算，组装物理结构时两种都认）已经盖住的不管，还没盖住的用几块蜂蜜胶框补上。
 *
 * <p>蜂蜜胶的框不用角是方块、也不用两角连着，服务器只看每边不超过上限（默认 48 格）。所以先试整片的外框一框；
 * 框里夹着车身以外的实心方块（垫的柱子、地面、树）或者太大，就沿最长的边对半分（交界那一层两边都框上，跨界的两格不落下），
 * 分到每块都合规为止；最小是相邻两块一框，总是合规，所以一定能盖全。
 */
final class HoneyGluePlan {

    /** 一块蜂蜜胶每边最多几格：Simulated 服务器配置 honeyGlueRange 的默认值。类别：游戏事实（HoneyGlueMaxSizing）。 */
    static final int MAX_SIZE = 48;

    private HoneyGluePlan() {
    }

    /** 一块胶框：最小角与最大角（世界坐标），两头都含在框里。 */
    record Box(BlockPos min, BlockPos max) {
        Box {
            min = min.immutable();
            max = max.immutable();
        }

        boolean contains(BlockPos cell) {
            return cell.getX() >= min.getX() && cell.getX() <= max.getX() && cell.getY() >= min.getY()
                    && cell.getY() <= max.getY() && cell.getZ() >= min.getZ() && cell.getZ() <= max.getZ();
        }
    }

    /** 相邻的两格。 */
    private record Pair(BlockPos a, BlockPos b) {}

    /**
     * 还要补的蜂蜜胶框，按补的先后排；都已经粘好时是空表。
     *
     * @param body     要一起动的方块（此刻真的有方块的那些格）
     * @param existing 现有的胶框（蜂蜜胶与强力胶）
     * @param foreign  这一格是不是车身以外的实心方块；车身的格不会问它
     */
    static List<Box> plan(Set<BlockPos> body, List<Box> existing, Predicate<BlockPos> foreign) {
        Objects.requireNonNull(foreign, "foreign");
        Map<BlockPos, Boolean> asked = new HashMap<>();
        Predicate<BlockPos> outsider = cell -> !body.contains(cell) && asked.computeIfAbsent(cell, foreign::test);
        Set<Pair> uncovered = new LinkedHashSet<>();
        for (BlockPos cell : body) {
            for (Direction direction : List.of(Direction.EAST, Direction.UP, Direction.SOUTH)) {
                BlockPos next = cell.relative(direction);
                if (!body.contains(next)) continue;
                Pair pair = new Pair(cell.immutable(), next.immutable());
                if (existing.stream().noneMatch(box -> box.contains(pair.a()) && box.contains(pair.b()))) uncovered.add(pair);
            }
        }
        List<Box> added = new ArrayList<>();
        cover(new ArrayList<>(uncovered), outsider, added);
        return List.copyOf(added);
    }

    // 盖住这一堆相邻的两格：先试它们的外框一框；不合规就沿最长的边对半分，交界那一层两边都框上；
    // 分不动（三根边都不超过两格）就一对一框。
    private static void cover(List<Pair> pairs, Predicate<BlockPos> outsider, List<Box> added) {
        if (pairs.isEmpty()) return;
        Box frame = frame(pairs);
        if (fits(frame) && !holdsOutsider(frame, outsider)) {
            added.add(frame);
            return;
        }
        Direction.Axis axis = longest(frame);
        int low = frame.min().get(axis);
        int high = frame.max().get(axis);
        if (high - low < 2) {
            for (Pair pair : pairs) added.add(new Box(pair.a(), pair.b()));
            return;
        }
        int middle = low + (high - low) / 2;
        List<Pair> lower = new ArrayList<>();
        List<Pair> upper = new ArrayList<>();
        for (Pair pair : pairs) {
            if (pair.b().get(axis) <= middle) lower.add(pair);
            else upper.add(pair);
        }
        cover(lower, outsider, added);
        cover(upper, outsider, added);
    }

    private static Box frame(List<Pair> pairs) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Pair pair : pairs) {
            for (BlockPos cell : List.of(pair.a(), pair.b())) {
                minX = Math.min(minX, cell.getX());
                minY = Math.min(minY, cell.getY());
                minZ = Math.min(minZ, cell.getZ());
                maxX = Math.max(maxX, cell.getX());
                maxY = Math.max(maxY, cell.getY());
                maxZ = Math.max(maxZ, cell.getZ());
            }
        }
        return new Box(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
    }

    private static boolean fits(Box box) {
        return box.max().getX() - box.min().getX() < MAX_SIZE && box.max().getY() - box.min().getY() < MAX_SIZE
                && box.max().getZ() - box.min().getZ() < MAX_SIZE;
    }

    private static boolean holdsOutsider(Box box, Predicate<BlockPos> outsider) {
        for (BlockPos cell : BlockPos.betweenClosed(box.min(), box.max())) {
            if (outsider.test(cell.immutable())) return true;
        }
        return false;
    }

    private static Direction.Axis longest(Box box) {
        int x = box.max().getX() - box.min().getX();
        int y = box.max().getY() - box.min().getY();
        int z = box.max().getZ() - box.min().getZ();
        if (x >= y && x >= z) return Direction.Axis.X;
        return z >= y ? Direction.Axis.Z : Direction.Axis.Y;
    }
}
