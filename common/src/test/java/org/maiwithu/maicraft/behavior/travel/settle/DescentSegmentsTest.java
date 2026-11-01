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

/** 出行的下掘分段判断：目的地在脚下深处才重开一段、比出发时挖得更深才算这一段的进展、拆满了收场带着挖到哪。 */
class DescentSegmentsTest {

    private static final TravelDestination MINE_LEVEL =
            TravelDestination.confirmed(new WorldPosition(-2972, 30, -836, null), 2);

    @Test
    void onlyConfirmedDestinationsBelowSplit() {
        // 目的地在脚下 28 格：值得从原地重开一段接着挖；同高、在上方、高度没核实都不拆。
        assertTrue(DescentSegments.needsLeg(MINE_LEVEL, new BlockPos(-2970, 58, -836)));
        assertFalse(DescentSegments.needsLeg(MINE_LEVEL, new BlockPos(-2970, 30, -836)));
        assertFalse(DescentSegments.needsLeg(MINE_LEVEL, new BlockPos(-2970, 12, -836)));
        TravelDestination column = TravelDestination.anyHeight(-2972, -836, 2, null);
        assertFalse(DescentSegments.needsLeg(column, new BlockPos(-2970, 58, -836)));
        assertEquals(0, DescentSegments.remaining(column, new BlockPos(-2970, 58, -836)));
    }

    @Test
    void aLegCountsAsProgressOnlyWhenItEndedDeeper() {
        // 挖下 4 格是进展；原地收场、还浮高了都不算，不给原地重走续命。
        BlockPos legStart = new BlockPos(-2970, 62, -836);
        assertTrue(DescentSegments.legDugDeeper(legStart, new BlockPos(-2970, 58, -836)));
        assertFalse(DescentSegments.legDugDeeper(legStart, new BlockPos(-2970, 62, -836)));
        assertFalse(DescentSegments.legDugDeeper(legStart, new BlockPos(-2970, 64, -836)));
        assertFalse(DescentSegments.legDugDeeper(null, new BlockPos(-2970, 58, -836)));
    }

    @Test
    void givingUpAfterLegsStatesDepthReachedAndKeepsUnderlyingHeadlineOnly() {        // 拆满还没到：说明带拆了几段、最深挖到哪、还差多深；下一层的说明只取结论句，不整段重抄。
        BlockPos feet = new BlockPos(-2970, 58, -836);
        Problem underlying = Problem.of(Problem.Kind.STUCK,
                "沿路线行走时长时间没有进展；现场：起点 (-2970,58,-836)，脚下 minecraft:stone",
                "看看脚下挡着什么，或把 change_blocks 放开再走");
        Problem judged = DescentSegments.stillShort(MINE_LEVEL, feet, 2, underlying);

        assertEquals(Problem.Kind.UNREACHABLE, judged.kind());
        assertTrue(judged.message().contains("下掘分了 2 段"), () -> judged.message());
        assertTrue(judged.message().contains("(-2970, 58, -836)"), () -> judged.message());
        assertTrue(judged.message().contains("还深 28 格"), () -> judged.message());
        assertTrue(judged.message().contains("长时间没有进展"), () -> "带下一层的结论句：" + judged.message());
        assertFalse(judged.message().contains("minecraft:stone"), () -> "不整段重抄下一层的说明：" + judged.message());
        assertEquals("看看脚下挡着什么，或把 change_blocks 放开再走", judged.suggestion());
    }

    @Test
    void bootLegOnlyForDestinationsDeeperThanWalkingRange() {
        // 崖边下探 50 级：深出一次走路接得住的范围，空转收场前值得拆一段下掘先挖台阶下去；
        // 只差几级的走路自己到得了，高度没核实的也不拆。
        assertTrue(DescentSegments.needsBootLeg(MINE_LEVEL, new BlockPos(-2972, 80, -836)));
        assertFalse(DescentSegments.needsBootLeg(MINE_LEVEL, new BlockPos(-2972, 55, -836)));
        assertFalse(DescentSegments.needsBootLeg(MINE_LEVEL, new BlockPos(-2972, 30, -836)));
        TravelDestination column = TravelDestination.anyHeight(-2972, -836, 2, null);
        assertFalse(DescentSegments.needsBootLeg(column, new BlockPos(-2972, 80, -836)));
    }

    @Test
    void bootLegStopsOnceCloseEnoughForWalking() {
        // 下掘段的停点：挖到离目的地还差 30 级以内就停，剩下的交回走路重新算路；还差 31 级就接着挖。
        var stop = DescentSegments.stopWhenClose(MINE_LEVEL);
        assertFalse(stop.test(new BlockPos(-2972, 61, -836)));
        assertTrue(stop.test(new BlockPos(-2972, 60, -836)));
        assertTrue(stop.test(new BlockPos(-2972, 30, -836)));
    }
}
