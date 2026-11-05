// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 看着：视线离身体不到 1 格就算；背对着、偏得太开、挨得近却看着别处都不算。 */
class LooksAtTest {

    /** 站在 (0, 64, 5) 的一个玩家大小的身体。 */
    private static final AABB BODY = new AABB(-0.3, 64, 4.7, 0.3, 65.8, 5.3);
    private static final Vec3 EYE = new Vec3(0, 65.62, 0);

    @Test
    void lookingStraightAtTheBodyCounts() {
        assertTrue(LooksAt.ray(EYE, new Vec3(0, 0, 1), BODY));
    }

    @Test
    void lookingALittleOffStillCountsButTurningAwayDoesNot() {
        // 偏开约 10 度，5 格外差不到 1 格：算看着。
        assertTrue(LooksAt.ray(EYE, new Vec3(0.18, 0, 1), BODY));
        // 偏开 45 度：差了 3 格多，不算。
        assertFalse(LooksAt.ray(EYE, new Vec3(1, 0, 1), BODY));
        // 背对着：不算。
        assertFalse(LooksAt.ray(EYE, new Vec3(0, 0, -1), BODY));
    }

    @Test
    void standingRightNextToItDoesNotCountUnlessFacingIt() {
        Vec3 close = new Vec3(0, 65.62, 4.0);
        assertFalse(LooksAt.ray(close, new Vec3(1, 0, 0), BODY), "挨着站、看着旁边");
        assertTrue(LooksAt.ray(close, new Vec3(0, -0.3, 1), BODY), "挨着站、看着他");
    }
}
