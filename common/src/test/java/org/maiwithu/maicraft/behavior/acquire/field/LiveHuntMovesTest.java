// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Function;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.travel.TravelFakes;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 狩猎走路的生产实现：走近猎物、走出去找一片，能不能打断与先到安全处再停都按走路说。 */
class LiveHuntMovesTest {

    @Test
    void 走近猎物悬在半空停不得_先停稳转给走路_落地才算停稳() {
        assertWalkForwards(moves -> moves.walkTo(12, 64, 0));
    }

    @Test
    void 走出去找一片悬在半空停不得_先停稳转给走路_落地才算停稳() {
        assertWalkForwards(moves -> moves.walkToArea(40, 64, 0));
    }

    // 第一刻开走、跳在半空：停不得，先停稳转给走路；落地报"已停下"才算停稳。站在地上走着现在就能停。
    private static void assertWalkForwards(Function<LiveHuntMoves, Action> start) {
        TravelFakes.ScriptedWalk leg = new TravelFakes.ScriptedWalk(
                WalkReport.onTheWay(new BlockPos(1, 65, 0), false),
                WalkReport.stopped(new BlockPos(1, 64, 0), false));
        leg.airborne = true;
        Action walk = start.apply(new LiveHuntMoves((target, permit) -> leg));
        assertFalse(walk.stopSafely(), "还没开走：现在就能停");
        walk.tick(TickContext.of(1, null));
        assertEquals(Interruptibility.UNSAFE_TO_STOP, walk.interruptibility(), "悬在半空停不得");
        assertTrue(walk.stopSafely(), "悬在半空，要先落地");
        assertEquals(1, leg.safeStops, "先停稳转给了走路");
        assertFalse(walk.stoppedSafely(), "还没落地");
        walk.tick(TickContext.of(2, null));
        assertTrue(walk.stoppedSafely(), "落地站稳了");

        TravelFakes.ScriptedWalk ground = new TravelFakes.ScriptedWalk(
                WalkReport.onTheWay(new BlockPos(1, 64, 0), false));
        Action onGround = start.apply(new LiveHuntMoves((target, permit) -> ground));
        onGround.tick(TickContext.of(1, null));
        assertEquals(Interruptibility.WORKING, onGround.interruptibility(), "站在地上走着是正常干活");
        assertFalse(onGround.stopSafely(), "站在地上走着：现在就能停");
    }
}
