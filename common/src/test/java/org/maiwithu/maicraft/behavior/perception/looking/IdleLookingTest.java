// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.looking;

import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.maiwithu.maicraft.behavior.perception.looking.LookingDoubles.container;
import static org.maiwithu.maicraft.behavior.perception.looking.LookingDoubles.openable;
import static org.maiwithu.maicraft.behavior.perception.looking.LookingDoubles.player;

/**
 * 闲时看：只在闲着时动；有事要做当刻收手并关上自己开的界面；读后看按路径开背包、开箱、翻配方页；
 * 主目标等回答或暂停时只转头；冷却、过期、开关都对着替身跑。
 */
class IdleLookingTest {

    private final FakeHead head = new FakeHead(0f, 0f);
    private final LookingDoubles.FakeWorld world = new LookingDoubles.FakeWorld();
    private final LookingDoubles.FakeScreens screens = new LookingDoubles.FakeScreens();
    private final LookingDoubles.Clock clock = new LookingDoubles.Clock();
    private final IdleLooking looking = new IdleLooking(context -> head, world, screens, new Random(6), true);

    private void run(int ticks, IdleLooking.Availability availability) {
        for (int i = 0; i < ticks; i++) looking.tick(clock.next(), availability);
    }

    @Test
    void 有任务用着角色时什么都不做() {
        looking.justRead(WhatWasRead.self());
        run(200, IdleLooking.Availability.TAKEN);
        assertEquals(0, head.turns);
        assertEquals(0, screens.inventoryOpens);
    }

    @Test
    void 刚闲下来先站定一秒_之后隔一会儿看看身边() {
        run(LookPace.SETTLE_TICKS, IdleLooking.Availability.FREE);
        assertEquals(0, head.turns, "第一秒站着不动");
        run(400, IdleLooking.Availability.FREE);
        assertTrue(head.turns > 0, "之后看了看");
        assertTrue(head.holds > 0, "看东西期间按住站定朝向");
        assertEquals(0f, head.standingYaw(), 0.01f, "角色朝哪边不变");
    }

    @Test
    void 刚挖完脚下低着头_第一下先抬回平视() {
        head.pitch = 70f;
        run(LookPace.SETTLE_TICKS + 30, IdleLooking.Availability.FREE);
        assertTrue(head.pitch < 15f, "抬回平视附近：" + head.pitch);
    }

    @Test
    void 读了自己就打开背包_三十秒内再读不重开() {
        looking.justRead(WhatWasRead.self());
        run(200, IdleLooking.Availability.FREE);
        assertEquals(1, screens.inventoryOpens, "背包开了一次");
        assertFalse(screens.ours, "看完关上");
        looking.justRead(WhatWasRead.self());
        run(200, IdleLooking.Availability.FREE);
        assertEquals(1, screens.inventoryOpens, "三十秒内再读不重开");
        run(600, IdleLooking.Availability.FREE);
        looking.justRead(WhatWasRead.self());
        run(200, IdleLooking.Availability.FREE);
        assertEquals(2, screens.inventoryOpens, "过了三十秒再读照开");
    }

    @Test
    void 新目标一来当刻收手_自己开的背包关上_镜头不往回甩() {
        looking.justRead(WhatWasRead.self());
        run(20, IdleLooking.Availability.FREE);
        assertTrue(screens.ours, "背包开着");
        int turns = head.turns;
        looking.tick(clock.next(), IdleLooking.Availability.TAKEN);
        assertFalse(screens.ours, "收手时关上");
        assertEquals(1, head.letGos, "松开转头");
        run(50, IdleLooking.Availability.TAKEN);
        assertEquals(turns, head.turns, "收手后不再转头");
    }

    @Test
    void 读了手边的箱子就打开看一眼() {
        world.known.put("b3", container("b3", 1, 0, 2, openable(1, 0, 2)));
        looking.justRead(WhatWasRead.seen("b3"));
        run(200, IdleLooking.Availability.FREE);
        assertEquals(List.of("container minecraft:chest"), screens.opened);
        assertFalse(screens.ours);
    }

