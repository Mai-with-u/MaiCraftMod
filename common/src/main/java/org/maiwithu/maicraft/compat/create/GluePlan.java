// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 补胶计划，纯函数：一片要一起动的方块（车身）里，相邻两格要同在一块强力胶框里才粘在一起；
 * 现有的胶框已经盖住的不管，还没盖住的用几块新框补上。
 *
 * <p>新框照 Create 服务器收胶时的核对来挑：两个角都是车身上的方块、两角在框里由车身方块连着、两角相距不到 25 格。
 * 框里不许夹着车身以外的实心方块（垫的柱子、地面、树），不然它们会跟着一起走。挑法：从最低处还没粘的那一对起，
 * 先在车身的外框里往外长出一片不夹外物、对角不超限的区域，再在几种候选（区域对角线两头的车身方块、这一对连到区域各角的车身方块）
 * 里挑盖住最多空缺、两角连得通的那一框；挑不出就只框这一对（两块相邻的方块一框，总是合规），所以一定能盖全。
 */
final class GluePlan {

    /** 两角相距要不到这么多格（服务器核对的上限）。类别：游戏事实（SuperGlueSelectionPacket）。 */
    static final int MAX_SPAN = 25;

    private static final List<Direction> GROWTH = List.of(Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH,
            Direction.UP, Direction.DOWN);

    private GluePlan() {
    }

    /**
     * 一块胶框：两个角，世界坐标；to 是角色要走到跟前的那一角（取低的那个，好够着）。
     * 已有的胶框也用它表示，这时 from、to 就是框的最小角与最大角。
     */
    record Box(BlockPos from, BlockPos to) {
        Box {
            from = from.immutable();
            to = to.immutable();
        }

        BlockPos min() {
            return new BlockPos(Math.min(from.getX(), to.getX()), Math.min(from.getY(), to.getY()),
                    Math.min(from.getZ(), to.getZ()));
        }

        BlockPos max() {
            return new BlockPos(Math.max(from.getX(), to.getX()), Math.max(from.getY(), to.getY()),
                    Math.max(from.getZ(), to.getZ()));
        }

        boolean contains(BlockPos cell) {
            return inside(cell, min(), max());
        }
    }

    /** 相邻的两格；a 是坐标小的那一格。 */
    private record Pair(BlockPos a, BlockPos b) {}

    /**
     * 还要补的胶框，按补的先后排；都已经粘好时是空表。
     *
     * @param body     要一起动的方块（此刻真的有方块的那些格）
     * @param existing 现有的胶框
     * @param foreign  这一格是不是车身以外的实心方块（地面、垫的东西）；车身的格不会问它
     */
    static List<Box> plan(Set<BlockPos> body, List<Box> existing, Predicate<BlockPos> foreign) {
        Objects.requireNonNull(foreign, "foreign");
        // 长区域时同一格会被问好几次：问过的记下来，世界只读一次。
        Map<BlockPos, Boolean> asked = new HashMap<>();
        Predicate<BlockPos> outsider = cell -> asked.computeIfAbsent(cell, foreign::test);
        Set<BlockPos> cells = Set.copyOf(body);
        Set<Pair> uncovered = new LinkedHashSet<>();
        for (Pair pair : pairs(cells)) {
            if (existing.stream().noneMatch(box -> box.contains(pair.a()) && box.contains(pair.b()))) uncovered.add(pair);
        }
        if (uncovered.isEmpty()) return List.of();
        BlockPos[] bounds = bounds(cells);
        List<Box> added = new ArrayList<>();
        while (!uncovered.isEmpty()) {
            Pair first = uncovered.iterator().next();
            Box box = best(first, cells, uncovered, outsider, bounds[0], bounds[1]);
            added.add(box);
            uncovered.removeIf(pair -> box.contains(pair.a()) && box.contains(pair.b()));
        }
        return List.copyOf(added);
    }

