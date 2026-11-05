// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.looking;

import java.util.Random;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.maiwithu.maicraft.behavior.perception.looking.LookingDoubles.spot;

/**
 * 为拍画面转头：闲着或只能转头时转过去、到位了交回真、停一会儿慢慢转回；手上有活、人按着、骑着、
 * 别人开着界面时不转并说清为什么；闲时看的开关关着也转，转完松手不接着闲看。都对着替身跑。
 */
class IdleLookingPictureTest {

    private final FakeHead head = new FakeHead(0f, 0f);
    private final LookingDoubles.FakeWorld world = new LookingDoubles.FakeWorld();
    private final LookingDoubles.FakeScreens screens = new LookingDoubles.FakeScreens();
    private final LookingDoubles.Clock clock = new LookingDoubles.Clock();
    private final IdleLooking looking = new IdleLooking(context -> head, world, screens, new Random(6), true);

    /** 东边十格一样东西：从原点的眼睛看过去偏航约 -87 度。 */
    private static final float EAST_YAW = Aim.between(0, LookingDoubles.EYE_Y, 0, 10.5, 0.5, 0.5).yaw();

    private void run(IdleLooking target, int ticks, IdleLooking.Availability availability) {
        for (int i = 0; i < ticks; i++) target.tick(clock.next(), availability);
    }

    private PictureLook.Turn ask(IdleLooking target, String id) {
        return target.lookForPicture(clock.next(), new PictureLook.Subject.Seen(id), true, false);
    }

    // 一刻一刻推进，直到转头那边交回结论；交回前镜头每刻都记下来，看它是不是一路转过去的。
    private boolean runUntilArrived(IdleLooking target, CompletableFuture<Boolean> arrived,
            IdleLooking.Availability availability) {
        for (int i = 0; i < 100 && !arrived.isDone(); i++) target.tick(clock.next(), availability);
        assertTrue(arrived.isDone(), "一百刻内交回了结论");
        return arrived.join();
    }

    @Test
    void 闲着时转过去_到位了交回真_停一会儿再转回站定朝向() {
        world.known.put("e1", spot("e1", 10, 0, 0));
        run(looking, 1, IdleLooking.Availability.FREE);
        PictureLook.Turn turn = ask(looking, "e1");
        PictureLook.Turn.Turning turning = assertInstanceOf(PictureLook.Turn.Turning.class, turn);
        assertTrue(runUntilArrived(looking, turning.arrived(), IdleLooking.Availability.FREE), "转到位了");
        assertEquals(EAST_YAW, head.yaw, 2.5f, "镜头对着东边那样东西");
        assertEquals(0f, head.standingYaw(), 0.01f, "身子朝向不变，只转头");
        run(looking, PictureLook.HOLD_TICKS - 2, IdleLooking.Availability.FREE);
        assertEquals(EAST_YAW, head.yaw, 4f, "停在那儿看一会儿，不当刻甩回");
        run(looking, 80, IdleLooking.Availability.FREE);
        assertEquals(0f, head.yaw, 4f, "看完慢慢转回站定朝向：" + head.yaw);
    }

    @Test
    void 转头是一步一步转过去的_不一下甩到位() {
        world.known.put("e1", spot("e1", 10, 0, 0));
        run(looking, 1, IdleLooking.Availability.FREE);
        PictureLook.Turn.Turning turning = (PictureLook.Turn.Turning) ask(looking, "e1");
        runUntilArrived(looking, turning.arrived(), IdleLooking.Availability.FREE);
        assertTrue(head.largestStep < 30f, "一刻最多转几十度以内：" + head.largestStep);
    }

    @Test
    void 只能转头时照样转() {
        world.known.put("e1", spot("e1", 10, 0, 0));
        run(looking, 1, IdleLooking.Availability.HEAD_ONLY);
        PictureLook.Turn turn = ask(looking, "e1");
        PictureLook.Turn.Turning turning = assertInstanceOf(PictureLook.Turn.Turning.class, turn);
        assertTrue(runUntilArrived(looking, turning.arrived(), IdleLooking.Availability.HEAD_ONLY));
        assertEquals(0, screens.inventoryOpens, "不开背包");
    }

    @Test
    void 手上有活时不转() {
        world.known.put("e1", spot("e1", 10, 0, 0));
        run(looking, 1, IdleLooking.Availability.TAKEN);
        PictureLook.Turn turn = ask(looking, "e1");
        assertEquals(new PictureLook.Turn.NotTurning(PictureLook.WhyNot.WORKING), turn);
        run(looking, 40, IdleLooking.Availability.TAKEN);
        assertEquals(0, head.turns, "镜头一动不动");
    }

