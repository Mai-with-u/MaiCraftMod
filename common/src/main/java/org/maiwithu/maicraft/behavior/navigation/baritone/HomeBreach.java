// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 被困在自己家里时拆哪一格墙出去：像真人那样朝外水平拆开脚那层或头那层的一两格，不往下挖地板、不碰床和家具。
 *
 * <p>门和栅栏门走路本来就会推开，卡坑判断也把它们当通道：走到这一步说明附近没有能开的门。
 * 从困住的那一片站得住的格子的边上，逐个看紧挨着的那一列：脚那层、头那层不通的格子拆掉以后，
 * 从这一片能不能走出去。拆了会带掉东西的（墙上挂着火把、告示牌、梯子、展示框，上面压着沙子砂砾）排到最后，
 * 别处都拆不了才拆；再挑拆得少的、拆了掉得回原物的（不是玻璃、石头这类挖掉掉的不是自己的）、离脚下近的。
 * 选中后还交出这一片四周的外壳：寻路这一趟只许拆选中的那几格，外壳上别的一格都不许挖。纯计算，可离线测试。
 *
 * @param stand   拆完从哪一格迈出去（困住的那一片里）
 * @param outward 朝哪边拆
 * @param cells   要拆的格子，从下往上（脚那层在前）
 * @param shell   困住的那一片四周不通的格子（地板、墙、屋顶、家具），去掉要拆的那几格
 * @param pocket  困住的那一片站得住的格子（屋里）：人的脚离开这一片、也不在拆开的格里，才算出来了
 */
record HomeBreach(BlockPos stand, Direction outward, List<BlockPos> cells, LongSet shell, LongSet pocket) {

    /** 最多从这一片里离脚下最近的这么多格去找：大屋子不必每一格都试一遍。 */
    static final int MAX_STANDS = 200;

    HomeBreach {
        cells = List.copyOf(cells);
        pocket = LongSets.unmodifiable(new LongOpenHashSet(pocket));
    }

    /**
     * 找一格能拆开出去的墙。
     *
     * @param breakable 这一格能不能拆：有碰撞、不是门床家具功能方块、不在记住的区域和点名保护的地方、拆得动
     * @param fragile   这一格挖掉掉的不是自己（玻璃碎了、石头掉圆石），补不回原样，同样多时最后才拆
     * @param drags     拆这一格会带掉别的东西（贴着它的火把告示牌梯子、上面的沙子砂砾），别处都拆不了才拆
     * @return 没有能拆一两格就出去的墙时为空（门也没有、四面都是不能拆的）
     */
    static Optional<HomeBreach> find(TrapCheck.Cells cells, BlockPos feet, int maxDrop, Predicate<BlockPos> breakable,
            Predicate<BlockPos> fragile, Predicate<BlockPos> drags) {
        LongSet pocket = TrapCheck.pocket(cells, feet, maxDrop);
        if (pocket == null) return Optional.empty();
        List<BlockPos> stands = new ArrayList<>();
        pocket.forEach((long packed) -> stands.add(BlockPos.of(packed)));
        stands.sort(Comparator.comparingDouble(stand -> stand.distSqr(feet)));
        List<Candidate> found = new ArrayList<>();
        for (BlockPos stand : stands.subList(0, Math.min(MAX_STANDS, stands.size()))) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                candidate(cells, pocket, stand, side, maxDrop, breakable, fragile, drags).ifPresent(found::add);
            }
        }
        // 先挑拆了不带掉东西的，再挑拆得少的、掉得回原物的、离脚下近的。
        return found.stream().min(Comparator.comparingInt((Candidate c) -> c.dragCount() > 0 ? 1 : 0)
                        .thenComparingInt(c -> c.cells().size())
                        .thenComparingInt(Candidate::fragileCount)
                        .thenComparingDouble(c -> c.stand().distSqr(feet)))
                .map(best -> new HomeBreach(best.stand(), best.side(), best.cells(), shell(cells, pocket, best.cells()),
                        pocket));
    }

    private record Candidate(BlockPos stand, Direction side, List<BlockPos> cells, int fragileCount, int dragCount) {}

    // 紧挨着的那一列：只看脚那层和头那层，那一列下面得有地可站；不通的格子都能拆、拆完走得出去才算。
    private static Optional<Candidate> candidate(TrapCheck.Cells cells, LongSet pocket, BlockPos stand, Direction side,
            int maxDrop, Predicate<BlockPos> breakable, Predicate<BlockPos> fragile, Predicate<BlockPos> drags) {
        BlockPos wall = stand.relative(side);
        if (pocket.contains(wall.asLong())) return Optional.empty();
        if (!cells.floor(wall.getX(), wall.getY() - 1, wall.getZ())) return Optional.empty();
        List<BlockPos> toBreak = new ArrayList<>();
        for (BlockPos cell : List.of(wall, wall.above())) {
            if (cells.open(cell.getX(), cell.getY(), cell.getZ())) continue;
            if (!breakable.test(cell)) return Optional.empty();
            toBreak.add(cell);
        }
        if (toBreak.isEmpty()) return Optional.empty();
        if (TrapCheck.trapped(withOpened(cells, toBreak), stand, maxDrop)) return Optional.empty();
        int fragileCount = (int) toBreak.stream().filter(fragile).count();
        int dragCount = (int) toBreak.stream().filter(drags).count();
        return Optional.of(new Candidate(stand, side, toBreak, fragileCount, dragCount));
    }

    // 假装这几格已经拆开：卡坑判断照原样问，只有这几格答"通、不能踩"。
    private static TrapCheck.Cells withOpened(TrapCheck.Cells cells, List<BlockPos> opened) {
        LongSet gone = new LongOpenHashSet();
        opened.forEach(cell -> gone.add(cell.asLong()));
        return new TrapCheck.Cells() {
            @Override public boolean open(int x, int y, int z) {
                return gone.contains(BlockPos.asLong(x, y, z)) || cells.open(x, y, z);
            }

            @Override public boolean floor(int x, int y, int z) {
                return !gone.contains(BlockPos.asLong(x, y, z)) && cells.floor(x, y, z);
            }

            @Override public boolean water(int x, int y, int z) {
                return cells.water(x, y, z);
            }

            @Override public boolean sky(int x, int y, int z) {
                return cells.sky(x, y, z);
            }

            @Override public boolean loaded(int x, int z) {
                return cells.loaded(x, z);
            }
        };
    }

    // 外壳：这一片每一格的身体两格往六个方向贴着的不通的格子，加上脚下的地板；要拆的那几格不在里面。
    private static LongSet shell(TrapCheck.Cells cells, LongSet pocket, List<BlockPos> breach) {
        LongSet shell = new LongOpenHashSet();
        pocket.forEach((long packed) -> {
            BlockPos stand = BlockPos.of(packed);
            shell.add(stand.below().asLong());
            for (BlockPos body : List.of(stand, stand.above())) {
                for (Direction side : Direction.values()) {
                    BlockPos next = body.relative(side);
                    if (!cells.open(next.getX(), next.getY(), next.getZ())) shell.add(next.asLong());
                }
            }
        });
        breach.forEach(cell -> shell.remove(cell.asLong()));
        return shell;
    }
}
