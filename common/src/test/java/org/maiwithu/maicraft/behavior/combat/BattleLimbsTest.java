// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 战斗手脚槽：走路的寻路同一件事沿用、目标挪远重算、走不通记账；出手的同一下沿用、被拒记站位绕开、连着被拒到上限报告放弃。 */
class BattleLimbsTest {

    private record Tick(long gameTick) implements TickContext {
        @Override public PlayerContext player() {
            return null;
        }
    }

    /** 按脚本逐刻回状态的替身动作；记下收尾、暂停各发生几次。 */
    private static class Scripted implements Action {
        final List<ActionStatus> script;
        int closed;
        int paused;

        Scripted(ActionStatus... statuses) {
            script = new ArrayList<>(List.of(statuses));
        }

        @Override public ActionStatus tick(TickContext context) {
            return script.isEmpty() ? ActionStatus.running() : script.remove(0);
        }

        @Override public String describe() {
            return "替身";
        }

        @Override public void close() {
            closed++;
        }

        @Override public void pause() {
            paused++;
        }
    }

    /** 动手替身：起路给下一趟的替身动作，出手给下一刀的替身动作。 */
    private static final class Moves implements BattleMoves {
        final List<Action> walks = new ArrayList<>();
        final List<Action> strikes = new ArrayList<>();
        int walkStarts;
        int strikeStarts;

        @Override public Action walk(GoalCompiler.Compiled goal) {
            walkStarts++;
            return walks.isEmpty() ? new Scripted() : walks.remove(0);
        }

        @Override public Action strike(TickContext context, int entityId) {
            strikeStarts++;
            return strikes.isEmpty() ? new Scripted() : strikes.remove(0);
        }

        @Override public Action shoot(TickContext context, int entityId) {
            return new Scripted();
        }

        @Override public boolean canShoot(TickContext context) {
            return false;
        }

        @Override public boolean hasMeleeWeapon(TickContext context) {
            return true;
        }

        @Override public boolean attackReady(TickContext context) {
            return true;
        }

        @Override public void guard(TickContext context, double distance, boolean aboutToStrike) {}

        @Override public void lowerGuard() {}

        @Override public Footwork.Ground ground(TickContext context) {
            return (dx, dz) -> Footwork.Footing.FLAT;
        }

        @Override public void step(TickContext context, int faceEntityId, Footwork.Step step) {}

        @Override public double[] selfPosition(TickContext context) {
            return new double[] {0, 64, 0};
        }

        @Override public boolean standable(TickContext context, int x, int y, int z) {
            return true;
        }

        @Override public String mainHand(TickContext context) {
            return "minecraft:iron_sword";
        }
    }

    /** 原生攻击没得到确认的失败状态。 */
    private static final Problem REJECTED = Problem.of(Problem.Kind.REFUSED_BY_GAME, "没确认");

    private static TickContext tick(long n) {
        return new Tick(n);
    }

    @Test
    void walkIsReusedWhileTheJobAndAnchorStay() {
        Moves moves = new Moves();
        BattleLimbs limbs = new BattleLimbs(moves, 3);
        Scripted leg = new Scripted(ActionStatus.progressed(), ActionStatus.progressed());
        moves.walks.add(leg);
        assertTrue(limbs.follow(tick(0), "逼近 1", 10, 0, () -> null));
        assertTrue(limbs.follow(tick(1), "逼近 1", 11, 0, () -> null));
        assertEquals(1, moves.walkStarts, "同一件事、目标没挪远：沿用进行中的这段路");
        assertEquals(0, leg.closed);
        assertSame(leg, limbs.walk());
    }

    @Test
    void walkReplansWhenTheTargetMovesAwayOrTheJobChanges() {
        Moves moves = new Moves();
        BattleLimbs limbs = new BattleLimbs(moves, 3);
        Scripted first = new Scripted(ActionStatus.progressed());
        Scripted second = new Scripted(ActionStatus.progressed());
        moves.walks.add(first);
        moves.walks.add(second);
        assertTrue(limbs.follow(tick(0), "逼近 1", 10, 0, () -> null));
        // 目标挪开三格以上：收掉重算。
        assertTrue(limbs.follow(tick(1), "逼近 1", 14, 0, () -> null));
        assertEquals(1, first.closed);
        // 换了事：同样重算。
        Scripted third = new Scripted(ActionStatus.progressed());
        moves.walks.add(third);
        assertTrue(limbs.follow(tick(2), "逼近 2", 14, 0, () -> null));
        assertEquals(1, second.closed);
        assertEquals(3, moves.walkStarts);
    }

