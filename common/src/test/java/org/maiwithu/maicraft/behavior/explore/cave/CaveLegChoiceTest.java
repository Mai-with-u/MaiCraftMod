// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore.cave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** 挑下一段：只挑看得见的新地方，越远越好、顺着走，别的方向记成岔路。 */
class CaveLegChoiceTest {

    private static final BlockPos FEET = new BlockPos(0, 64, 0);

    private static List<BlockPos> line(int dx, int dz, int length) {
        List<BlockPos> cells = new ArrayList<>();
        for (int i = 1; i <= length; i++) {
            cells.add(FEET.offset(dx * i, 0, dz * i));
        }
        return cells;
    }

    private static CaveLegChoice.Situation situation(Double heading) {
        return new CaveLegChoice.Situation(FEET, FEET, 128, heading, null);
    }

    @Test
    void 挑看得见的最远处_别的方向记成岔路() {
        List<BlockPos> reached = new ArrayList<>(line(1, 0, 20));
        reached.addAll(line(0, -1, 10));
        CaveLegChoice.Choice choice = CaveLegChoice.choose(situation(null), reached, cell -> false, cell -> true,
                List.of());
        assertEquals(new BlockPos(20, 64, 0), choice.next());
        assertEquals(1, choice.branches().size());
        assertEquals(new BlockPos(0, 64, -10), choice.branches().getFirst().at());
    }

    @Test
    void 看不见的格不挑_拐角后面再长也不算() {
        List<BlockPos> reached = new ArrayList<>(line(1, 0, 8));
        List<BlockPos> behindCorner = line(0, 1, 20);
        reached.addAll(behindCorner);
        Set<BlockPos> hidden = Set.copyOf(behindCorner);
        CaveLegChoice.Choice choice = CaveLegChoice.choose(situation(null), reached, cell -> false,
                cell -> !hidden.contains(cell), List.of());
        assertEquals(new BlockPos(8, 64, 0), choice.next(), "看不见的长通道不偷看");
        assertTrue(choice.branches().isEmpty());
    }

    @Test
    void 顺着上一段的方向走_不来回折返() {
        List<BlockPos> reached = new ArrayList<>(line(1, 0, 12));
        reached.addAll(line(-1, 0, 13));
        CaveLegChoice.Choice choice = CaveLegChoice.choose(situation(90.0), reached, cell -> false, cell -> true,
                List.of());
        assertEquals(new BlockPos(12, 64, 0), choice.next(), "朝东走过来，东边短一格也接着往东");
    }

    @Test
    void 走过的和走不到的附近不挑_都没了给空() {
        List<BlockPos> reached = line(1, 0, 20);
        CaveLegChoice.Choice walkedAll = CaveLegChoice.choose(situation(null), reached, cell -> true, cell -> true,
                List.of());
        assertNull(walkedAll.next());

        CaveLegChoice.Choice nearTried = CaveLegChoice.choose(situation(null), line(1, 0, 30), cell -> false,
                cell -> true, List.of(new BlockPos(30, 64, 0)));
        assertEquals(new BlockPos(13, 64, 0), nearTried.next(), "走不到的那格 16 格内都不挑");
    }

    @Test
    void 越过离洞口的界不挑() {
        CaveLegChoice.Situation tight = new CaveLegChoice.Situation(FEET, FEET, 10, null, null);
        CaveLegChoice.Choice choice = CaveLegChoice.choose(tight, line(1, 0, 20), cell -> false, cell -> true,
                List.of());
        assertEquals(new BlockPos(10, 64, 0), choice.next());
    }
}
