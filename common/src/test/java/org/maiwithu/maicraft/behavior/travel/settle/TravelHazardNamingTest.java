// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.settle;

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
import org.maiwithu.maicraft.behavior.travel.planning.HazardCell;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TickResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 出行走不通收场时，失败说明点名目的地旁边因为危险没踩的格；只给 x/z 的目的地和程序出错不点名。 */
class TravelHazardNamingTest {

    private final FakeWorld world = new FakeWorld();
    private final FakeWalks walks = new FakeWalks();
    private final TestTick tick = new TestTick();

    private TravelTask task(Target target, TerrainPermit permit) {
        DestinationResolver resolver = new DestinationResolver(world, new FakePlaces(), new FakeSeen());
        var ready = assertInstanceOf(DestinationResolver.Resolution.Ready.class, resolver.resolve(target, 0));
        return new TravelTask(ready.destination(), 0, permit, walks, new FakePlaced(), new ProgressRecorder(),
                world, null, null, Permissions.DEFAULT);
    }

    private TaskResult runToFinish(TravelTask task, int maxTicks) {
        task.start(tick);
        for (int i = 0; i < maxTicks; i++) {
            if (task.tick(tick) instanceof TickResult.Finished finished) return finished.result();
            tick.nextTick();
        }
        throw new AssertionError("出行任务没有按脚本收场");
    }

    @Test
    void noRouteNamesTheLitCampfireBesideTheDestination() {
        // 唯一入口是点着的营火、寻路给不出路线：说明点名营火在目的地西 2 格、为什么没踩，建议先灭火。
        world.hazards.add(new HazardCell(new BlockPos(10, 64, -2), "minecraft:campfire", "点着了，站上去会烧伤"));
        walks.enqueue(new ScriptedWalk(WalkReport.failed(
                Problem.of(Problem.Kind.UNREACHABLE, "没有能走到目标的路线"), new BlockPos(3, 64, 1), false)));

        TaskResult result = runToFinish(task(new Target.Position(12, 64, -2, null), TerrainPermit.NATURAL), 50);

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.UNREACHABLE, result.problem().kind());
        assertEquals(new BlockPos(12, 64, -2), world.hazardsAskedAt);
        assertEquals(DestinationHazards.RADIUS, world.hazardsAskedRadius);
        String message = result.problem().message();
        assertTrue(message.startsWith("没有能走到目标的路线；"), message);
        assertTrue(message.contains("minecraft:campfire 1 处，最近一处在 (10, 64, -2)、目的地西 2.0 格、同高（点着了，站上去会烧伤）"),
                message);
        assertTrue(result.problem().suggestion().contains("灭火"), result.problem().suggestion());
    }

    @Test
    void idleEndKeepsPermitAdviceAfterHazardAdvice() {
        // 不许动土、空转满时限收场：危险格的处理办法先说，"许可动土"的建议照留。
        world.hazards.add(new HazardCell(new BlockPos(12, 64, -1), "minecraft:magma_block", "站上去烫脚"));
        walks.enqueue(new ScriptedWalk(WalkReport.onTheWay(new BlockPos(4, 64, 0), false)));

        TaskResult result = runToFinish(task(new Target.Position(12, 64, -2, null), TerrainPermit.WALK_ONLY), 700);

        assertEquals(Problem.Kind.UNREACHABLE, result.problem().kind());
        assertTrue(result.problem().message().contains("目的地南 1.0 格、同高（站上去烫脚）"), result.problem().message());
        String suggestion = result.problem().suggestion();
        assertTrue(suggestion.startsWith("目的地旁边有因为危险没踩的格") && suggestion.contains("动土"), suggestion);
    }

    @Test
    void columnDestinationDoesNotReadHazards() {
        // 只给 x/z 的目的地高度不知道，不读旁边的格，失败说明照原样。
        world.hazards.add(new HazardCell(new BlockPos(10, 64, -2), "minecraft:campfire", "点着了，站上去会烧伤"));
        walks.enqueue(new ScriptedWalk(WalkReport.failed(
                Problem.of(Problem.Kind.UNREACHABLE, "没有能走到目标的路线"), new BlockPos(3, 64, 1), false)));

        TaskResult result = runToFinish(task(new Target.Position(12, null, -2, null), TerrainPermit.NATURAL), 50);

        assertNull(world.hazardsAskedAt);
        assertEquals("没有能走到目标的路线", result.problem().message());
    }

    @Test
    void programErrorDoesNotNameHazards() {
        // 算路程序出错不是地形问题：不点名危险格，免得游戏 Agent 去灭火。
        world.hazards.add(new HazardCell(new BlockPos(10, 64, -2), "minecraft:campfire", "点着了，站上去会烧伤"));
        walks.enqueue(new ScriptedWalk(WalkReport.failed(
                Problem.of(Problem.Kind.INTERNAL_ERROR, "算路出错"), new BlockPos(3, 64, 1), false)));

        TaskResult result = runToFinish(task(new Target.Position(12, 64, -2, null), TerrainPermit.NATURAL), 50);

        assertFalse(result.problem().message().contains("campfire"), result.problem().message());
    }
}
