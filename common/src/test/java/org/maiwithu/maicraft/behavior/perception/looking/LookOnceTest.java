// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.looking;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.ActionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 一回看：转过去 → 停 → 开界面 → 关 → 转回来；被收手时界面关上、镜头不往回甩；不见了的跳过。 */
class LookOnceTest {

    private final FakeHead head = new FakeHead(0f, 0f);
    private final LookingDoubles.FakeWorld world = new LookingDoubles.FakeWorld();
    private final LookingDoubles.FakeScreens screens = new LookingDoubles.FakeScreens();
    private final LookingDoubles.Clock clock = new LookingDoubles.Clock();

    private LookOnce look(LookPlans.Step... steps) {
        head.hold();
        return new LookOnce(new LookPlans.Plan(List.of(steps), 150f, false), context -> head, world, screens,
                new Random(4), 0f);
    }

    private int runToEnd(LookOnce look) {
        for (int i = 1; i <= 400; i++) {
            ActionStatus status = look.tick(clock.next());
            if (status instanceof ActionStatus.Done) return i;
            assertFalse(((ActionStatus.Running) status).progressed(), "转头不算真实进展");
        }
        throw new AssertionError("一回看四百刻都没看完");
    }

    @Test
    void 转过去停一会儿再转回站定朝向() {
        LookOnce look = look(new LookPlans.Step(new LookTarget.Direction(90f, 0f), 20, new LookPlans.Screen.None()));
        float farthest = 0f;
        for (int i = 0; i < 400; i++) {
            if (look.tick(clock.next()) instanceof ActionStatus.Done) break;
            farthest = Math.max(farthest, head.yaw);
        }
        assertTrue(farthest > 85f, "转到了九十度附近：" + farthest);
        assertEquals(0f, head.yaw, 2.6f, "看完转回站定朝向");
        assertTrue(head.largestStep <= 150f / 20f + 0.01f, "读后看每秒一百五十度上下");
    }

    @Test
    void 开背包看完关上再转回来() {
        LookOnce look = look(new LookPlans.Step(new LookTarget.Standing(), 40, new LookPlans.Screen.Inventory()));
        int ticks = runToEnd(look);
        assertEquals(1, screens.inventoryOpens);
        assertFalse(screens.ours, "看完关上");
        assertTrue(screens.closes >= 1);
        assertTrue(ticks >= 40, "背包开够了时间：" + ticks);
    }

    @Test
    void 手边的箱子点开看一眼再关上_没开成就到此为止() {
        LookPlans.Screen.Container chest = new LookPlans.Screen.Container(WorldPosition.here(1, 0, 1), "minecraft:chest");
        runToEnd(look(new LookPlans.Step(new LookTarget.Spot(1.5, 0.5, 1.5, false), 30, chest)));
        assertEquals(List.of("container minecraft:chest"), screens.opened);
        assertFalse(screens.ours);
        assertEquals(1, screens.peeksClosed, "开箱动作用完收尾");

        screens.opened.clear();
        screens.peekOpens = false;
        runToEnd(look(new LookPlans.Step(new LookTarget.Spot(1.5, 0.5, 1.5, false), 30, chest)));
        assertEquals(2, screens.peeks, "没开成不点第二次");
        assertTrue(screens.opened.isEmpty());
    }

    @Test
    void 看着会动的东西跟着转_不见了就跳过() {
        world.things.put("e1", new Aim(40f, 0f));
        LookOnce look = look(new LookPlans.Step(new LookTarget.Thing("e1"), 60, new LookPlans.Screen.None()),
                new LookPlans.Step(new LookTarget.Thing("e9"), 30, new LookPlans.Screen.None()));
        for (int i = 0; i < 25; i++) look.tick(clock.next());
        world.things.put("e1", new Aim(70f, 0f));
        for (int i = 0; i < 20; i++) look.tick(clock.next());
        assertTrue(head.yaw > 60f, "它走到七十度，视线跟过去：" + head.yaw);
        runToEnd(look);
        assertEquals(0f, head.yaw, 2.6f, "e9 不在场：跳过，转回来");
    }

    @Test
    void 远处只朝方位看_视线压在平视附近() {
        // 一百格外、高八十格的山顶：俯仰按方位压在二十度以内，不仰头看天。
        LookOnce look = look(new LookPlans.Step(new LookTarget.Spot(0, 300, 100, true), 30, new LookPlans.Screen.None()));
        float highest = 0f;
        for (int i = 0; i < 60; i++) {
            look.tick(clock.next());
            highest = Math.min(highest, head.pitch);
        }
        assertTrue(highest >= -20f - 3f - 1.6f, "仰头不超过二十度（带一点偏开与漂移）：" + highest);
    }

    @Test
    void 被收手时界面关上_镜头不往回甩() {
        LookOnce look = look(new LookPlans.Step(new LookTarget.Direction(90f, 0f), 200, new LookPlans.Screen.None()),
                new LookPlans.Step(new LookTarget.Standing(), 60, new LookPlans.Screen.Inventory()));
        for (int i = 0; i < 30; i++) look.tick(clock.next());
        float where = head.yaw;
        int turns = head.turns;
        look.close();
        assertEquals(turns, head.turns, "收手后不再转头");
        assertEquals(where, head.yaw, 0.01f, "镜头停在哪就是哪");

        // 镜头还停在九十度：先转回站定朝向再开背包。
        LookOnce inventory = look(new LookPlans.Step(new LookTarget.Standing(), 60, new LookPlans.Screen.Inventory()));
        for (int i = 0; i < 25; i++) inventory.tick(clock.next());
        assertTrue(screens.ours);
        inventory.close();
        assertFalse(screens.ours, "开着的背包关上");
    }

    @Test
    void 看够最短时间才让给新请求() {
        LookOnce look = look(new LookPlans.Step(new LookTarget.Direction(30f, 0f), 100, new LookPlans.Screen.None()));
        assertFalse(look.heldEnough(), "还没转到位");
        int ticks = 0;
        while (!look.heldEnough()) {
            look.tick(clock.next());
            assertTrue(++ticks < 60, "看不够");
        }
        assertTrue(ticks >= LookPace.MIN_HOLD_TICKS, "转到位后至少停零点八秒：" + ticks);
    }

    @Test
    void 连着查配方时配方页交给下一回不关() {
        LookOnce look = look(new LookPlans.Step(new LookTarget.Standing(), 60,
                new LookPlans.Screen.Recipe("minecraft:torch", false)));
        for (int i = 0; i < 10; i++) look.tick(clock.next());
        assertTrue(look.showingRecipePage());
        look.handOverScreen();
        look.close();
        assertTrue(screens.ours, "配方页留着，下一回直接翻过去");
    }
}
