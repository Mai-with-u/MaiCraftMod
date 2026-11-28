// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.behavior.combat.Footwork;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.behavior.travel.TravelFakes;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 战后打扫：走回一个点按半径算回到、长路的进展从脚位读、收尾途中落地不算走到也不另起一趟；
 * 捡拾一直推到结束、失败原因带出来。替身动作演走路与捡拾，不碰角色对象。
 */
class AfterFightTest {

    private record Tick(long gameTick) implements TickContext {
        @Override public PlayerContext player() {
            throw new IllegalStateException("战后打扫测试不碰角色对象");
        }
    }

    /** 走位替身：人在哪、下一趟走路演什么、普通替身每刻回什么，都由测试摆。 */
    private static final class Moves implements BattleMoves {
        double[] at = {0, 64, 0};
        int walks;
        /** 下一趟寻路照这段脚本演；为 null 时按 walkStatus 回普通替身。 */
        TravelFakes.ScriptedWalk nextWalk;
        ActionStatus walkStatus = ActionStatus.running();

        @Override public Action walk(GoalCompiler.Compiled goal) {
            walks++;
            if (nextWalk != null) {
                Action walk = nextWalk;
                nextWalk = null;
                return walk;
            }
            return new Fixed(() -> walkStatus);
        }

        @Override public double[] selfPosition(TickContext context) { return at.clone(); }

        @Override public Action strike(TickContext context, int entityId) { return new Fixed(ActionStatus::done); }
        @Override public Action shoot(TickContext context, int entityId) { return new Fixed(ActionStatus::running); }
        @Override public boolean canShoot(TickContext context) { return false; }
        @Override public boolean hasMeleeWeapon(TickContext context) { return true; }
        @Override public int ticksToReady(TickContext context) { return 0; }
        @Override public void guard(TickContext context, double distance, boolean aboutToStrike) {}
        @Override public void lowerGuard() {}
        @Override public Footwork.Ground ground(TickContext context) { return (dx, dz) -> Footwork.Footing.FLAT; }
        @Override public void step(TickContext context, int faceEntityId, Footwork.Step step) {}
        @Override public boolean standable(TickContext context, int x, int y, int z) { return true; }
        @Override public String mainHand(TickContext context) { return "minecraft:iron_sword"; }
        @Override public OptionalInt arrowsCarried(TickContext context) { return OptionalInt.empty(); }
    }

    private record Fixed(Supplier<ActionStatus> status) implements Action {
        @Override public ActionStatus tick(TickContext context) { return status.get(); }
        @Override public String describe() { return "替身"; }
    }

    private final Moves moves = new Moves();
    private final List<String> progress = new ArrayList<>();

    private AfterFight aftermath(boolean[] stopping) {
        return new AfterFight(moves, progress::add, () -> stopping[0]);
    }

    private static AfterFight.Spot spot(double x, double arriveRadius, double walkRadius) {
        return new AfterFight.Spot(new double[] {x, 64, 0}, arriveRadius, walkRadius);
    }

    @Test
    void withinTheArriveRadiusCountsAsBackWithoutStartingAWalk() {
        // 人已经在半径内：当刻就算回到，不起寻路。
        AfterFight aftermath = aftermath(new boolean[] {false});
        moves.at = new double[] {1, 64, 0};
        assertEquals(AfterFight.BackStep.NEAR,
                aftermath.walkBack(new Tick(0), spot(0, 1.5, 1.0), "走回打点", () -> "替身在走"));
        assertEquals(0, moves.walks);
        assertTrue(progress.isEmpty());
    }

    @Test
    void longWalkFeedsProgressFromTheFeetPosition() {
        // 几十格的长路：走到在路上不逐刻自报进展（只说「还在走」），进展要从脚位挪格读。
        AfterFight aftermath = aftermath(new boolean[] {false});
        moves.at = new double[] {60, 64, 0};
        List<WalkReport> leg = new ArrayList<>();
        for (int i = 0; i < 5; i++) leg.add(WalkReport.onTheWay(new BlockPos(60 - i, 64, 0), false));
        moves.nextWalk = new TravelFakes.ScriptedWalk(leg.toArray(WalkReport[]::new));
        for (int t = 0; t < 5; t++) {
            assertEquals(AfterFight.BackStep.WALKING,
                    aftermath.walkBack(new Tick(t), spot(0, 1.5, 1.0), "走回打点", () -> "替身在走"));
        }
        assertEquals(1, moves.walks, "同一段路不每刻重起");
        assertTrue(progress.get(0).contains("走回打点：走到 "), progress.toString());
    }

    @Test
    void stubWalkFeedsProgressFromTheActionStatus() {
        // 替身动作不是走到运行：退回按动作状态记进展，有进展就记替身给的那句话。
        AfterFight aftermath = aftermath(new boolean[] {false});
        moves.at = new double[] {5, 64, 0};
        moves.walkStatus = ActionStatus.progressed();
        assertEquals(AfterFight.BackStep.WALKING,
                aftermath.walkBack(new Tick(0), spot(0, 1.5, 1.0), "走回打点", () -> "替身在挪"));
        assertEquals(List.of("替身在挪"), progress);
        assertEquals("替身", aftermath.walkDescribe());
    }

