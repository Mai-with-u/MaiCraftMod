// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.looking;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 环顾：两边各看一眼再回正、带随机不机械、转头期间站定朝向不变、不算进展、卡住的镜头不让它卡死。 */
class LookAroundTest {

    private long tick;

    private TickContext tick() {
        long now = ++tick;
        return new TickContext() {
            @Override public long gameTick() { return now; }
            @Override public PlayerContext player() { return null; }
        };
    }

    @Test
    void 一圈先到一边略低头_再到另一边略抬头_最后回正() {
        Random random = new Random(42);
        for (int round = 0; round < 50; round++) {
            List<LookAround.Waypoint> plan = LookAround.plan(0f, 0f, random);
            assertEquals(3, plan.size());
            float first = plan.get(0).yaw();
            float second = plan.get(1).yaw();
            assertTrue(Math.abs(first) >= 70f && Math.abs(first) <= 95f, "第一边转 70–95 度：" + first);
            assertTrue(Math.abs(second) >= 70f && Math.abs(second) <= 95f, "第二边转 70–95 度：" + second);
            assertTrue(Math.signum(first) != Math.signum(second), "两边在站定朝向两侧");
            assertEquals(10f, plan.get(0).pitch(), 0.01f, "第一边略低头");
            assertEquals(-4f, plan.get(1).pitch(), 0.01f, "第二边略抬头");
            assertEquals(0f, plan.get(2).yaw(), 0.01f, "最后回到站定朝向");
            assertEquals(0, plan.get(2).holdTicks());
        }
    }

    @Test
    void 先往哪边转带随机() {
        // 同一个随机源连着排五十圈（实际也是一个角色一份随机源）：两边都有先转的，转多大也不是每圈一样。
        Random random = new Random(42);
        boolean left = false;
        boolean right = false;
        float previous = Float.NaN;
        boolean varies = false;
        for (int round = 0; round < 50; round++) {
            float first = LookAround.plan(0f, 0f, random).get(0).yaw();
            if (first > 0) right = true;
            else left = true;
            if (!Float.isNaN(previous) && Math.abs(Math.abs(first) - Math.abs(previous)) > 0.5f) varies = true;
            previous = first;
        }
        assertTrue(left && right, "五十圈里两边都有先转的");
        assertTrue(varies, "转多大也不是每圈一样");
    }

    @Test
    void 扫一圈约两秒做完_回到站定朝向_站定朝向全程保住() {
        FakeHead head = new FakeHead(30f, 0f);
        LookAround look = new LookAround(context -> head, new Random(7), false);
        int ticks = 0;
        ActionStatus status;
        do {
            status = look.tick(tick());
            ticks++;
            assertTrue(ticks < 200, "一圈扫不完");
            if (!(status instanceof ActionStatus.Done)) {
                assertEquals(30f, head.standingYaw(), 0.01f, "转头期间角色朝哪边不变");
                assertFalse(((ActionStatus.Running) status).progressed(), "转头不算真实进展");
            }
        } while (!(status instanceof ActionStatus.Done));
        assertTrue(ticks >= 30 && ticks <= 80, "一圈约两秒：" + ticks + " 刻");
        assertEquals(30f, head.yaw, 0.01f, "扫完回到原来的朝向");
        assertEquals(1, head.letGos, "扫完放开站定朝向");
        assertTrue(head.largestStep <= LookAround.SWEEP_DEGREES_PER_SECOND / 20f + 0.01f, "每刻转得不比定好的快");
        assertInstanceOf(ActionStatus.Done.class, look.tick(tick()), "做完了再推也是做完");
    }

    @Test
    void 一直等着的扫完一圈停一会儿再扫_不自己做完() {
        FakeHead head = new FakeHead(0f, 0f);
        LookAround look = new LookAround(context -> head, new Random(3), true);
        int restTicks = 0;
        int longestRest = 0;
        int run = 0;
        for (int i = 0; i < 400; i++) {
            int turnsBefore = head.turns;
            assertInstanceOf(ActionStatus.Running.class, look.tick(tick()), "一直等着的不自己做完");
            if (head.turns == turnsBefore) {
                run++;
                restTicks++;
                longestRest = Math.max(longestRest, run);
            } else {
                run = 0;
            }
        }
        // 回正那一下停零刻，接着进入两圈之间的歇息：歇息里镜头一动不动。
        assertTrue(longestRest >= LookAround.REST_MIN_TICKS && longestRest <= LookAround.REST_MAX_TICKS + 1,
                "两圈之间停两到四秒：" + longestRest);
        assertTrue(restTicks < 400, "停够了接着扫下一圈");
        assertEquals(0f, head.standingYaw(), 0.01f, "停着时站定朝向也不放");
        assertEquals(0, head.letGos);
    }

    @Test
    void 镜头不跟也不卡死_一个方向最多转两秒() {
        // 人按 F8 拿回了角色：转头请求不生效，一圈照样按时间走完，不在半路干等。
        FakeHead head = new FakeHead(0f, 0f);
        head.follows = false;
        LookAround look = new LookAround(context -> head, new Random(5), false);
        int ticks = 0;
        while (!(look.tick(tick()) instanceof ActionStatus.Done)) {
            ticks++;
            assertTrue(ticks < 3 * (LookAround.WAYPOINT_LIMIT_TICKS + LookAround.EDGE_HOLD_MAX_TICKS + 2),
                    "镜头不动时卡在半路");
        }
    }

    @Test
    void 不在世界里时只等着不动() {
        LookAround look = new LookAround(context -> null, new Random(1), false);
        for (int i = 0; i < 10; i++) {
            assertInstanceOf(ActionStatus.Running.class, look.tick(tick()));
        }
    }

    @Test
    void 被打断时放开站定朝向_回来从头扫一圈() {
        FakeHead head = new FakeHead(0f, 0f);
        LookAround look = new LookAround(context -> head, new Random(9), true);
        for (int i = 0; i < 5; i++) look.tick(tick());
        assertEquals(1, head.holds);
        look.pause();
        assertEquals(1, head.letGos, "暂停时放开站定朝向");
        // 临时任务把镜头转到了别处：回来按新的朝向重新站定，从头扫一圈。
        head.yaw = 120f;
        look.tick(tick());
        assertEquals(2, head.holds);
        assertEquals(120f, head.standingYaw(), 0.01f);
        look.close();
        assertEquals(2, head.letGos, "收尾时放开站定朝向");
    }

    @Test
    void 刚低头挖完脚下的先抬起来再看() {
        // 起扫的俯仰压在平视附近：低头 70 度时，第一边按平视上限再略低头，开扫第一刻就往上抬。
        FakeHead head = new FakeHead(0f, 70f);
        LookAround look = new LookAround(context -> head, new Random(2), false);
        look.tick(tick());
        List<LookAround.Waypoint> plan = LookAround.plan(0f, LookAround.BASE_PITCH_MAX, new Random(2));
        assertEquals(LookAround.BASE_PITCH_MAX + LookAround.LOOK_DOWN_DEGREES, plan.get(0).pitch(), 0.01f);
        assertTrue(head.pitch < 70f, "开扫第一刻就往平视那边抬");
    }

    @Test
    void 随时可以打断() {
        LookAround look = LookAround.once(new Random(1));
        assertEquals(Interruptibility.BETWEEN_ACTIONS, look.interruptibility());
    }
}
