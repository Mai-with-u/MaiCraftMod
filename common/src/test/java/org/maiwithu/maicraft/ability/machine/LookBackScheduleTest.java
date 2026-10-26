// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 回头看产物的账：一个周期估多久、隔多久看、新出几件怎么数、几回没出收手。 */
class LookBackScheduleTest {

    @Test
    void 加工周期按配方里写的时长_读不到按十秒() {
        JsonObject milling = new JsonObject();
        milling.addProperty("processing_time", 250);
        assertEquals(250, LookBackSchedule.cycleTicks(milling));
        JsonObject smelting = new JsonObject();
        smelting.addProperty("cookingtime", 100);
        assertEquals(100, LookBackSchedule.cycleTicks(smelting));
        assertEquals(LookBackSchedule.DEFAULT_CYCLE_TICKS, LookBackSchedule.cycleTicks(new JsonObject()));
        assertEquals(LookBackSchedule.DEFAULT_CYCLE_TICKS, LookBackSchedule.cycleTicks(null));
    }

    @Test
    void 隔多久看夹在两秒到一分钟之间() {
        assertEquals(LookBackSchedule.MIN_WAIT_TICKS, new LookBackSchedule(5, 0).firstWait());
        assertEquals(LookBackSchedule.MAX_WAIT_TICKS, new LookBackSchedule(20 * 600, 0).firstWait());
        assertEquals(200, new LookBackSchedule(200, 0).firstWait());
    }

    @Test
    void 新出的件数按上一眼收完剩下的算_开工前的旧货不算() {
        LookBackSchedule schedule = new LookBackSchedule(200, 3);
        // 开工前出口里有 3 件；第一眼看到 5 件、全收走：新出 2 件，收了 5 件。
        assertEquals(2, schedule.record(5, 5));
        // 第二眼看到 1 件、不收：新出 1 件，出口里留着 1 件。
        assertEquals(1, schedule.record(1, 0));
        // 第三眼看到 4 件：比留着的多出 3 件。
        assertEquals(3, schedule.record(4, 4));
        assertEquals(6, schedule.produced());
        assertEquals(9, schedule.collected());
        assertEquals(0, schedule.leftInExit());
        assertEquals(3, schedule.looks());
    }

    @Test
    void 连着三回没出才收手_中间出过就重新数() {
        LookBackSchedule schedule = new LookBackSchedule(200, 0);
        schedule.record(0, 0);
        schedule.record(0, 0);
        assertFalse(schedule.gaveUp());
        schedule.record(1, 1);
        schedule.record(0, 0);
        schedule.record(0, 0);
        assertFalse(schedule.gaveUp(), "出过一件，没出的回数重新数");
        schedule.record(0, 0);
        assertTrue(schedule.gaveUp());
    }

    @Test
    void 出过以后按出货快慢估剩下的_一件没出就再等一个周期() {
        LookBackSchedule schedule = new LookBackSchedule(200, 0);
        schedule.record(0, 0);
        assertEquals(200, schedule.nextWait(200, 8));
        // 等了 400 刻出了 2 件：每件约 200 刻，还差 6 件要 1200 刻，正好一分钟的上限。
        schedule.record(2, 2);
        assertEquals(1200, schedule.nextWait(400, 8));
        // 出得快：70 刻出了 7 件，每件 10 刻，还差 1 件只要 10 刻。
        LookBackSchedule nearly = new LookBackSchedule(200, 0);
        nearly.record(7, 7);
        assertEquals(LookBackSchedule.MIN_WAIT_TICKS, nearly.nextWait(70, 8), "估出来不到两秒也至少等两秒");
    }
}
