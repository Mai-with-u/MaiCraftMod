// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.bodylanguage;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 一段肢体动作什么时候发：1.5 秒没新动作、满 6 秒、走开又回头；东西在飞、人在走开时再等等。 */
class NonverbalWatchTest {

    @Test
    void segmentIsDueAfterQuietOrWindowButWaitsForFlyingItemsAndWalkers() {
        assertFalse(NonverbalWatch.due(100, 120, 140, false, false), "最后一下后 1 秒，还在等");
        assertTrue(NonverbalWatch.due(100, 120, 150, false, false), "1.5 秒没新动作就发");
        assertFalse(NonverbalWatch.due(100, 120, 150, false, true), "东西还在飞、人在走开，再等等");
        assertTrue(NonverbalWatch.due(100, 215, 220, false, false), "一直在做，满 6 秒发一条");
        assertTrue(NonverbalWatch.due(100, 130, 131, true, true), "走开又回头了，当刻发");
    }

    @Test
    void tossesNearbyAreKeptForOneMinuteWithTheirLandingTick() {
        Map<Integer, Long> tossed = new HashMap<>(Map.of(7, 100L, 8, 600L));
        // 落地后整一分钟还记得：叫她捡时认得出刚才扔过来的就是它。
        NonverbalWatch.forgetOldTosses(tossed, 1300);
        assertEquals(Map.of(7, 100L, 8, 600L), NonverbalWatch.tossedWithin(tossed, 1300, 1200));
        // 过了一分钟就忘：实体 7 不再算扔过来的。
        NonverbalWatch.forgetOldTosses(tossed, 1301);
        assertEquals(Map.of(8, 600L), tossed);
        // 只问最近 30 秒的：落地早的不给。
        assertEquals(Map.of(), NonverbalWatch.tossedWithin(tossed, 1301, 600));
    }
}
