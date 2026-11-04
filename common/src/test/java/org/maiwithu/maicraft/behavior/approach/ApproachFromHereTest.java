// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;

/**
 * 靠近的"从这里到不了"：某个站位因为从这里到不了走不通，剩下的站位合成一个目标再算一次；
 * 合起来也走不通就记下、按到不了收场，同一个目标运行里、起点 8 格内再靠近同一个对象直接收场；
 * 换了目标运行、离开起点 8 格以上就失效；一般的走不通照旧一个个换站位。
 */
class ApproachFromHereTest {

    private static final ReachRules REACH = new ReachRules(4.5, 3.0, 3, 1.62);
    private static final BlockPos CHEST = new BlockPos(3, 64, 0);
    /** 对箱子够得着、看得见的站位：合起来的那一趟走到这里。 */
    private static final BlockPos GOOD_SPOT = new BlockPos(2, 64, 0);

    private final StubWorld world = new StubWorld();
    private long goalRun = 7;
    private final UnreachableFromHere unreachable = new UnreachableFromHere(() -> OptionalLong.of(goalRun));

    private Approach approach(StubMoves moves) {
        return new Approach(ApproachTarget.ofBlock(CHEST), REACH, world, new StubWalkCost(), new StubGuarded(),
                moves, TerrainPermit.WALK_ONLY, null, Permissions.DEFAULT, null, unreachable);
    }

    private static ActionStatus noRoute() {
        return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "连续算路 15 秒仍没有可走的路线"));
    }

    // 推进到有结论为止，最多这么多刻。
    private static ActionStatus runToEnd(Approach approach) {
        for (int tick = 1; tick <= 20; tick++) {
            ActionStatus status = approach.tick(new StubTick(tick));
            if (status instanceof ActionStatus.Done || status instanceof ActionStatus.Failed) return status;
        }
        throw new AssertionError("20 刻内没有结论");
    }

    // 两个站位都从这里到不了：合起来再算也走不通，记下这一条后收场。
    private ActionStatus failTwice() {
        world.grounded = false;
        StubMoves moves = new StubMoves(world, noRoute(), noRoute());
        moves.noRouteFromHere = true;
        return runToEnd(approach(moves));
    }

    @Test
    void noRouteFromFirstSpotMergesTheRestIntoOneWalk() {
        // 第一个站位从这里到不了：剩下的合成一个目标再算一次，走到了能用的那一格就算靠近了。
        world.grounded = false;
        StubMoves moves = new StubMoves(world, noRoute(), ActionStatus.done());
        moves.noRouteFromHere = true;
        moves.onArrive = () -> world.feet = GOOD_SPOT;
        assertEquals(ActionStatus.done(), runToEnd(approach(moves)));
        assertEquals(2, moves.begun.size(), "只走了第一个站位和合起来的一趟");
        assertEquals("Composite", moves.goals.get(1).goal().getClass().getSimpleName());
    }

    @Test
    void mergedWalkFailingGivesUpAsUnreachableAndSaysWhatWasTried() {
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, failTwice());
        assertEquals(Problem.Kind.UNREACHABLE, failed.problem().kind());
        String message = failed.problem().message();
        assertTrue(message.startsWith("从 0, 64, 0 附近到不了方块 3, 64, 0"), message);
        assertTrue(message.contains("这条路走不通：连续算路"), message);
        assertTrue(message.contains("合起来再算（也走不通"), message);
    }

    @Test
    void sameObjectFromNearbyStartIsGivenUpWithoutWalking() {
        // 刚记下从这里到不了：同一个目标运行里、起点 8 格内再靠近同一只箱子，一步不走直接收场。
        failTwice();
        world.feet = new BlockPos(5, 64, 4);
        StubMoves again = new StubMoves(world);
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, approach(again).tick(new StubTick(1)));
        assertEquals(Problem.Kind.UNREACHABLE, failed.problem().kind());
        assertTrue(failed.problem().message().contains("这次目标里刚试过"), failed.problem().message());
        assertEquals(0, again.begun.size());
    }

    @Test
    void memoryExpiresWithAnotherGoalRun() {
        // 目标结束、换了一个目标运行：记忆失效，照常上路。
        failTwice();
        goalRun = 8;
        StubMoves again = new StubMoves(world, ActionStatus.done());
        again.onArrive = () -> world.feet = GOOD_SPOT;
        assertEquals(ActionStatus.done(), runToEnd(approach(again)));
        assertEquals(1, again.begun.size());
    }

    @Test
    void memoryExpiresAfterLeavingTheStartByMoreThanEightBlocks() {
        // 离开起点 9 格：那一片起点的记忆失效，照常上路。
        failTwice();
        world.feet = new BlockPos(0, 64, 9);
        StubMoves again = new StubMoves(world, ActionStatus.done());
        again.onArrive = () -> world.feet = GOOD_SPOT;
        assertEquals(ActionStatus.done(), runToEnd(approach(again)));
        assertEquals(1, again.begun.size());
    }

    @Test
    void ordinaryFailureKeepsTryingSpotsOneByOne() {
        // 一般的走不通（不是从这里到不了）：不合并，照旧换下一个站位单独上路。
        world.grounded = false;
        StubMoves moves = new StubMoves(world, noRoute(), ActionStatus.done());
        moves.onArrive = () -> world.feet = GOOD_SPOT;
        assertEquals(ActionStatus.done(), runToEnd(approach(moves)));
        assertEquals("Exact", moves.goals.get(1).goal().getClass().getSimpleName());
    }
}