    // 车身里每一对相邻的格，按低到高、西到东、北到南排，计划从最低处起补。
    private static List<Pair> pairs(Set<BlockPos> cells) {
        List<BlockPos> ordered = cells.stream().sorted(Comparator.comparingInt((BlockPos cell) -> cell.getY())
                .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ)).toList();
        List<Pair> pairs = new ArrayList<>();
        for (BlockPos cell : ordered) {
            for (Direction direction : List.of(Direction.EAST, Direction.UP, Direction.SOUTH)) {
                BlockPos next = cell.relative(direction);
                if (cells.contains(next)) pairs.add(new Pair(cell, next));
            }
        }
        return pairs;
    }

    // 为这一对挑一框：先长出区域，再在候选里挑盖住空缺最多、两角连得通的；挑不出就只框这一对。
    private static Box best(Pair first, Set<BlockPos> cells, Set<Pair> uncovered, Predicate<BlockPos> foreign,
            BlockPos lowest, BlockPos highest) {
        BlockPos[] region = grow(first, cells, foreign, lowest, highest);
        // 候选：区域四条对角线两头最近的车身方块各成一框；再从这一对的两格各连到区域八个角最近的车身方块，
        // 后一种一定框住这一对，前一种往往框得最大。挑盖住空缺最多的。
        List<BlockPos[]> candidates = new ArrayList<>();
        List<BlockPos> corners = new ArrayList<>();
        for (BlockPos corner : BlockPos.betweenClosed(region[0], region[1])) {
            boolean onCorner = (corner.getX() == region[0].getX() || corner.getX() == region[1].getX())
                    && (corner.getY() == region[0].getY() || corner.getY() == region[1].getY())
                    && (corner.getZ() == region[0].getZ() || corner.getZ() == region[1].getZ());
            if (onCorner) corners.add(corner.immutable());
        }
        for (BlockPos corner : corners) {
            BlockPos opposite = new BlockPos(region[0].getX() + region[1].getX() - corner.getX(),
                    region[0].getY() + region[1].getY() - corner.getY(), region[0].getZ() + region[1].getZ() - corner.getZ());
            candidates.add(new BlockPos[] {nearest(cells, region, corner), nearest(cells, region, opposite)});
            BlockPos far = nearest(cells, region, corner);
            candidates.add(new BlockPos[] {first.a(), far});
            candidates.add(new BlockPos[] {first.b(), far});
        }
        Box chosen = ordered(first.a(), first.b());
        long most = 0;
        for (BlockPos[] ends : candidates) {
            if (ends[0] == null || ends[1] == null || ends[0].equals(ends[1])) continue;
            Box box = ordered(ends[0], ends[1]);
            if (!connected(cells, box)) continue;
            long covered = uncovered.stream().filter(pair -> box.contains(pair.a()) && box.contains(pair.b())).count();
            if (covered > most) {
                most = covered;
                chosen = box;
            }
        }
        return chosen;
    }

    // 两角定好以后，角色走去低的那一角（to），够得着；另一角是 from。
    private static Box ordered(BlockPos one, BlockPos other) {
        return one.getY() <= other.getY() ? new Box(other, one) : new Box(one, other);
    }

    // 从这一对往外长：每轮各个方向试着加一层，不出车身外框、新的一层里没有外物、对角不超限才加，长不动为止。
    private static BlockPos[] grow(Pair first, Set<BlockPos> cells, Predicate<BlockPos> foreign, BlockPos lowest,
            BlockPos highest) {
        BlockPos min = new Box(first.a(), first.b()).min();
        BlockPos max = new Box(first.a(), first.b()).max();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Direction direction : GROWTH) {
                boolean positive = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE;
                BlockPos newMin = positive ? min : min.relative(direction);
                BlockPos newMax = positive ? max.relative(direction) : max;
                if (!inside(newMin, lowest, highest) || !inside(newMax, lowest, highest)) continue;
                if (newMin.distSqr(newMax) >= (long) MAX_SPAN * MAX_SPAN) continue;
                // 新加的这一层：沿这个方向的那一个坐标上，其余两轴铺满。
                int at = positive ? newMax.get(direction.getAxis()) : newMin.get(direction.getAxis());
                if (anyForeign(along(newMin, direction.getAxis(), at), along(newMax, direction.getAxis(), at), cells, foreign)) {
                    continue;
                }
                min = newMin;
                max = newMax;
                grew = true;
            }
        }
        return new BlockPos[] {min, max};
    }

    private static BlockPos along(BlockPos pos, Direction.Axis axis, int value) {
        return new BlockPos(axis == Direction.Axis.X ? value : pos.getX(), axis == Direction.Axis.Y ? value : pos.getY(),
                axis == Direction.Axis.Z ? value : pos.getZ());
    }

    private static boolean anyForeign(BlockPos min, BlockPos max, Set<BlockPos> cells, Predicate<BlockPos> foreign) {
        for (BlockPos cell : BlockPos.betweenClosed(min, max)) {
            if (!cells.contains(cell) && foreign.test(cell.immutable())) return true;
        }
        return false;
    }

    // 区域里离这个角最近的车身方块；同样近按坐标先后取，结果每次一样。
    private static BlockPos nearest(Set<BlockPos> cells, BlockPos[] region, BlockPos corner) {
        return cells.stream().filter(cell -> inside(cell, region[0], region[1]))
                .min(Comparator.comparingDouble((BlockPos cell) -> cell.distSqr(corner))
                        .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ))
                .orElse(null);
    }

    // 两角在框里由车身方块连着：从一个角沿相邻的车身方块走，走得到另一个角。服务器收胶时也这样核对。
    private static boolean connected(Set<BlockPos> cells, Box box) {
        Deque<BlockPos> frontier = new ArrayDeque<>(List.of(box.from()));
        Set<BlockPos> visited = new HashSet<>(List.of(box.from()));
        while (!frontier.isEmpty()) {
            BlockPos current = frontier.poll();
            if (current.equals(box.to())) return true;
            for (Direction direction : Direction.values()) {
                BlockPos next = current.relative(direction);
                if (cells.contains(next) && box.contains(next) && visited.add(next)) frontier.add(next);
            }
        }
        return false;
    }

    private static BlockPos[] bounds(Set<BlockPos> cells) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos cell : cells) {
            minX = Math.min(minX, cell.getX());
            minY = Math.min(minY, cell.getY());
            minZ = Math.min(minZ, cell.getZ());
            maxX = Math.max(maxX, cell.getX());
            maxY = Math.max(maxY, cell.getY());
            maxZ = Math.max(maxZ, cell.getZ());
        }
        return new BlockPos[] {new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ)};
    }

    private static boolean inside(BlockPos cell, BlockPos min, BlockPos max) {
        return cell.getX() >= min.getX() && cell.getX() <= max.getX() && cell.getY() >= min.getY() && cell.getY() <= max.getY()
                && cell.getZ() >= min.getZ() && cell.getZ() <= max.getZ();
    }
}
