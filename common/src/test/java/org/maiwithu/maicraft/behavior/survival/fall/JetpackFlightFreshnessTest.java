// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.fall;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 喷气背包飞的那一段还在不在管上下的判断：刚说过算在管，没说过或说得太久都不算。 */
class JetpackFlightFreshnessTest {

    @Test
    void 刚说过管着上下就算在管() {
        assertTrue(JetpackFlightFreshness.stillControlling(1_000L, 1_000L + JetpackFlightFreshness.CONTROL_FRESH_NANOS - 1));
    }

    @Test
    void 说得太久就当不在管了() {
        assertFalse(JetpackFlightFreshness.stillControlling(1_000L, 1_000L + JetpackFlightFreshness.CONTROL_FRESH_NANOS));
    }

    @Test
    void 从没说过就当不在管() {
        assertFalse(JetpackFlightFreshness.stillControlling(0L, 1_000L));
    }
}
