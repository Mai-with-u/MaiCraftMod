// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.bodylanguage;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 从采样认肢体动作：蹲起、点头、摇头、原地跳、换手、走开又回头、谁扔的。 */
class NonverbalReadingTest {

    /** 采样替身：从第 0 刻起每刻一份，按需改其中几样。 */
    private static final class Poses {
        final List<PlayerPose> list = new ArrayList<>();
        long tick;
        double x;
        double y = 64;
        boolean onGround = true;
        float yaw;
        float pitch;
        boolean crouching;
        String hand = "";
        boolean looking = true;

        Poses add(int ticks) {
            for (int i = 0; i < ticks; i++) {
                list.add(new PlayerPose(tick++, x, y, 0, onGround, yaw, pitch, crouching, hand, looking));
            }
            return this;
        }
    }

    @Test
    void pressAndReleaseIsOneCrouchButStayingDownIsNot() {
        // 蹲下 5 刻站起来算一下；再蹲下 30 刻（超过 1 秒）站起来不算蹲起，那是蹲着。
        Poses poses = new Poses().add(3);
        poses.crouching = true;
        poses.add(5);
        poses.crouching = false;
        poses.add(4);
        poses.crouching = true;
        poses.add(5);
        poses.crouching = false;
        poses.add(4);
        poses.crouching = true;
        poses.add(30);
        poses.crouching = false;
        poses.add(2);

        List<NonverbalAct> acts = NonverbalReading.crouches(poses.list, -1);
        assertEquals(2, acts.size());
        assertTrue(acts.get(0).towardMe(), "看着角色蹲起的算冲着角色");
        assertEquals(1, NonverbalReading.crouches(poses.list, 10).size(), "上一段发到第 10 刻，之前那下不重报");
    }

    @Test
    void nodsAreCountedAndWalkingWhileLookingDownIsNot() {
        Poses poses = new Poses().add(2);
        for (int i = 0; i < 2; i++) {
            poses.pitch = 25;
            poses.add(4);
            poses.pitch = 0;
            poses.add(4);
        }
        poses.add(2);
        List<NonverbalAct> acts = NonverbalReading.headTurns(poses.list, -1);
        assertEquals(1, acts.size());
        assertEquals(NonverbalAct.Kind.NOD, acts.get(0).kind());
        assertEquals(2, acts.get(0).count());

        // 同样的上下看，但人一路往前走：不是点头。
        Poses walking = new Poses().add(2);
        for (int i = 0; i < 2; i++) {
            walking.pitch = 25;
            walking.x += 1;
            walking.add(4);
            walking.pitch = 0;
            walking.x += 1;
            walking.add(4);
        }
        assertTrue(NonverbalReading.headTurns(walking.list, -1).isEmpty());
    }

    @Test
    void shakingHeadNeedsTwoReversalsOfTwentyDegrees() {
        Poses poses = new Poses().add(2);
        for (int i = 0; i < 2; i++) {
            poses.yaw = 25;
            poses.add(4);
            poses.yaw = -25;
            poses.add(4);
        }
        List<NonverbalAct> acts = NonverbalReading.headTurns(poses.list, -1);
        assertEquals(1, acts.size());
        assertEquals(NonverbalAct.Kind.SHAKE_HEAD, acts.get(0).kind());

        // 只转过去一次（回头看一眼）：不是摇头。
        Poses once = new Poses().add(2);
        once.yaw = 40;
        once.add(6);
        assertTrue(NonverbalReading.headTurns(once.list, -1).isEmpty());
    }

    @Test
    void jumpingInPlaceCountsButJumpingWhileRunningDoesNot() {
        Poses poses = new Poses().add(2);
        poses.onGround = false;
        poses.y = 65.1;
        poses.add(4);
        poses.onGround = true;
        poses.y = 64;
        poses.add(2);
        assertEquals(1, NonverbalReading.jumps(poses.list, -1).size());

        Poses running = new Poses().add(2);
        running.onGround = false;
        running.y = 65.1;
        running.x = 3;
        running.add(4);
        running.onGround = true;
        running.y = 64;
        running.add(2);
        assertTrue(NonverbalReading.jumps(running.list, -1).isEmpty(), "边跑边跳挪了 3 格");
    }

    @Test
    void scrollingThroughTheHotbarRecordsOnlyWhatEndsUpInHand() {
        Poses poses = new Poses().add(2);
        poses.hand = "石头";
        poses.add(2);
        poses.hand = "铁剑";
        poses.add(20);
        List<NonverbalAct> acts = NonverbalReading.holds(poses.list, -1);
        assertEquals(1, acts.size());
        assertEquals("铁剑", acts.get(0).detail());
    }

    @Test
    void walkingAwayAndLookingBackIsReportedWithDirection() {
        Poses poses = new Poses().add(5);
        poses.looking = false;
        for (int i = 0; i < 6; i++) {
            poses.x += 1;
            poses.add(5);
        }
        poses.looking = true;
        poses.add(2);
        var act = NonverbalReading.walkedBack(poses.list, 4).orElseThrow();
        assertEquals(NonverbalAct.Kind.WALK, act.kind());
        assertTrue(act.detail().contains("走了 6 格"), act.detail());
        assertTrue(act.detail().contains("东"), "往 x 正方向是东：" + act.detail());
    }

    @Test
    void anItemSpawnedAtHisEyeAndFlyingAlongHisViewIsHisToss() {
        Vec3 eye = new Vec3(0, 65.62, 0);
        Vec3 look = new Vec3(0, 0, 1);
        // 生成后 1 刻：在眼睛下 0.3 格、往前 0.3 格附近，速度朝前。
        assertTrue(NonverbalReading.tossMiss(eye, look, new Vec3(0, 65.3, 0.35), new Vec3(0, 0.05, 0.3), 1).isPresent());
        // 生成在 3 格外：不是他扔的。
        assertFalse(NonverbalReading.tossMiss(eye, look, new Vec3(3, 65.3, 0.3), new Vec3(0, 0, 0.3), 1).isPresent());
        // 方向反着飞：不是他扔的。
        assertFalse(NonverbalReading.tossMiss(eye, look, new Vec3(0, 65.3, 0.2), new Vec3(0, 0, -0.3), 1).isPresent());
    }
}