    @Test
    void failedWalkIsDroppedAndReportedOnce() {
        Moves moves = new Moves();
        BattleLimbs limbs = new BattleLimbs(moves, 3);
        Scripted leg = new Scripted(ActionStatus.failed(REJECTED));
        moves.walks.add(leg);
        assertFalse(limbs.follow(tick(0), "逼近 1", 10, 0, () -> null), "走不通这刻没有进展");
        assertTrue(limbs.lastWalkFailed(), "走不通要告诉引擎记账");
        assertEquals(1, leg.closed, "走不通的这段当场收掉");
        assertNull(limbs.walk());
        // 下一刻另起一段，记账翻篇。
        moves.walks.add(new Scripted(ActionStatus.progressed()));
        assertTrue(limbs.follow(tick(1), "逼近 1", 10, 0, () -> null));
        assertFalse(limbs.lastWalkFailed());
    }

    @Test
    void strikeIsReusedForTheSameTargetAndDroppedForAnother() {
        Moves moves = new Moves();
        BattleLimbs limbs = new BattleLimbs(moves, 3);
        Scripted first = new Scripted(ActionStatus.running(), ActionStatus.running());
        moves.strikes.add(first);
        BattleLimbs.Swing swing = limbs.swing(tick(0), 1, 0, 0, moves::strike);
        assertFalse(swing.settled(), "还在进行没有结果");
        swing = limbs.swing(tick(1), 1, 0, 0, moves::strike);
        assertFalse(swing.settled());
        assertEquals(1, moves.strikeStarts, "同一只沿用进行中的出手");
        assertEquals(0, first.closed);
        // 换了打的对象：先收手再起下一下。
        moves.strikes.add(new Scripted(ActionStatus.done()));
        swing = limbs.swing(tick(2), 2, 0, 0, moves::strike);
        assertEquals(1, first.closed);
        assertTrue(swing.struck(), "打中了这算有了结果");
        assertEquals(2, moves.strikeStarts);
        assertNull(limbs.strike(), "打中了清掉等下一下");
    }

    @Test
    void busyEntranceReportsNoResultAndKeepsTheStrike() {
        Moves moves = new Moves();
        BattleLimbs limbs = new BattleLimbs(moves, 3);
        // 交互入口被上一下占着：出手动作在内部按"本刻没轮到"回 Running 等待，槽位照常收下这个结论，
        // 动作留在手上，下一刻再挥；不抛异常，也不把没轮到记成被拒。
        Action busy = new Action() {
            @Override public ActionStatus tick(TickContext context) {
                return ActionStatus.running();
            }

            @Override public String describe() {
                return "占着入口";
            }
        };
        moves.strikes.add(busy);
        BattleLimbs.Swing swing = limbs.swing(tick(0), 1, 5, 5, moves::strike);
        assertFalse(swing.settled());
        assertSame(busy, limbs.strike());
        assertTrue(limbs.sidestepHazard().isEmpty(), "没被拒，不用绕开谁");
        assertTrue(limbs.sidestepHazard().isEmpty(), "没被拒，不用绕开谁");
    }

