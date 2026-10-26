// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

/**
 * 环扫内核的取值：范围边界与步长、排序与条数上限、一列里先同高再上下交替、只往下找、
 * 存在性与最近格（代价相同保留先扫到的）、候选收齐。
 */
class SpotScanTest {

    @Test
    void columnScanRespectsTheBounds() {
        // 范围外的柱不进扫描：中心 (0,0) 半径 1 只有九柱有机会被挑中。
        List<BlockPos> spots = SpotScan.columns(-1, -1, 1, 1, 1,
                (x, z) -> Optional.of(new BlockPos(x, 64, z)),
                Comparator.comparingDouble(spot -> spot.distSqr(BlockPos.ZERO)));
        assertEquals(9, spots.size());
        assertTrue(spots.stream().allMatch(spot -> Math.max(Math.abs(spot.getX()), Math.abs(spot.getZ())) <= 1));
    }

    @Test
    void columnScanSortsByDistanceAndCapsTheLimit() {
        // 离中心近的排前面，上限只留前两个；一样近时保留扫描先后的次序（先 x 后 z）。
        List<BlockPos> spots = SpotScan.columns(-2, 0, 2, 0, 1,
                (x, z) -> Optional.of(new BlockPos(x, 64, z)),
                Comparator.comparingDouble(spot -> spot.distSqr(BlockPos.ZERO)), 2);
        assertEquals(new BlockPos(0, 64, 0), spots.get(0));
        assertEquals(new BlockPos(-1, 64, 0), spots.get(1));
    }

    @Test
    void columnScanStepsOverColumns() {
        // 隔两列看一列：只有 x 是偶数的柱被看，大片落脚处不用每柱都跑。
        List<Integer> seenX = new java.util.ArrayList<>();
        SpotScan.columns(-4, 0, 4, 0, 2,
                (x, z) -> {
                    seenX.add(x);
                    return Optional.empty();
                },
                Comparator.comparingInt(BlockPos::getX));
        assertEquals(List.of(-4, -2, 0, 2, 4), seenX);
    }

    @Test
    void emptyColumnsYieldNothing() {
        assertTrue(SpotScan.columns(-1, -1, 1, 1, 1, (x, z) -> Optional.empty(),
                Comparator.comparingInt(BlockPos::getX)).isEmpty());
    }

    @Test
    void inColumnTriesSameHeightThenUpAndDown() {
        // 站得住的格在低两格：同高、高一格、低一格都不行，低两格才中；再往外超了范围给空。
        Optional<BlockPos> hit = SpotScan.inColumn(new BlockPos(0, 64, 0), 3, pos -> pos.getY() == 62);
        assertTrue(hit.isPresent());
        assertEquals(62, hit.get().getY());
        assertFalse(SpotScan.inColumn(new BlockPos(0, 64, 0), 1, pos -> false).isPresent(),
                "范围里都不行给空");
    }

    @Test
    void inColumnStopsAtTheFirstStandable() {
        // 同高就能站时不再往下翻：先到先停。
        List<BlockPos> tried = new java.util.ArrayList<>();
        Optional<BlockPos> hit = SpotScan.inColumn(new BlockPos(0, 64, 0), 4, pos -> {
            tried.add(pos);
            return pos.getY() == 64;
        });
        assertEquals(1, tried.size(), "同高中了就不再试别的层");
    }

    @Test
    void downwardFindsTheFirstStandableBelow() {
        // 从顶上往下找：落差一格就中，不再往深里看；往上够不着的不算。
        Optional<BlockPos> hit = SpotScan.downward(new BlockPos(0, 66, 0), 3, pos -> pos.getY() == 65);
        assertTrue(hit.isPresent());
        assertEquals(65, hit.get().getY());
        assertTrue(SpotScan.downward(new BlockPos(0, 66, 0), 3, pos -> pos.getY() == 62).isEmpty(),
                "落差三格以内的才找");
    }

    @Test
    void existsStopsAtTheFirstMatch() {
        // 竖直范围只到 ±1：再高的那格满足也看不见，找到了一格就停。
        assertTrue(SpotScan.exists(BlockPos.ZERO, 2, 1,
                pos -> pos.getX() == 0 && pos.getY() == 1));
        assertFalse(SpotScan.exists(BlockPos.ZERO, 2, 1, pos -> pos.getY() == 3));
    }

    @Test
    void nearestPicksTheCheapestAndKeepsScanOrderOnTies() {
        // 一样近的两格（东西对称）：保留扫描先扫到的（x 从 -r 往 +r，先西后东）。
        BlockPos found = SpotScan.nearest(BlockPos.ZERO, 2, 0,
                pos -> Math.abs(pos.getX()) == 1, pos -> 1.0).orElseThrow();
        assertEquals(-1, found.getX());
        // 代价更小的另一格赢：哪怕它扫得更晚。
        BlockPos cheap = SpotScan.nearest(BlockPos.ZERO, 2, 0,
                pos -> pos.getX() != 0, pos -> Math.abs(pos.getX())).orElseThrow();
        assertEquals(-1, cheap.getX(), "代价 1 的两格里先扫到的西格");
    }

    @Test
    void nearestGivesEmptyWhenNothingMatches() {
        assertTrue(SpotScan.nearest(BlockPos.ZERO, 1, 1, pos -> false, pos -> 0).isEmpty());
    }

    @Test
    void collectGathersMatchesInScanOrder() {
        // y 外层、z 次之、x 内层的扫描次序原样保留，调用方好按自己的代价重排。
        List<BlockPos> spots = SpotScan.collect(BlockPos.ZERO, 1, 0, pos -> pos.getZ() == 0);
        assertEquals(List.of(new BlockPos(-1, 0, 0), new BlockPos(0, 0, 0), new BlockPos(1, 0, 0)), spots);
    }
}
