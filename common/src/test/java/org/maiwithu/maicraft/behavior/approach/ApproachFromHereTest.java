// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalLong;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.survival.SurvivalFakes;
import org.maiwithu.maicraft.behavior.survival.breath.UnderwaterWalkBudget;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;

/**
 * 靠近的"从这里到不了"：某个站位因为从这里到不了走不通，剩下的站位合成一个目标再算一次；
 * 合起来也走不通就记下、按到不了收场，同一个目标运行里、起点 8 格内再靠近同一个对象直接收场；
 * 换了目标运行、离开起点 8 格以上就失效；一般的走不通照旧一个个换站位。走路自己按呼吸预算憋不住放弃的，
 * 一样算从这里到不了。
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
        return approach(moves, Approach.UNBOUNDED_WALK_BUDGET);
    }

    private Approach approach(StubMoves moves, long walkBudgetTicks) {
        return new Approach(ApproachTarget.ofBlock(CHEST), REACH, world, new StubWalkCost(), new StubGuarded(),
                moves, new StubStrolls(world), TerrainPermit.WALK_ONLY, null, Permissions.DEFAULT, null, unreachable,
                walkBudgetTicks);
    }

    private static ActionStatus noRoute() {
        return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "连续算路 15 秒仍没有可走的路线"));
    }

    // 走路自己按呼吸预算放弃这段路时交回的收场问题：氧气只剩一泡。
    private static ActionStatus outOfBreath() {
        return ActionStatus.failed(UnderwaterWalkBudget.giveUpProblem(
                SurvivalFakes.calm().underwater(30).build(), new BlockPos(1, 60, 0)));
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
    void walkGivingUpOnItsOwnBreathAlsoMergesOnceAndIsRemembered() {
        // 换气插不进来（比如主任务把生存需求全关了），走路自己按呼吸预算憋不住放弃：同样从这里到不了，
        // 剩下的站位合起来只再算一次，也不行就记下收场；起点附近再靠近同一只箱子一步不走直接收场。
        world.grounded = false;
        StubMoves moves = new StubMoves(world, outOfBreath(), outOfBreath());
        moves.noRouteFromHere = true;
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, runToEnd(approach(moves)));
        assertEquals(2, moves.begun.size(), "只走了第一个站位和合起来的一趟");
        assertEquals("Composite", moves.goals.get(1).goal().getClass().getSimpleName());
        assertTrue(failed.problem().message().contains("氧气只剩 30"), failed.problem().message());

        StubMoves again = new StubMoves(world);
        assertInstanceOf(ActionStatus.Failed.class, approach(again).tick(new StubTick(1)));
        assertEquals(0, again.begun.size());
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

    @Test
    void givingUpWithSpotsExhaustedIsAlsoRemembered() {
        // 站位耗尽的放弃同样是"从这里到不了"：也记下来，起点附近再靠近同一个对象一步不走直接收场，
        // 不把同一批候选位置重新挑一遍（实机砌筑同一批格跨目标原样重败）。
        world.hidden = (eye, t) -> true;
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, runToEnd(approach(new StubMoves(world))));
        assertEquals(Problem.Kind.UNREACHABLE, failed.problem().kind());
        assertTrue(failed.problem().message().contains("候选位置"), failed.problem().message());

        StubMoves again = new StubMoves(world);
        ActionStatus.Failed recalled = assertInstanceOf(ActionStatus.Failed.class, approach(again).tick(new StubTick(1)));
        assertEquals(Problem.Kind.UNREACHABLE, recalled.problem().kind());
        assertTrue(recalled.problem().message().contains("这次目标里刚试过"), recalled.problem().message());
        assertEquals(0, again.begun.size(), "记住了就不再重新挑站位");
    }

    @Test
    void highSpotNoRouteDropsRestAndRemembersUnreachableInOneWalk() {
        // 高处墙体或屋顶只有比角色高出一跳以上的候选站位：首趟半截路或折返按从这里到不了收场时，
        // 先批量丢弃其余高处候选，不必再花第二趟合并重算，并直接写入 8 格邻域不可达记忆；
        // 随后角色停在邻格再要靠近同一高处目标时一步不走直接收场。
        world.grounded = false;
        world.feet = new BlockPos(0, 64, 0);
        BlockPos highBlock = new BlockPos(3, 70, 0);
        world.hidden = (eye, target) -> eye.y() < 69.0;
        StubMoves moves = new StubMoves(world,
                ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE, "路线到不了目标，只能往那边走半截，持续没能离目标更近")));
        moves.noRouteFromHere = true;
        ApproachTarget highTarget = ApproachTarget.ofBlock(highBlock);
        Approach first = new Approach(highTarget, REACH, world, new StubWalkCost(), new StubGuarded(),
                moves, new StubStrolls(world), TerrainPermit.WALK_ONLY, null, Permissions.DEFAULT, null, unreachable);

        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, runToEnd(first));
        assertEquals(Problem.Kind.UNREACHABLE, failed.problem().kind());
        assertEquals(1, moves.begun.size(), "高处站位首趟到不了后直接批删其余高处候选，不再白跑第二趟");
        assertTrue(first.tried().stream().anyMatch(attempt -> attempt.tried().contains("一跳")
                && attempt.result().contains("不再挨个爬")), first.tried().toString());

        // 角色挪到邻格（仍在 8 格邻域内）再尝试靠近同一高处目标：直接命中不可达记忆，零算路收场。
        world.feet = new BlockPos(2, 64, 1);
        StubMoves secondMoves = new StubMoves(world);
        Approach second = new Approach(highTarget, REACH, world, new StubWalkCost(), new StubGuarded(),
                secondMoves, new StubStrolls(world), TerrainPermit.WALK_ONLY, null, Permissions.DEFAULT, null, unreachable);
        ActionStatus.Failed recalled = assertInstanceOf(ActionStatus.Failed.class, second.tick(new StubTick(1)));
        assertEquals(Problem.Kind.UNREACHABLE, recalled.problem().kind());
        assertTrue(recalled.problem().message().contains("这次目标里刚试过"), recalled.problem().message());
        assertEquals(0, secondMoves.begun.size(), "邻格复用 8 格不可达记忆，不再重复上路");
    }

    @Test
    void defaultApproachWithoutWalkBudgetDoesNotCutOffNormalLongWalk() {
        // 默认不设总走步硬上限（供找床、翻箱、采矿等中远距完整路径使用）：正常整路连走 500 刻绝不误杀，
        // 也不污染写入 8 格不可达记忆。
        world.grounded = false;
        world.feet = new BlockPos(0, 64, 0);
        StubMoves moves = new StubMoves(world);
        moves.keepRunning = true;
        Approach unbounded = approach(moves);

        ActionStatus status = ActionStatus.running();
        for (int tick = 1; tick <= 500; tick++) {
            status = unbounded.tick(new StubTick(tick));
            assertInstanceOf(ActionStatus.Running.class, status, "无总预算时正常长途整路不应被提前腰斩，tick=" + tick);
        }
        assertEquals(List.of(Approach.LOCAL_PARTIAL_ROUTE_LIMIT_TICKS), moves.partialLimits,
                "仍向下传递局部半截路止损上限以防半截路空转");
        assertEquals(0, moves.closes, "正常走途中不应强行收掉走路运行");
    }

    @Test
    void approachWithExplicitWalkBudgetSelfClosesIntoUnreachableBeforeOuterTimeout() {
        // 施工等挂了外层超时（如 400 刻）的调用方显式传入略短预算（380 刻）：
        // 即便多趟累加逼近外层超时，靠近也在内部预算到点时自主收尾并写入 8 格不可达记忆，不再被外层强杀切断缓存。
        world.grounded = false;
        world.feet = new BlockPos(0, 64, 0);
        StubMoves moves = new StubMoves(world);
        moves.keepRunning = true;
        long explicitBudget = BringsPlayerClose.WALK_PATIENCE_TICKS - 20L;
        Approach bounded = approach(moves, explicitBudget);

        ActionStatus status = ActionStatus.running();
        int ticks = 0;
        while (status instanceof ActionStatus.Running && ticks < BringsPlayerClose.WALK_PATIENCE_TICKS) {
            status = bounded.tick(new StubTick(++ticks));
        }
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, status,
                "显式传预算时应早于外层 400 刻预算自主收场，实际推进刻数：" + ticks);
        assertEquals(Problem.Kind.UNREACHABLE, failed.problem().kind());
        assertTrue(ticks < BringsPlayerClose.WALK_PATIENCE_TICKS, "必须早于外层 400 刻强杀收场：" + ticks);
        assertEquals(List.of(Approach.LOCAL_PARTIAL_ROUTE_LIMIT_TICKS), moves.partialLimits,
                "靠近动作应向下传递局部半截路止损上限");
        assertEquals(1, moves.closes, "自主收场时必须关掉手上走路运行");

        // 随后从邻格再次靠近同一对象：直接命中 8 格不可达记忆，一步不走立刻收场。
        world.feet = new BlockPos(2, 64, 1);
        StubMoves secondMoves = new StubMoves(world);
        ActionStatus.Failed recalled = assertInstanceOf(ActionStatus.Failed.class,
                approach(secondMoves, explicitBudget).tick(new StubTick(1)));
        assertEquals(Problem.Kind.UNREACHABLE, recalled.problem().kind());
        assertEquals(0, secondMoves.begun.size(), "内部超时收场已落入 8 格不可达记忆，邻格零算路直接收场");
    }
}
