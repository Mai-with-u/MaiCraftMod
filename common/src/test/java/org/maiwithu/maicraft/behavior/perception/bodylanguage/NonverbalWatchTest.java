// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.bodylanguage;

import org.junit.jupiter.api.Test;

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
}
