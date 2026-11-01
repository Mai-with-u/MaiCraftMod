// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;

/**
 * 埋着的站位：候选站位因为"头顶没空间"被拒，而脚格、头格里挡着的只是一两块能挖的天然方块时，
 * 像真人盖地基那样挖进去站——目标格比地面低时，地面上的位置看不进坑底，坑边与目标同层的格子
 * 又都埋在土里，不挖进去就没有能放这一格的站位。要挖的超过脚格与头格这两块、或有一块挖不得，就不值得救。
 * 纯函数：方块是实心的、挖不挖得都由调用方按现场回答。
 */
public final class BuriedSpots {

    /** 最多挖开几格：脚格与头格；再多就不是"只差一点"的站位。 */
    public static final int MAX_CELLS = 2;

    private BuriedSpots() {}

    /**
     * 挖进这个站位要挖开的格，从上往下（先头格后脚格）；不值得挖时为空表。
     *
     * @param feet      被拒为"头顶没空间"的站位脚格
     * @param solid     这一格是不是实心的（人站不进去）
     * @param breakable 这一格挖不挖得：天然的土石树叶，许可放行、不受保护
     */
    public static List<BlockPos> cellsToDig(BlockPos feet, Predicate<BlockPos> solid, Predicate<BlockPos> breakable) {
        List<BlockPos> cells = new ArrayList<>(MAX_CELLS);
        for (BlockPos cell : List.of(feet.above(), feet)) {
            if (!solid.test(cell)) continue;
            if (!breakable.test(cell)) return List.of();
            cells.add(cell.immutable());
        }
        return cells;
    }
}
