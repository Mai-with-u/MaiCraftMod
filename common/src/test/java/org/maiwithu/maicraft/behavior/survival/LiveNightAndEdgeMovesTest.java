// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.inventory.drops.PicksUpDrops;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.behavior.survival.recover.LostItemsNeed;
import org.maiwithu.maicraft.behavior.travel.TravelFakes;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 退离边沿与跑回死的地方的走路：路上悬在半空停不得，被叫先到安全处再停转给走路，落地才算停稳；
 * 落地报的"已停下"不当成走完了、不算一次没走到，也不另起一趟；暂停过后从原地重新上路。
 */
class LiveNightAndEdgeMovesTest {

    /** 每次出发交出下一段脚本；脚本用完了交一段在地上走着的。 */
    private static final class Walks implements WalkTo {
        final List<TravelFakes.ScriptedWalk> queued = new ArrayList<>();
        int started;

        @Override public WalkRun start(GoalCompiler.Compiled target, TerrainPermit permit) {
            started++;
            return queued.isEmpty()
                    ? new TravelFakes.ScriptedWalk(WalkReport.onTheWay(new BlockPos(1, 64, 0), false))
                    : queued.remove(0);
        }
    }

    private static final PicksUpDrops NOTHING_TO_PICK = new PicksUpDrops() {
        @Override public Set<Integer> nearby() {
            return Set.of();
        }

        @Override public Action pickUpNewSince(Set<Integer> before) {
            throw new AssertionError("还没跑到，不该去捡");
        }
    };

    @Test
    void 退离边沿悬在半空被叫先停稳_落地才停_不算没退成() {
        Walks walks = new Walks();
        TravelFakes.ScriptedWalk leg = airborneLeg();
        walks.queued.add(leg);
        Task task = LiveNightAndEdgeMoves.retreat(walks, null).stepBack(new double[] {5, 0, 0});
        assertWindsDownThenSetsOffAgain(task, walks, leg);
    }

    @Test
    void 跑回死的地方悬在半空被叫先停稳_落地才停_不算一次没跑到() {
        Walks walks = new Walks();
        TravelFakes.ScriptedWalk leg = airborneLeg();
        walks.queued.add(leg);
        Task task = LiveNightAndEdgeMoves.lostItems(walks, NOTHING_TO_PICK, null)
                .recover(new LostItemsNeed.Death(30, 64, 0, "minecraft:overworld", 0, 3), 100_000);
        assertWindsDownThenSetsOffAgain(task, walks, leg);
    }

    // 第一刻上路、跳在半空：停不得，先停稳转给走路；落地后不收场、不另起一趟；暂停过后从原地重新上路。
    private static void assertWindsDownThenSetsOffAgain(Task task, Walks walks, TravelFakes.ScriptedWalk leg) {
        long tick = 0;
        task.start(TickContext.of(tick, null));
        task.tick(TickContext.of(tick++, null));
        assertEquals(Interruptibility.UNSAFE_TO_STOP, task.interruptibility(TickContext.of(tick, null)), "悬在半空停不得");

        assertTrue(task.stopSafely(), "悬在半空，要先落地");
        assertEquals(1, leg.safeStops, "先停稳转给了走路");
        assertFalse(task.stoppedSafely(), "还没落地");
        for (int i = 0; i < 4; i++) {
            assertInstanceOf(TickResult.Running.class, task.tick(TickContext.of(tick++, null)), "收尾途中不收场");
        }
        assertTrue(task.stoppedSafely(), "落地站稳了");
        assertEquals(1, walks.started, "收尾途中不另起一趟");

        task.pause();
        task.tick(TickContext.of(tick++, null));
        assertEquals(2, walks.started, "暂停过后从原地重新上路");
        assertFalse(task.stopSafely(), "站在地上走着：现在就能停");
    }

    private static TravelFakes.ScriptedWalk airborneLeg() {
        TravelFakes.ScriptedWalk leg = new TravelFakes.ScriptedWalk(
                WalkReport.onTheWay(new BlockPos(1, 65, 0), false),
                WalkReport.onTheWay(new BlockPos(1, 66, 0), false),
                WalkReport.stopped(new BlockPos(1, 64, 0), false));
        leg.airborne = true;
        return leg;
    }
}
