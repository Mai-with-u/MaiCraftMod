// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.settle;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.travel.TravelFakes.FakePlaced;
import org.maiwithu.maicraft.behavior.travel.TravelFakes.FakePlaces;
import org.maiwithu.maicraft.behavior.travel.TravelFakes.FakeSeen;
import org.maiwithu.maicraft.behavior.travel.TravelFakes.FakeWalks;
import org.maiwithu.maicraft.behavior.travel.TravelFakes.FakeWorld;
import org.maiwithu.maicraft.behavior.travel.TravelFakes.ProgressRecorder;
import org.maiwithu.maicraft.behavior.travel.TravelFakes.ScriptedWalk;
import org.maiwithu.maicraft.behavior.travel.TravelFakes.TestTick;
import org.maiwithu.maicraft.behavior.travel.planning.DestinationResolver;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TickResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 绕路时也算进展：离目的地没近，但按路线挖开、站上这一趟没碰过的格，不判卡住；
 * 来回走、反复挖同一格、远出范围的新格攒不出进展；离上次变近满 2 分钟就只认距离。
 */
class TravelDetourTest {

    /** 目的地在东边 20 格，角色从原点出发。 */
    private static final Target.Position DESTINATION = new Target.Position(20, 64, 0, null);

    private final FakeWorld world = new FakeWorld();
    private final FakeWalks walks = new FakeWalks();
    private final FakePlaced placed = new FakePlaced();
    private final TestTick tick = new TestTick();
    /** 收场是在第几刻；没收场为 -1。 */
    private int finishedAt = -1;

    private TravelTask task() {
        DestinationResolver resolver = new DestinationResolver(world, new FakePlaces(), new FakeSeen());
        var ready = assertInstanceOf(DestinationResolver.Resolution.Ready.class, resolver.resolve(DESTINATION, 0));
        return new TravelTask(ready.destination(), 0, TerrainPermit.NATURAL, walks, placed, new ProgressRecorder(),
                world, null, null, Permissions.DEFAULT);
    }

    /** 逐刻推进，每刻先让 beforeTick 摆好现场；收场就交结果，到 maxTicks 还没收场给空。 */
    private TaskResult run(TravelTask task, int maxTicks, IntConsumer beforeTick) {
        task.start(tick);
        for (int i = 0; i < maxTicks; i++) {
            beforeTick.accept(i);
            if (task.tick(tick) instanceof TickResult.Finished finished) {
                finishedAt = i;
                return finished.result();
            }
            tick.nextTick();
        }
        return null;
    }

    /** 一段停在原地不动的走路：脚一直在 feet。 */
    private static ScriptedWalk standingAt(BlockPos feet) {
        return new ScriptedWalk(WalkReport.onTheWay(feet, false));
    }

    @Test
    void diggingNewCellsKeepsGoingPastThirtySecondsAndGettingCloserRestartsTheAllowance() {
        // 徒手往下挖、在墙底下打洞：每 150 刻挖开一格新的，离目的地没近，一分多钟也不判卡住；
        // 第 2000 刻往前挪近了 5 格，额度从头算，挖到第 4000 刻（离开始早过了 2 分钟）还在挖。
        List<WalkReport> script = new ArrayList<>();
        for (int i = 0; i < 2000; i++) script.add(WalkReport.onTheWay(BlockPos.ZERO.above(64), false));
        script.add(WalkReport.onTheWay(new BlockPos(5, 64, 0), false));
        walks.enqueue(new ScriptedWalk(script.toArray(WalkReport[]::new)));

        TaskResult result = run(task(), 4000, i -> {
            if (i % 150 == 0) placed.broken.add("0, 63, " + i + " 的 minecraft:stone");
        });

        assertNull(result, "一直在按路线挖开新格，不该判卡住");
    }

    @Test
    void diggingTheSameCellAgainAndAgainIsStuckAfterSixHundredTicks() {
        // 沙砾落下来又挖：同一格挖了又挖攒不出进展，照旧 600 刻判卡住。
        walks.enqueue(standingAt(new BlockPos(0, 64, 0)));

        TaskResult result = run(task(), 2000, i -> {
            if (i % 100 == 50) placed.broken.add("0, 65, 0 的 minecraft:gravel");
        });

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertTrue(finishedAt < 760, () -> "收场在第 " + finishedAt + " 刻");
    }

    @Test
    void walkingBackAndForthIsStuckAfterSixHundredTicks() {
        // 两格之间来回走：站过的格不再算新格，照旧 600 刻判卡住。
        List<WalkReport> script = new ArrayList<>();
        for (int i = 0; i < 2000; i++) script.add(WalkReport.onTheWay(new BlockPos(0, 64, i % 2), false));
        walks.enqueue(new ScriptedWalk(script.toArray(WalkReport[]::new)));

        TaskResult result = run(task(), 2000, i -> {});

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertTrue(finishedAt < 700, () -> "收场在第 " + finishedAt + " 刻");
    }

    @Test
    void afterTwoMinutesWithoutGettingCloserOnlyDistanceCounts() {
        // 一直挖新格、一直没近：2 分钟（2400 刻）额度用完后只认距离，再 600 刻判卡住，说明里写挖了几格、站上几个新格。
        walks.enqueue(standingAt(new BlockPos(0, 64, 0)));

        TaskResult result = run(task(), 5000, i -> {
            if (i % 100 == 0) placed.broken.add("0, 63, " + i + " 的 minecraft:stone");
        });

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertTrue(finishedAt > 2400 + 500 && finishedAt < 2400 + 700, () -> "收场在第 " + finishedAt + " 刻");
        String message = result.problem().message();
        assertTrue(message.contains("这期间按路线挖开") && message.contains("绕路额度 120 秒用完了"), message);
    }

    @Test
    void newCellsMoreThanEightBlocksFartherThanBestDoNotCount() {
        // 往目的地反方向越走越远：比最好时远出 8 格以外，站上的新格不算进展，照旧 600 刻判卡住。
        List<WalkReport> script = new ArrayList<>();
        script.add(WalkReport.onTheWay(new BlockPos(0, 64, 0), false));
        for (int i = 0; i < 2000; i++) script.add(WalkReport.onTheWay(new BlockPos(-9 - i, 64, 0), false));
        walks.enqueue(new ScriptedWalk(script.toArray(WalkReport[]::new)));

        TaskResult result = run(task(), 2000, i -> {});

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertTrue(finishedAt < 700, () -> "收场在第 " + finishedAt + " 刻");
    }
}
