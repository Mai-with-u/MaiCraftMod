// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.bodylanguage;

import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 逐下做肢体动作：蹲起、点头、摇头、跳、挥手、拍一下按游戏里真发生的数，做不成的写清原因，他走了就收场。 */
class GesturingTest {

    private static final TickContext TICK = new TickContext() {
        @Override public long gameTick() {
            return 0;
        }

        @Override public PlayerContext player() {
            return null;
        }
    };

    // 一直推到做完（或失败），最多 400 刻；每刻之后替身里上一刻的按键生效。
    private static ActionStatus run(Gesturing gesture, FakeNonverbalMoves moves) {
        for (int i = 0; i < 400; i++) {
            ActionStatus status = gesture.tick(TICK);
            moves.advance();
            if (!(status instanceof ActionStatus.Running)) return status;
        }
        throw new AssertionError("400 刻还没做完");
    }

    private static Gesturing gesture(FakeNonverbalMoves moves, NonverbalOperation op, int count) {
        return new Gesturing(moves, op, count, 40, FakeNonverbalMoves.ALEX_PARTNER, NonverbalMoves.AimPoint.EYES);
    }

    // 蹲起 3 下：按下松开各 3 刻，每一下都真蹲下去了；做完松开潜行、不再按着转头。
    @Test
    void crouchesCountWhatTheServerSaw() {
        FakeNonverbalMoves moves = new FakeNonverbalMoves();
        Gesturing crouch = gesture(moves, NonverbalOperation.CROUCH, 3);
        assertInstanceOf(ActionStatus.Done.class, run(crouch, moves));
        assertEquals(3, crouch.done());
        assertNull(crouch.skipped());
        assertEquals(9, moves.pressed.size());
        crouch.close();
        assertFalse(moves.fakeHead().holding);
    }

    // 点头：视线在他脸上往下点 25 度再抬回，2 下 16 刻；最后看着他的脸。
    @Test
    void nodsDipAndComeBack() {
        FakeNonverbalMoves moves = new FakeNonverbalMoves();
        Gesturing nod = gesture(moves, NonverbalOperation.NOD, 2);
        nod.tick(TICK);
        assertEquals(NonverbalRhythm.TURN_DEGREES, moves.fakeHead().pitch);
        assertInstanceOf(ActionStatus.Done.class, run(nod, moves));
        assertEquals(2, nod.done());
        assertEquals(0, moves.fakeHead().pitch);
    }

    // 摇头：先左后右，做完回正。
    @Test
    void shakesLeftRightThenCenters() {
        FakeNonverbalMoves moves = new FakeNonverbalMoves();
        Gesturing shake = gesture(moves, NonverbalOperation.SHAKE_HEAD, 2);
        shake.tick(TICK);
        assertEquals(-NonverbalRhythm.TURN_DEGREES, moves.fakeHead().yaw);
        assertInstanceOf(ActionStatus.Done.class, run(shake, moves));
        assertEquals(2, shake.done());
        assertEquals(0, moves.fakeHead().yaw);
    }

    // 原地跳：离地再落地算一下；头顶贴着方块跳不起来就写清少跳几下。
    @Test
    void jumpsLandBeforeCountingAndGiveUpUnderACeiling() {
        FakeNonverbalMoves moves = new FakeNonverbalMoves();
        Gesturing jump = gesture(moves, NonverbalOperation.JUMP, 2);
        assertInstanceOf(ActionStatus.Done.class, run(jump, moves));
        assertEquals(2, jump.done());

        FakeNonverbalMoves boxed = new FakeNonverbalMoves();
        boxed.ceiling = true;
        Gesturing stuck = gesture(boxed, NonverbalOperation.JUMP, 2);
        run(stuck, boxed);
        assertEquals(0, stuck.done());
        assertTrue(stuck.skipped().contains("少跳 2 下"));
    }

    // 挥手：视线抬到准星什么都碰不到才按攻击键，两下隔半秒；抬到头顶还碰得到就不挥。
    @Test
    void swingsOnlyIntoTheAir() {
        FakeNonverbalMoves moves = new FakeNonverbalMoves();
        moves.clearAbove = -50;
        Gesturing swing = gesture(moves, NonverbalOperation.SWING, 2);
        assertInstanceOf(ActionStatus.Done.class, run(swing, moves));
        assertEquals(2, moves.attacks);
        assertTrue(moves.fakeHead().pitch <= -50);

        FakeNonverbalMoves walled = new FakeNonverbalMoves();
        walled.clearAbove = -100;
        Gesturing blocked = gesture(walled, NonverbalOperation.SWING, 2);
        run(blocked, walled);
        assertEquals(0, walled.attacks);
        assertTrue(blocked.skipped().contains("挥手会挖到"));
    }

    // 拍一下：准星在他身上就按一下；他走了以 TARGET_GONE 收场。
    @Test
    void pokesOnceAndNoticesHeLeft() {
        FakeNonverbalMoves moves = new FakeNonverbalMoves();
        Gesturing poke = new Gesturing(moves, NonverbalOperation.POKE, 1, 0, FakeNonverbalMoves.ALEX_PARTNER,
                NonverbalMoves.AimPoint.CENTER);
        assertInstanceOf(ActionStatus.Done.class, run(poke, moves));
        assertEquals(1, moves.attacks);

        moves.present = false;
        ActionStatus gone = run(gesture(moves, NonverbalOperation.LOOK, 1), moves);
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, gone);
        assertEquals(Problem.Kind.TARGET_GONE, failed.problem().kind());
    }

    // 没有对着的人：对着开始时镜头的方向做，不转身。
    @Test
    void withoutPartnerKeepsTheStartingDirection() {
        FakeNonverbalMoves moves = new FakeNonverbalMoves();
        moves.fakeHead().yaw = 90;
        Gesturing look = new Gesturing(moves, NonverbalOperation.LOOK, 1, 20, null, NonverbalMoves.AimPoint.EYES);
        assertInstanceOf(ActionStatus.Done.class, run(look, moves));
        assertEquals(90, moves.fakeHead().yaw);
        assertNotNull(look.describe());
    }
}
