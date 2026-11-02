// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.looking;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 闲时看的节奏：间隔、放慢、只剩有反应、读完的延迟与过期、各份冷却。 */
class LookPaceTest {

    @Test
    void 两回闲看隔三到七秒_闲了一分钟放慢一倍() {
        Random random = new Random(1);
        for (int i = 0; i < 200; i++) {
            int gap = LookPace.glanceGap(0, random);
            assertTrue(gap >= 60 && gap <= 140, "间隔三到七秒：" + gap);
            int slower = LookPace.glanceGap(20 * 60, random);
            assertTrue(slower >= 120 && slower <= 280, "闲了一分钟放慢一倍：" + slower);
        }
    }

    @Test
    void 闲了五分钟只剩有反应的几种() {
        assertFalse(LookPace.reactiveOnly(20 * 60 * 5 - 1));
        assertTrue(LookPace.reactiveOnly(20 * 60 * 5));
    }

    @Test
    void 读完零点二到零点五秒再动_八秒后作废() {
        Random random = new Random(2);
        for (int i = 0; i < 100; i++) {
            int delay = LookPace.readDelay(random);
            assertTrue(delay >= 4 && delay <= 10, "读完的延迟：" + delay);
        }
        assertFalse(LookPace.expired(100, 260), "正好八秒还算数");
        assertTrue(LookPace.expired(100, 261), "过了八秒作废");
    }

    @Test
    void 背包三十秒_周围十五秒_同一目标二十秒内不重做() {
        LookPace.Cooldown self = LookPace.cooldownOf(WhatWasRead.self());
        assertEquals(600, self.ticks());
        assertEquals(300, LookPace.cooldownOf(WhatWasRead.surroundings()).ticks());
        LookPace.Cooldown chest = LookPace.cooldownOf(WhatWasRead.seen("B5"));
        assertEquals(400, chest.ticks());
        assertEquals(chest.key(), LookPace.cooldownOf(WhatWasRead.seen(" b5 ")).key(), "编号大小写、空白不算两样");
        assertNotEquals(chest.key(), LookPace.cooldownOf(WhatWasRead.seen("b6")).key());
        assertNotEquals(LookPace.cooldownOf(WhatWasRead.recipe("minecraft:torch", false)).key(),
                LookPace.cooldownOf(WhatWasRead.recipe("minecraft:torch", true)).key(), "配方页和用途页各算各的");
        WhatWasRead.Places here = new WhatWasRead.Places(List.of(WorldPosition.here(1, 2, 3)), false);
        assertEquals(LookPace.cooldownOf(here).key(),
                LookPace.cooldownOf(new WhatWasRead.Places(List.of(WorldPosition.here(1, 2, 3)), false)).key());

        assertFalse(LookPace.coolingDown(null, 1000, 600), "没读过不在冷却");
        assertTrue(LookPace.coolingDown(500L, 1099, 600));
        assertFalse(LookPace.coolingDown(500L, 1100, 600));
    }
}
