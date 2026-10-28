// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

/**
 * 固定翼的姿态按车头那根轴算：机头抬起为正俯仰，右翼往下为正横滚；车头朝 -x 时同样对。
 * 就近停下时附近连地都没有（海上）：回原来挑的那条，没有就往前方迫降，不在天上一直绕。
 */
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

    /** 一片海：哪里都是水面 y=64。 */
    private static final LandingSites.Ground SEA = new LandingSites.Ground() {
        @Override public Integer top(int x, int z) { return 64; }

        @Override public boolean liquid(int x, int z) { return true; }
    };

    /** 海里一座岛：x 在 ±150、z 在 850～1150 是平地，别处是水。 */
    private static final LandingSites.Ground ISLAND = new LandingSites.Ground() {
        @Override public Integer top(int x, int z) { return 64; }

        @Override public boolean liquid(int x, int z) { return Math.abs(x) > 150 || z < 850 || z > 1150; }
    };

    // 一架在天上朝南飞的固定翼：机身沿 +z 长 11 格、翼展 5 格。
    private static FixedWingFlight inAir(PilotSample start, Vec3 destination) {
        SableStructures.Pose pose = turnedAbout(new Vec3(0, 1, 0), 0);
        SableStructures.Structure ship = new SableStructures.Structure("plane", "", new AABB(0, 0, 0, 5, 2, 11), pose, pose,
                new BlockPos(0, 0, 0), new BlockPos(4, 1, 10), true, new BlockPos(0, 0, 0));
        return new FixedWingFlight(Map.of("power", 87, "yaw_left", 65, "yaw_right", 68),
                Map.of("power", "打字机:W", "yaw_left", "打字机:A", "yaw_right", "打字机:D"), ship, "+z", start,
                destination, 120, false);
    }

    private static PilotSample flying(long tick, Vec3 at) {
        return new PilotSample(tick, at, new Vec3(0, 0, 12), 0, 0, 0, 0, VehicleAttitude.AIRBORNE);
    }

    @Test
    void 海上就近停下_没有地也没有跑道可回就往前方迫降_不一直绕() {
        Vec3 at = new Vec3(0, 100, 0);
        FixedWingFlight flight = inAir(flying(0, at), new Vec3(0, 64, 5000));
        flight.stop(flying(0, at), SEA);
        for (long tick = 1; tick < 400 && !flight.landing().startsWith("往前方迫降"); tick++) {
            flight.fly(SEA, new FixedWingPilot.Attitude(0, 0), flying(tick, at), tick);
        }
        assertTrue(flight.landing().startsWith("往前方迫降"), flight.landing() + " " + flight.notes());
        assertTrue(flight.notes().stream().anyMatch(note -> note.contains("往前方迫降")), flight.notes().toString());
    }

    @Test
    void 就近停下附近都是海_回原来挑的那条跑道() {
        // 先在岛上给目的地挑好一条跑道，再飞到远处海上被叫停：附近找不到地，回岛上那条，不在海上绕圈。
        Vec3 near = new Vec3(0, 100, 900);
        FixedWingFlight flight = inAir(flying(0, near), new Vec3(0, 64, 1000));
        for (long tick = 1; tick < 200 && !flight.landing().startsWith("落在目的地附近"); tick++) {
            flight.fly(ISLAND, new FixedWingPilot.Attitude(0, 0), flying(tick, near), tick);
        }
        assertTrue(flight.landing().startsWith("落在目的地附近"), flight.landing() + " " + flight.notes());
        Vec3 far = new Vec3(0, 100, -2000);
        flight.stop(flying(200, far), ISLAND);
        for (long tick = 201; tick < 600 && !flight.landing().startsWith("附近没有能降的地"); tick++) {
            flight.fly(ISLAND, new FixedWingPilot.Attitude(0, 0), flying(tick, far), tick);
        }
        assertTrue(flight.landing().startsWith("附近没有能降的地，回原来挑的那条"), flight.landing() + " " + flight.notes());
    }
}
