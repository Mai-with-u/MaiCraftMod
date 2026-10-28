// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** 近战出手前镜头到没到位：视线离瞄点 7° 以内，或准星已经先碰到它，都算到位。 */
class StrikeAimSettledTest {

    /** 水平面上偏开 degrees 度的方向。 */
    private static Vec3 turned(double degrees) {
        double radians = Math.toRadians(degrees);
        return new Vec3(Math.sin(radians), 0, Math.cos(radians));
    }

    @Test
    void crosshairOnTheTargetCountsEvenFarFromTheLeadPoint() {
        // 视线离提前后的瞄点 12°（3 格外疾跑横移的人），准星已经落在他身上：到位，出手。
        assertTrue(Interaction.strikeAimSettled(true, turned(0), turned(12).scale(3)));
    }

    @Test
    void farFromTheLeadPointAndOffTheTargetWaits() {
        // 同样 12°，准星没在他身上：不出手，照旧等镜头转到。
        assertFalse(Interaction.strikeAimSettled(false, turned(0), turned(12).scale(3)));
    }

    @Test
    void withinSevenDegreesIsSettled() {
        assertTrue(Interaction.strikeAimSettled(false, turned(0), turned(6).scale(3)));
        assertFalse(Interaction.strikeAimSettled(false, turned(0), turned(8).scale(3)));
    }
}