    @Test
    void 主目标等回答时只转头_不开背包不开箱不翻配方页() {
        world.known.put("b3", container("b3", 1, 0, 2, openable(1, 0, 2)));
        run(30, IdleLooking.Availability.HEAD_ONLY);
        looking.justRead(WhatWasRead.self());
        run(100, IdleLooking.Availability.HEAD_ONLY);
        looking.justRead(WhatWasRead.seen("b3"));
        run(100, IdleLooking.Availability.HEAD_ONLY);
        looking.justRead(WhatWasRead.recipe("minecraft:torch", false));
        run(100, IdleLooking.Availability.HEAD_ONLY);
        assertTrue(screens.opened.isEmpty(), "一个界面都没开：" + screens.opened);
        assertTrue(head.turns > 0, "照样转头看看");
    }

    @Test
    void 主目标挂起来时开着的背包先关上() {
        looking.justRead(WhatWasRead.self());
        run(20, IdleLooking.Availability.FREE);
        assertTrue(screens.ours);
        looking.tick(clock.next(), IdleLooking.Availability.HEAD_ONLY);
        assertFalse(screens.ours, "只能转头了，界面关上");
    }

    @Test
    void 别人开着界面_角色在骑乘时都不做() {
        screens.others = true;
        run(200, IdleLooking.Availability.FREE);
        assertEquals(0, head.turns, "别人开着界面时不动");
        screens.others = false;
        world.playerOk = false;
        run(200, IdleLooking.Availability.FREE);
        assertEquals(0, head.turns, "骑着、飞着、背包没到时不动");
    }

    @Test
    void 读完八秒还没闲下来就作废() {
        looking.justRead(WhatWasRead.self());
        run(200, IdleLooking.Availability.TAKEN);
        run(200, IdleLooking.Availability.FREE);
        assertEquals(0, screens.inventoryOpens, "那件事已经过去了");
    }

    @Test
    void 连着查配方_配方页直接翻过去不关() {
        looking.justRead(WhatWasRead.recipe("minecraft:torch", false));
        run(50, IdleLooking.Availability.FREE);
        looking.justRead(WhatWasRead.recipe("minecraft:lantern", false));
        run(20, IdleLooking.Availability.FREE);
        assertEquals(List.of("recipe minecraft:torch", "recipe minecraft:lantern"), screens.opened);
        assertEquals(0, screens.closes, "翻页时没有关界面");
        run(200, IdleLooking.Availability.FREE);
        assertFalse(screens.ours, "看完关上");
    }

    @Test
    void 读了结果指着的地方就看过去() {
        // 找到的东西在角色正西（右手边）：看过去。
        looking.justRead(new WhatWasRead.Places(List.of(WorldPosition.here(-10, 0, 0)), false));
        float most = 0f;
        for (int i = 0; i < 60; i++) {
            looking.tick(clock.next(), IdleLooking.Availability.FREE);
            most = Math.max(most, head.yaw);
        }
        assertTrue(most > 80f, "朝西转过去了：" + most);
    }

    @Test
    void 有人说话就转过去看他() {
        UUID friend = new UUID(0, 7);
        world.around.add(player("e5", friend, 120f, 10, false));
        world.things.put("e5", new Aim(120f, 0f));
        run(10, IdleLooking.Availability.FREE);
        looking.someoneSpoke(friend);
        float most = 0f;
        for (int i = 0; i < 200; i++) {
            looking.tick(clock.next(), IdleLooking.Availability.FREE);
            most = Math.max(most, head.yaw);
        }
        assertTrue(most > 110f, "转过去看着说话的人：" + most);
    }

    @Test
    void 关掉开关时什么都不做() {
        IdleLooking off = new IdleLooking(context -> head, world, screens, new Random(6), false);
        off.justRead(WhatWasRead.self());
        for (int i = 0; i < 400; i++) off.tick(clock.next(), IdleLooking.Availability.FREE);
        assertEquals(0, head.turns);
        assertEquals(0, screens.inventoryOpens);
    }
}
