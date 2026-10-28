// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** 看到的移动：这一刻的位置减上一刻的位置；一刻挪两格以上当传送，不算在动。 */
class ObservedMotionTest {

    private static final double EPS = 1.0e-9;

    @Test
    void movementIsThisTicksPositionMinusLastTicks() {
        Vec3 moved = ObservedMotion.step(new Vec3(10.28, 64, 5), new Vec3(10, 64, 5));
        assertEquals(0.28, moved.x, EPS);
        assertEquals(0, moved.y, EPS);
        assertEquals(0, moved.z, EPS);
        assertEquals(0, ObservedMotion.step(new Vec3(3, 64, 3), new Vec3(3, 64, 3)).length(), EPS, "站着不动");
    }

    @Test
    void aTeleportCountsAsNotMoving() {
        // 末影珍珠一刻挪了 3 格：当传送，这一刻不提前。
        assertEquals(0, ObservedMotion.step(new Vec3(13, 64, 0), new Vec3(10, 64, 0)).length(), EPS);
        // 疾跑跳一刻不到 0.7 格照常算。
        assertEquals(0.65, ObservedMotion.step(new Vec3(0.65, 64, 0), new Vec3(0, 64, 0)).x, EPS);
    }
}
