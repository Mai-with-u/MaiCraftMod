// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.settle;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.travel.planning.TravelDestination;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 出行的爬升分段判断：高得一次规划到不了才拆段、爬到走路接得住就停、拆满收场带着已爬到的高度。 */
class ClimbSegmentsTest {

    private static final TravelDestination PLATEAU =
            TravelDestination.confirmed(new WorldPosition(2, 132, -7, null), 2);

    @Test
    void onlyHeightsConfirmedDestinationsWellAboveSplit() {
        // 坑底直上台地（高差 88）要拆；差四十四级在走一段算一段盖得住的范围内，不拆。
        assertTrue(ClimbSegments.needsLeg(PLATEAU, new BlockPos(-12, 44, 49)));
        assertFalse(ClimbSegments.needsLeg(PLATEAU, new BlockPos(-12, 88, 49)));
        // 目的地在脚下下方或同高：不拆段，往下走是另一套。
        assertFalse(ClimbSegments.needsLeg(PLATEAU, new BlockPos(2, 200, -7)));
    }

    @Test
    void unconfirmedHeightNeverSplits() {
        // 只给了 x/z 的目的地高度没核实（y 是占位）：没有"还高几级"可言，不拆段。
        TravelDestination column = TravelDestination.anyHeight(2, -7, 2, null);
        assertFalse(ClimbSegments.needsLeg(column, new BlockPos(-12, 44, 49)));
        assertEquals(0, ClimbSegments.remaining(column, new BlockPos(-12, 44, 49)));
    }

    @Test
    void climbLegStopsOnceWalkCanTakeOver() {
        // 爬到离目的地还差三十级以内，走路接得住，这一段就算完；还差三十一级接着爬。
        assertTrue(ClimbSegments.stopWhenClose(PLATEAU).test(new BlockPos(0, 102, 0)));
        assertFalse(ClimbSegments.stopWhenClose(PLATEAU).test(new BlockPos(0, 101, 0)));
    }

    @Test
    void givingUpAfterLegsStatesHeightReachedAndKeepsUnderlyingHeadlineOnly() {
        // 拆满还没到：说明带拆了几段、爬到多高、还差几级；下一层的说明只取结论句，不整段重抄。
        BlockPos feet = new BlockPos(-22, 96, 40);
        Problem underlying = Problem.of(Problem.Kind.UNREACHABLE,
                "走到长时间没有新进展：身体停在原地，剩 东 8.2 格；自「在路上」之后又推进了 600 刻",
                "换一条路线，或先绕开挡路的地形再试");
        Problem judged = ClimbSegments.stillShort(PLATEAU, feet, 3, underlying);

        assertEquals(Problem.Kind.UNREACHABLE, judged.kind());
        assertTrue(judged.message().contains("爬了 3 段"), () -> judged.message());
        assertTrue(judged.message().contains("(-22, 96, 40)"), () -> judged.message());
        assertTrue(judged.message().contains("还高 36 级"), () -> judged.message());
        assertTrue(judged.message().contains("长时间没有新进展"), () -> "带下一层的结论句：" + judged.message());
        assertFalse(judged.message().contains("600 刻"), () -> "不整段重抄下一层的说明：" + judged.message());
        assertEquals("换一条路线，或先绕开挡路的地形再试", judged.suggestion());
    }
}