    @Test
    void 人按着时不转_临时任务占着身体时不转() {
        world.known.put("e1", spot("e1", 10, 0, 0));
        run(looking, 1, IdleLooking.Availability.FREE);
        assertEquals(new PictureLook.Turn.NotTurning(PictureLook.WhyNot.HUMAN_CONTROLS),
                looking.lookForPicture(clock.next(), new PictureLook.Subject.Seen("e1"), false, false));
        assertEquals(new PictureLook.Turn.NotTurning(PictureLook.WhyNot.TEMPORARY_TASK),
                looking.lookForPicture(clock.next(), new PictureLook.Subject.Seen("e1"), true, true));
    }

    @Test
    void 骑着坐着时不转_别人开着界面时不转() {
        world.known.put("e1", spot("e1", 10, 0, 0));
        run(looking, 1, IdleLooking.Availability.FREE);
        world.playerOk = false;
        assertEquals(new PictureLook.Turn.NotTurning(PictureLook.WhyNot.NOT_ON_FOOT), ask(looking, "e1"));
        world.playerOk = true;
        screens.others = true;
        assertEquals(new PictureLook.Turn.NotTurning(PictureLook.WhyNot.OTHERS_SCREEN), ask(looking, "e1"));
    }

    @Test
    void 认不出的编号用不着转() {
        run(looking, 1, IdleLooking.Availability.FREE);
        assertEquals(new PictureLook.Turn.NoNeed(), ask(looking, "e404"));
    }

    @Test
    void 不看哪样东西且镜头本来就在站定朝向时直接拍() {
        run(looking, 1, IdleLooking.Availability.FREE);
        assertEquals(new PictureLook.Turn.NoNeed(),
                looking.lookForPicture(clock.next(), new PictureLook.Subject.Standing(), true, false));
    }

    @Test
    void 转到一半有活来了_交回没到位() {
        world.known.put("e1", spot("e1", 10, 0, 0));
        run(looking, 1, IdleLooking.Availability.FREE);
        PictureLook.Turn.Turning turning = (PictureLook.Turn.Turning) ask(looking, "e1");
        run(looking, 1, IdleLooking.Availability.FREE);
        looking.tick(clock.next(), IdleLooking.Availability.TAKEN);
        assertTrue(turning.arrived().isDone());
        assertFalse(turning.arrived().join(), "收手了，照镜头此刻拍");
    }

    @Test
    void 转不动时转够两秒交回没到位() {
        world.known.put("e1", spot("e1", 10, 0, 0));
        head.follows = false;
        run(looking, 1, IdleLooking.Availability.FREE);
        PictureLook.Turn.Turning turning = (PictureLook.Turn.Turning) ask(looking, "e1");
        assertFalse(runUntilArrived(looking, turning.arrived(), IdleLooking.Availability.FREE));
    }

    @Test
    void 正在读后看时让给拍画面() {
        world.known.put("e1", spot("e1", 10, 0, 0));
        looking.justRead(WhatWasRead.self());
        run(looking, 20, IdleLooking.Availability.FREE);
        assertTrue(screens.ours, "读后看开着背包");
        PictureLook.Turn.Turning turning = (PictureLook.Turn.Turning) ask(looking, "e1");
        assertTrue(runUntilArrived(looking, turning.arrived(), IdleLooking.Availability.FREE));
        assertFalse(screens.ours, "背包先关上再转过去");
        assertEquals(EAST_YAW, head.yaw, 2.5f);
    }

    @Test
    void 闲时看关着也转_转完松手不接着闲看() {
        IdleLooking off = new IdleLooking(context -> head, world, screens, new Random(6), false);
        world.known.put("e1", spot("e1", 10, 0, 0));
        run(off, 1, IdleLooking.Availability.FREE);
        PictureLook.Turn.Turning turning = (PictureLook.Turn.Turning) ask(off, "e1");
        assertTrue(runUntilArrived(off, turning.arrived(), IdleLooking.Availability.FREE));
        run(off, 120, IdleLooking.Availability.FREE);
        assertEquals(0f, head.yaw, 4f, "转回来了");
        int turns = head.turns;
        assertTrue(head.letGos > 0, "转完松手");
        run(off, 600, IdleLooking.Availability.FREE);
        assertEquals(turns, head.turns, "之后不闲看");
    }
}
