// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.bodylanguage;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 节奏与上限：蹲起按下松开各 3 刻、摇头先左后右再回正、每种动作最多几下都在 15 秒内、同一个人 10 秒最多拍 2 下。 */
class NonverbalRhythmTest {

    @Test
    void crouchHoldsThreeTicksThenReleasesThree() {
        assertTrue(NonverbalRhythm.crouching(0));
        assertTrue(NonverbalRhythm.crouching(2));
        assertFalse(NonverbalRhythm.crouching(3));
        assertFalse(NonverbalRhythm.crouching(5));
        assertTrue(NonverbalRhythm.crouching(6));
    }

    @Test
    void shakeGoesLeftRightThenCenters() {
        assertEquals(-NonverbalRhythm.TURN_DEGREES, NonverbalRhythm.shakeYaw(0, 1));
        assertEquals(NonverbalRhythm.TURN_DEGREES, NonverbalRhythm.shakeYaw(4, 1));
        assertEquals(0, NonverbalRhythm.shakeYaw(8, 1));
    }

    // 每种动作做到上限（看着最多 10 秒）也不超过 15 秒：上限本身就把一次卡在 15 秒内。
    @Test
    void everyMaximumFitsInFifteenSeconds() {
        for (NonverbalOperation op : NonverbalOperation.values()) {
            int ticks = NonverbalRhythm.plannedTicks(op, op.maxCount, 10 * 20);
            assertTrue(ticks <= NonverbalRhythm.MAX_TICKS, op + " 做满要 " + ticks + " 刻");
        }
    }

    @Test
    void operationsParseFromTheirParamNames() {
        for (NonverbalOperation op : NonverbalOperation.values()) {
            assertEquals(op, NonverbalOperation.of(op.param).orElseThrow());
        }
        assertTrue(NonverbalOperation.of("wave").isEmpty());
    }

    @Test
    void sameTargetGetsAtMostTwoPokesInTenSeconds() {
        PokesGiven pokes = new PokesGiven();
        UUID alex = UUID.randomUUID();
        pokes.gave(alex, 0);
        pokes.gave(alex, 50);
        assertFalse(pokes.allowed(alex, 100));
        assertTrue(pokes.allowed(UUID.randomUUID(), 100));
        assertTrue(pokes.allowed(alex, PokesGiven.WINDOW_TICKS));
    }
}
