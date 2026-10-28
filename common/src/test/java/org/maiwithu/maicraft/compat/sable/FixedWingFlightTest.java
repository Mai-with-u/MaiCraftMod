// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

/** 固定翼的姿态按车头那根轴算：机头抬起为正俯仰，右翼往下为正横滚；车头朝 -x 时同样对。 */
class FixedWingFlightTest {

    private static SableStructures.Pose turnedAbout(Vec3 axis, double degrees) {
        double half = Math.toRadians(degrees) / 2;
        double sin = Math.sin(half);
        return new SableStructures.Pose(new Vec3(100, 70, 100),
                new double[]{axis.x * sin, axis.y * sin, axis.z * sin, Math.cos(half)},
                new Vec3(10000, 64, 10000), new Vec3(1, 1, 1));
    }

    @Test
    void 摆正时俯仰横滚都是零() {
        FixedWingPilot.Attitude level = FixedWingFlight.attitude(turnedAbout(new Vec3(0, 1, 0), 0), "+z");
        assertEquals(0, level.pitch(), 1e-6);
        assertEquals(0, level.roll(), 1e-6);
    }

    @Test
    void 机头抬起是正俯仰_右翼往下是正横滚() {
        // 车头 +z：绕 x 轴转 -10° 机头往上抬。
        assertEquals(10, FixedWingFlight.attitude(turnedAbout(new Vec3(1, 0, 0), -10), "+z").pitch(), 1e-6);
        // 车头 +z：绕 z 轴转 +15° 右手那边（-x）往下。
        assertEquals(15, FixedWingFlight.attitude(turnedAbout(new Vec3(0, 0, 1), 15), "+z").roll(), 1e-6);
        // 车头 -x：绕 z 轴转 +10°，-x 那头往下，是低头。
        assertEquals(-10, FixedWingFlight.attitude(turnedAbout(new Vec3(0, 0, 1), 10), "-x").pitch(), 1e-6);
    }
}
