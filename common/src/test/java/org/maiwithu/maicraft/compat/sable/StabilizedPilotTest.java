// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

/**
 * 装了飞行稳定器的飞艇飞控：放进一个玩具物理里跑——高度稳定器按目标高度把船拉过去，线速度耦合器按想要的速度推、松开就刹——
 * 要走完"先升到离地约 8 格 → 巡航 → 进近 → 下降 → 接地"，升够之前不往前走，下降目标不低于落点下一点点，落在落点附近；
 * 设了目标高度不离地就照实失败。四个方向的键按名字给量，4 刻一组按比例按。
 */
class StabilizedPilotTest {

    /** 玩具飞艇：高度按目标高度的弹簧加阻尼走，水平速度追着想要的速度，地面在 y=64。 */
    private static final class Toy {
        final boolean lifts;
        double x, y = 64, z, vx, vy, vz;
        long tick;

        Toy(boolean lifts) {
            this.lifts = lifts;
        }

        PilotSample sample() {
            boolean grounded = y <= 64.001;
            return new PilotSample(tick, new Vec3(x, y, z), new Vec3(vx, vy, vz), 0, 0, 0, 0,
                    grounded ? VehicleAttitude.GROUNDED : VehicleAttitude.AIRBORNE);
        }

        void step(StabilizedPilot.Command command) {
            double dt = 0.05;
            if (lifts) vy += ((command.altitude() - y) * 2 - vy * 3) * dt;
            vx += (command.vx() - vx) * 2 * dt;
            vz += (command.vz() - vz) * 2 * dt;
            x += vx * dt;
            y += vy * dt;
            z += vz * dt;
            if (y < 64) {
                y = 64;
                vy = 0;
                vx *= 0.5;
                vz *= 0.5;
            }
            tick++;
        }
    }

    @Test
    void 先升够再走_巡航_进近_下降_接地() {
        Toy ship = new Toy(true);
        StabilizedPilot pilot = new StabilizedPilot(64);
        AirshipPilot.Course course = new AirshipPilot.Course(new Vec3(80, 64, -30), 64 + 24);
        double lowestTarget = Double.MAX_VALUE;
        for (int i = 0; i < 20000 && pilot.phase() != StabilizedPilot.Phase.LANDED
                && pilot.phase() != StabilizedPilot.Phase.FAILED; i++) {
            StabilizedPilot.Command command = pilot.tick(ship.sample(), course);
            if (pilot.phase() == StabilizedPilot.Phase.CLIMB_OUT) {
                assertEquals(0, Math.hypot(command.vx(), command.vz()), 1e-9, "升够之前不往前走");
            }
            if (pilot.phase() == StabilizedPilot.Phase.DESCENT) lowestTarget = Math.min(lowestTarget, command.altitude());
            ship.step(command);
        }
        assertEquals(StabilizedPilot.Phase.LANDED, pilot.phase(), pilot.transitions().toString());
        assertFalse(pilot.unplannedTouchdown(), pilot.transitions().toString());
        assertTrue(lowestTarget >= 64 - StabilizedPilot.SINK - 1e-9, "下降目标不低于落点下一点点：" + lowestTarget);
        assertTrue(Math.hypot(ship.x - 80, ship.z + 30) < 2, "落在落点附近：" + ship.x + "," + ship.z);
        assertTrue(pilot.transitions().getFirst().startsWith("climb_out → cruise"), pilot.transitions().toString());
        assertTrue(pilot.transitions().stream().anyMatch(line -> line.startsWith("approach → descent")),
                pilot.transitions().toString());
    }

    @Test
    void 设了目标高度不离地就照实失败() {
        Toy ship = new Toy(false);
        StabilizedPilot pilot = new StabilizedPilot(64);
        AirshipPilot.Course course = new AirshipPilot.Course(new Vec3(80, 64, 0), 64 + 24);
        for (int i = 0; i < 1000 && pilot.phase() != StabilizedPilot.Phase.FAILED; i++) ship.step(pilot.tick(ship.sample(), course));
        assertEquals(StabilizedPilot.Phase.FAILED, pilot.phase());
        assertTrue(pilot.failure().startsWith("设了目标高度 30 秒没离地"), pilot.failure());
    }

    @Test
    void 四个方向的键按名字给量_4刻一组按比例按() {
        KeyMixer mixer = new KeyMixer(Map.of("+x", 1, "-x", 2, "+z", 3, "-z", 4));
        assertEquals(Set.of(1, 4), mixer.tick(0, Map.of("+x", 1.0, "-z", 1.0)));
        int pressed = 0;
        for (long tick = 4; tick < 4 + 4 * 10; tick++) if (mixer.tick(tick, Map.of("+z", 0.5)).contains(3)) pressed++;
        assertEquals(20, pressed, "一半的量：十组里按五组，每组 4 刻");
        assertTrue(mixer.tick(100, Map.of()).isEmpty(), "没给的当 0，全松开");
    }
}