    @Test
    void aWalkThatCannotGetThereIsReportedAsFailed() {
        AfterFight aftermath = aftermath(new boolean[] {false});
        moves.at = new double[] {5, 64, 0};
        moves.walkStatus = ActionStatus.failed(Problem.of(
                Problem.Kind.UNREACHABLE, "掉进坑里了"));
        assertEquals(AfterFight.BackStep.WALK_FAILED,
                aftermath.walkBack(new Tick(0), spot(0, 1.5, 1.0), "走回打点", () -> "替身在走"));
    }

    @Test
    void landingDuringWindDownDoesNotCountAsArrivedAndDoesNotStartAgain() {
        // 收尾途中：还没起路不另起一趟；在走的那趟落地报「已停下」不算走到，站着等；
        // 外层停稳放回来后，那趟走完了才按离点多远重新起步。
        boolean[] stopping = {false};
        AfterFight aftermath = aftermath(stopping);
        moves.at = new double[] {10, 64, 0};
        TravelFakes.ScriptedWalk leg = new TravelFakes.ScriptedWalk(
                WalkReport.onTheWay(new BlockPos(9, 65, 0), false),
                WalkReport.stopped(new BlockPos(9, 64, 0), false));
        leg.airborne = true;
        moves.nextWalk = leg;
        assertEquals(AfterFight.BackStep.WALKING, aftermath.walkBack(new Tick(0), spot(0, 1.5, 1.0), "走回打点", () -> "替身在走"));
        // 进收尾：落地报的「已停下」不算走到，那一趟留着不收。
        stopping[0] = true;
        assertEquals(AfterFight.BackStep.WAITING_TO_STOP,
                aftermath.walkBack(new Tick(1), spot(0, 1.5, 1.0), "走回打点", () -> "替身在走"));
        assertEquals(0, leg.closes, "收尾途中那一趟留着不收");
        assertEquals(1, moves.walks, "收尾途中不另起一趟");
        // 收尾途中的那一趟结束了（替身没脚本了），也不收掉不重起。
        assertEquals(AfterFight.BackStep.WAITING_TO_STOP,
                aftermath.walkBack(new Tick(2), spot(0, 1.5, 1.0), "走回打点", () -> "替身在走"));
        assertEquals(1, moves.walks);
        // 放回来接着做：走完的那趟收掉，再一刻人还没进半径就重新起路。
        stopping[0] = false;
        assertEquals(AfterFight.BackStep.WALK_ENDED,
                aftermath.walkBack(new Tick(3), spot(0, 1.5, 1.0), "走回打点", () -> "替身在走"));
        assertEquals(1, leg.closes, "放回来后走完的那趟收掉");
        assertEquals(AfterFight.BackStep.WALKING,
                aftermath.walkBack(new Tick(4), spot(0, 1.5, 1.0), "走回打点", () -> "替身在走"));
        assertEquals(2, moves.walks, "接着做时按离点多远重新起步");
    }

    @Test
    void noWalkIsStartedWhileStopping() {
        // 还没起路就进了收尾：站着等外层停稳，不开新的一段。
        boolean[] stopping = {true};
        AfterFight aftermath = aftermath(stopping);
        moves.at = new double[] {10, 64, 0};
        assertEquals(AfterFight.BackStep.WAITING_TO_STOP,
                aftermath.walkBack(new Tick(0), spot(0, 1.5, 1.0), "走回打点", () -> "替身在走"));
        assertEquals(0, moves.walks);
    }

    @Test
    void pickUpRunsUntilItEndsAndBringsOutWhyItFailed() {
        AfterFight aftermath = aftermath(new boolean[] {false});
        int[] ticks = {0};
        // 三刻捡完：第一刻有进展记一句，第二刻只说还在捡（不记），第三刻捡成；结束不带失败原因。
        Supplier<Action> pick = () -> new Fixed(() -> switch (++ticks[0]) {
            case 1 -> ActionStatus.progressed();
            case 2 -> ActionStatus.running();
            default -> ActionStatus.done();
        });
        assertEquals(AfterFight.PickStep.PICKING, aftermath.pickUp(new Tick(0), pick));
        assertEquals(AfterFight.PickStep.PICKING, aftermath.pickUp(new Tick(1), () -> null));
        assertEquals(List.of("替身"), progress, "有进展才记一句");
        assertNull(aftermath.pickupProblem());
        assertEquals(AfterFight.PickStep.PICKED, aftermath.pickUp(new Tick(2), () -> null));
        assertNull(aftermath.pickupProblem(), "捡成了不带失败原因");
        // 再捡一次没捡成：失败原因带出来，调用方如实交代。
        assertEquals(AfterFight.PickStep.PICKED, aftermath.pickUp(new Tick(3), () -> new Fixed(() ->
                ActionStatus.failed(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "隔着水够不着")))));
        assertEquals("隔着水够不着", aftermath.pickupProblem());
    }
}