    @Test
    void rejectedStrikeSidestepsAndGivesUpAfterTheLimit() {
        Moves moves = new Moves();
        BattleLimbs limbs = new BattleLimbs(moves, 3);
        moves.strikes.add(new Scripted(ActionStatus.failed(REJECTED)));
        moves.strikes.add(new Scripted(ActionStatus.failed(REJECTED)));
        assertTrue(limbs.swing(tick(0), 1, 5, 5, moves::strike).rejected());
        assertTrue(limbs.sidestepActive(), "被拒一次：接下来绕开刚才的站位");
        assertTrue(limbs.sidestepHazard().isPresent());
        // 绕开的刻数过掉一刻；第二次被拒还没到上限。
        limbs.sidestepTick();
        assertTrue(limbs.sidestepActive());
        moves.strikes.add(new Scripted(ActionStatus.failed(REJECTED)));
        assertFalse(limbs.swing(tick(1), 1, 5, 5, moves::strike).givenUp());
        moves.strikes.add(new Scripted(ActionStatus.failed(REJECTED)));
        BattleLimbs.Swing third = limbs.swing(tick(2), 1, 5, 5, moves::strike);
        assertTrue(third.rejected() && third.givenUp(), "连着被拒到上限：报告放弃");
        assertFalse(limbs.sidestepActive(), "都放弃了，不用再绕");
        assertTrue(limbs.sidestepHazard().isEmpty());
        // 放弃后计数从头数：再被拒又只是绕开，不再报告放弃。
        moves.strikes.add(new Scripted(ActionStatus.failed(REJECTED)));
        BattleLimbs.Swing fresh = limbs.swing(tick(2), 1, 5, 5, moves::strike);
        assertTrue(fresh.rejected() && !fresh.givenUp());
        assertTrue(limbs.sidestepActive());
    }

    @Test
    void rejectCountResetsWhenRetargetingOrLanding() {
        Moves moves = new Moves();
        BattleLimbs limbs = new BattleLimbs(moves, 3);
        moves.strikes.add(new Scripted(ActionStatus.failed(REJECTED)));
        moves.strikes.add(new Scripted(ActionStatus.failed(REJECTED)));
        limbs.swing(tick(0), 1, 5, 5, moves::strike);
        limbs.swing(tick(1), 1, 5, 5, moves::strike);
        // 换对象（引擎 retarget 时叫）：被拒计数从头数，不把上一只的账算到这一只头上。
        limbs.resetRejects();
        moves.strikes.add(new Scripted(ActionStatus.failed(REJECTED)));
        BattleLimbs.Swing swing = limbs.swing(tick(2), 2, 5, 5, moves::strike);
        assertTrue(swing.rejected() && !swing.givenUp());
        // 打中一次也清零。
        limbs.resetRejects();
        moves.strikes.add(new Scripted(ActionStatus.done()));
        assertTrue(limbs.swing(tick(3), 2, 5, 5, moves::strike).struck());
        moves.strikes.add(new Scripted(ActionStatus.failed(REJECTED)));
        swing = limbs.swing(tick(4), 2, 5, 5, moves::strike);
        assertTrue(swing.rejected() && !swing.givenUp());
    }

    @Test
    void neverGivesUpWhenTheLimitIsNotBound() {
        Moves moves = new Moves();
        // 打龙这边被拒不设上限：每次被拒都只是换个角度。
        BattleLimbs limbs = new BattleLimbs(moves, Integer.MAX_VALUE);
        for (int i = 0; i < 5; i++) {
            moves.strikes.add(new Scripted(ActionStatus.failed(REJECTED)));
            BattleLimbs.Swing swing = limbs.swing(tick(i), 7, 5, 5, moves::strike);
            assertTrue(swing.rejected() && !swing.givenUp());
        }
        assertTrue(limbs.sidestepActive());
    }

    @Test
    void dropsAndPauseCloseWhateverIsHeld() {
        Moves moves = new Moves();
        BattleLimbs limbs = new BattleLimbs(moves, 3);
        Scripted leg = new Scripted(ActionStatus.progressed());
        Scripted blow = new Scripted(ActionStatus.running());
        moves.walks.add(leg);
        moves.strikes.add(blow);
        limbs.follow(tick(0), "逼近 1", 10, 0, () -> null);
        limbs.swing(tick(0), 1, 5, 5, moves::strike);
        limbs.pause();
        assertEquals(1, leg.paused);
        assertEquals(1, blow.paused);
        limbs.drop();
        assertEquals(1, leg.closed);
        assertEquals(1, blow.closed);
        assertNull(limbs.walk());
        assertNull(limbs.strike());
    }
}
