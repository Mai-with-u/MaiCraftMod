// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 埋着的站位要不要挖进去：只差脚格与头格两块能挖的天然方块就挖，先头格后脚格；
 * 有一块挖不得（受保护、太硬、不是天然的）就不救；脚格头格本来就空的那一半不用挖。
 */
class BuriedSpotsTest {

    private static final BlockPos 脚格 = new BlockPos(3, 61, 1);

    @Test
    void 脚格头格都埋在土里就从上往下两块都挖() {
        List<BlockPos> cells = BuriedSpots.cellsToDig(脚格, cell -> true, cell -> true);
        assertEquals(List.of(脚格.above(), 脚格), cells, "先挖头格再挖脚格，人才能一路掉进去站稳");
    }

    @Test
    void 只有头顶一块挡着就只挖那一块() {
        // 一格高的缝：脚格空着、头格是土，挖开头格人就能站直。
        Set<BlockPos> solid = Set.of(脚格.above());
        List<BlockPos> cells = BuriedSpots.cellsToDig(脚格, solid::contains, cell -> true);
        assertEquals(List.of(脚格.above()), cells);
    }

    @Test
    void 有一块挖不得就不救() {
        // 头格是别人放的方块（受保护）：不为站位去动它，整个位置不救。
        Set<BlockPos> breakable = Set.of(脚格);
        assertTrue(BuriedSpots.cellsToDig(脚格, cell -> true, breakable::contains).isEmpty());
    }

    @Test
    void 脚格头格都空着就没有要挖的() {
        assertTrue(BuriedSpots.cellsToDig(脚格, cell -> false, cell -> true).isEmpty());
    }
}
