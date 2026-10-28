// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

/**
 * 固定翼飞控放进一个粗糙的玩具物理里跑：升力随前进速度涨（每秒 8 格离地）、抬头多给升力、压坡度转弯、地面在 y=64；
 * 要走完滑跑 → 爬升 → 巡航 → 进近 → 下滑 → 拉平 → 落地滑跑 → 停下，离地 8 格内不转弯；
 * 前方地面不够刹停就中止、被叫停就刹住、没有俯仰键靠油门也能飞；落不好复飞、3 次要换跑道、最后一次硬落。
 */
class FixedWingPilotTest {

    private static final FixedWingPilot.Controls ALL = new FixedWingPilot.Controls(true, true, true, false, true);

    /** 玩具固定翼：速度沿机头，升力 = 1.25 × 前进速度 × (1 + 4 × 俯仰弧度) × 气压，重力 10。 */
    private static final class Plane {
        double x, y = 64, z, along, vy, heading, pitch, roll, turnRate;
        double ceiling = Double.MAX_VALUE;
        /** 底下是水：y=64 是水面，浮着时接地探头照原版说悬空（水没有碰撞）。 */
        boolean water;
        long tick;

        Plane(double heading) {
            this.heading = heading;
        }

        boolean grounded() {
            return y <= 64.001;
        }

        PilotSample sample() {
            double fx = -Math.sin(Math.toRadians(heading));
            double fz = Math.cos(Math.toRadians(heading));
            return new PilotSample(tick, new Vec3(x, y, z), new Vec3(fx * along, vy, fz * along), heading,
                    Math.max(Math.abs(pitch), Math.abs(roll)), turnRate, 0,
                    grounded() && !water ? VehicleAttitude.GROUNDED : VehicleAttitude.AIRBORNE);
        }

        FixedWingPilot.Attitude attitude() {
            return new FixedWingPilot.Attitude(pitch, roll);
        }

        void step(FixedWingPilot.Command command) {
            double dt = 0.05;
            boolean air = !grounded();
            double authority = Math.min(1, Math.abs(along) / 6);
            pitch += command.pitch() * 25 * authority * dt;
            roll = air ? roll + command.roll() * 40 * authority * dt : 0;
            turnRate = command.yaw() * 15 * authority + (air ? roll * 0.8 : 0) + (air ? 0 : command.steer() * 10);
            heading += turnRate * dt;
            double accel = command.power() * 4 - along * 0.3;
            if (command.brake() && !air) accel -= 3 * Math.signum(along);
            along += accel * dt;
            if (command.brake() && !air && Math.abs(along) < 0.2) along = 0;
            double pressure = y > ceiling ? Math.max(0, 1 - (y - ceiling) * 0.05) : 1;
            double lift = 1.25 * Math.max(0, along) * (1 + Math.toRadians(pitch) * 4) * pressure;
            vy += (lift - 10 - vy * 0.8) * dt;
            y += vy * dt;
            if (y < 64) {
                y = 64;
                vy = 0;
                pitch = Math.max(0, pitch);
            }
            x += -Math.sin(Math.toRadians(heading)) * along * dt;
            z += Math.cos(Math.toRadians(heading)) * along * dt;
            tick++;
        }
    }

    private static FixedWingPilot.Situation open() {
        return new FixedWingPilot.Situation(Double.NaN, 64, false);
    }

    @Test
    void 滑跑离地_离地八格内不转弯_转向进近_下滑拉平落地停在跑道上() {
        // 机头朝东起飞，目的地在北边：离地 8 格以上才转向北边跑道的进近点。
        Plane plane = new Plane(270);
        Runway runway = new Runway(new Vec3(0, 64, -300), 180, 80, 14, false);
        FixedWingPilot pilot = new FixedWingPilot(ALL, 3, 10, 84, new Vec3(0, 64, -320), false);
        pilot.land(runway, true, false);
        double headingWhenLow = 270;
        for (int i = 0; i < 20 * 300 && pilot.phase() != FixedWingPilot.Phase.LANDED
                && pilot.phase() != FixedWingPilot.Phase.FAILED; i++) {
            FixedWingPilot.Command command = pilot.tick(plane.sample(), plane.attitude(), open());
            if (pilot.leftGround() && plane.y < 64 + FixedWingPilot.CLEARANCE - 0.5
                    && pilot.phase() == FixedWingPilot.Phase.CLIMB_OUT) {
                headingWhenLow = plane.heading;
            }
            plane.step(command);
        }
        List<String> phases = pilot.transitions();
        assertEquals(FixedWingPilot.Phase.LANDED, pilot.phase(), phases.toString());
        assertTrue(pilot.arrived(), phases.toString());
        assertEquals(270, headingWhenLow, 2, "离地 8 格内沿跑道方向直着爬");
        assertTrue(pilot.liftoffSpeed() > 7 && pilot.liftoffSpeed() < 10, "离地速度是量出来的：" + pilot.liftoffSpeed());
        assertTrue(pilot.rolled() > 5, "滑了几格：" + pilot.rolled());
        for (String step : List.of("takeoff_roll → climb_out", "climb_out → cruise", "cruise → approach",
                "approach → descent", "descent → flare", "flare → rollout", "rollout → landed")) {
            assertTrue(phases.stream().anyMatch(line -> line.startsWith(step)), step + " 不在 " + phases);
        }
        assertTrue(Math.abs(runway.lateral(new Vec3(plane.x, 64, plane.z))) < 7, "停在跑道上：" + plane.x + "," + plane.z);
    }

    @Test
    void 前方地面不够刹停还没离地就中止起飞_照实写滑了几格() {
        // 机头前方 20 格就是墙：照样开始滑，剩下的不够刹停就刹住，不撞墙。
        Plane plane = new Plane(270);
        FixedWingPilot pilot = new FixedWingPilot(ALL, 3, 10, 84, new Vec3(400, 64, 0), false);
        for (int i = 0; i < 2000 && pilot.phase() != FixedWingPilot.Phase.FAILED; i++) {
            double clear = 20 - plane.x;
            plane.step(pilot.tick(plane.sample(), plane.attitude(), new FixedWingPilot.Situation(clear, 64, false)));
        }
        assertEquals(FixedWingPilot.Phase.FAILED, pilot.phase(), pilot.transitions().toString());
        assertTrue(plane.x < 20, "没撞墙：" + plane.x);
        assertTrue(pilot.failure().contains("中止起飞") && pilot.failure().contains("滑了")
                && pilot.failure().contains("没离地"), pilot.failure());
        assertFalse(pilot.leftGround());
    }

    @Test
    void 滑跑中被叫停就刹住_停住不算失败() {
        Plane plane = new Plane(270);
        FixedWingPilot pilot = new FixedWingPilot(ALL, 3, 10, 84, new Vec3(400, 64, 0), false);
        for (int i = 0; i < 20; i++) plane.step(pilot.tick(plane.sample(), plane.attitude(), open()));
        pilot.stop();
        for (int i = 0; i < 400 && pilot.phase() != FixedWingPilot.Phase.LANDED; i++) {
            plane.step(pilot.tick(plane.sample(), plane.attitude(), open()));
        }
        assertEquals(FixedWingPilot.Phase.LANDED, pilot.phase(), pilot.transitions().toString());
        assertTrue(pilot.transitions().stream().anyMatch(line -> line.startsWith("takeoff_roll → stop_takeoff")));
    }

    @Test
    void 没有俯仰键靠速度离地_靠油门管高度() {
        Plane plane = new Plane(270);
        FixedWingPilot pilot = new FixedWingPilot(new FixedWingPilot.Controls(false, false, true, false, false), 3, 10, 80,
                new Vec3(2000, 64, 0), false);
        double highest = 64;
        for (int i = 0; i < 20 * 60; i++) {
            FixedWingPilot.Command command = pilot.tick(plane.sample(), plane.attitude(), open());
            assertEquals(0, command.pitch(), 1e-9, "没有俯仰键不按俯仰");
            plane.step(command);
            if (pilot.phase() == FixedWingPilot.Phase.CRUISE) highest = Math.max(highest, plane.y);
        }
        assertTrue(pilot.leftGround(), pilot.transitions().toString());
        assertEquals(FixedWingPilot.Phase.CRUISE, pilot.phase(), pilot.transitions().toString());
        assertEquals(80, plane.y, 4, "油门管住了巡航高度");
        assertTrue(highest < 90, "没一路往上冲：" + highest);
    }

    @Test
    void 满动力升不上去就按此刻高度巡航并写明() {
        Plane plane = new Plane(270);
        plane.ceiling = 74;
        FixedWingPilot pilot = new FixedWingPilot(ALL, 3, 10, 120, new Vec3(3000, 64, 0), false);
        for (int i = 0; i < 20 * 90 && pilot.phase() != FixedWingPilot.Phase.CRUISE; i++) {
            plane.step(pilot.tick(plane.sample(), plane.attitude(), open()));
        }
        assertEquals(FixedWingPilot.Phase.CRUISE, pilot.phase(), pilot.transitions().toString());
        assertTrue(pilot.cruise() < 100, "巡航高度降下来了：" + pilot.cruise());
        assertTrue(pilot.notes().stream().anyMatch(note -> note.contains("升不上去")), pilot.notes().toString());
    }

    @Test
    void 跑道上出现挡路的方块就复飞_同一条三次落不下要换跑道() {
        Plane plane = new Plane(180);
        plane.y = 76;
        plane.along = 11;
        plane.z = -160;
        Runway runway = new Runway(new Vec3(0, 64, -300), 180, 80, 14, false);
        FixedWingPilot pilot = new FixedWingPilot(ALL, 3, 10, 84, new Vec3(0, 64, -320), true);
        pilot.land(runway, true, false);
        for (int i = 0; i < 20 * 600 && !pilot.wantsNextRunway(); i++) {
            boolean blocked = pilot.phase() == FixedWingPilot.Phase.DESCENT;
            plane.step(pilot.tick(plane.sample(), plane.attitude(), new FixedWingPilot.Situation(Double.NaN, 64, blocked)));
        }
        assertTrue(pilot.wantsNextRunway(), pilot.transitions().toString());
        assertEquals(FixedWingPilot.ATTEMPTS, pilot.goArounds(), pilot.transitions().toString());
        assertTrue(pilot.transitions().stream().anyMatch(line -> line.contains("跑道上有挡路的方块，复飞")),
                pilot.transitions().toString());
    }

    @Test
    void 最后一次硬落_挡着也不再复飞() {
        Plane plane = new Plane(180);
        plane.y = 76;
        plane.along = 11;
        plane.z = -160;
        Runway runway = new Runway(new Vec3(0, 64, -300), 180, 80, 14, false);
        FixedWingPilot pilot = new FixedWingPilot(ALL, 3, 10, 84, new Vec3(0, 64, -320), true);
        pilot.land(runway, false, true);
        for (int i = 0; i < 20 * 300 && pilot.phase() != FixedWingPilot.Phase.LANDED; i++) {
            plane.step(pilot.tick(plane.sample(), plane.attitude(), new FixedWingPilot.Situation(Double.NaN, 64, true)));
        }
        assertEquals(FixedWingPilot.Phase.LANDED, pilot.phase(), pilot.transitions() + " 在 " + plane.x + "," + plane.y
                + "," + plane.z + " 速度 " + plane.along + " 俯仰 " + plane.pitch + " 朝向 " + plane.heading);
        assertEquals(0, pilot.goArounds());
        assertFalse(pilot.arrived(), "落在起飞跑道上不算开到了目的地");
    }

    @Test
    void 没有跑道时在目的地上空绕圈() {
        Plane plane = new Plane(180);
        plane.y = 84;
        plane.along = 12;
        FixedWingPilot pilot = new FixedWingPilot(ALL, 3, 10, 84, new Vec3(0, 64, -200), true);
        double farthest = 0;
        for (int i = 0; i < 20 * 120; i++) {
            plane.step(pilot.tick(plane.sample(), plane.attitude(), open()));
            if (i > 20 * 40) farthest = Math.max(farthest, Math.hypot(plane.x, plane.z + 200));
        }
        assertEquals(FixedWingPilot.Phase.CRUISE, pilot.phase());
        assertTrue(farthest < FixedWingPilot.ORBIT_RADIUS * 2.5, "一直在目的地附近绕：最远 " + farthest);
    }

    @Test
    void 迫降在水面上_碰到水面算接地_不卡在拉平() {
        // 海上找不到地，往前方水面迫降（最后一次、不复飞）：浮着时接地探头说悬空，船底贴着水面就算接地，刹停收场。
        Plane plane = new Plane(180);
        plane.water = true;
        plane.y = 76;
        plane.along = 11;
        plane.z = -160;
        Runway sea = new Runway(new Vec3(0, 64, -300), 180, Runways.MIN_LENGTH, 12, true);
        FixedWingPilot pilot = new FixedWingPilot(ALL, 3, 10, 84, new Vec3(0, 64, -2000), true);
        pilot.land(sea, false, true);
        for (int i = 0; i < 20 * 300 && pilot.phase() != FixedWingPilot.Phase.LANDED; i++) {
            plane.step(pilot.tick(plane.sample(), plane.attitude(), new FixedWingPilot.Situation(Double.NaN, 64, false, true)));
        }
        assertEquals(FixedWingPilot.Phase.LANDED, pilot.phase(), pilot.transitions().toString());
        assertTrue(pilot.transitions().stream().anyMatch(line -> line.startsWith("rollout → landed")),
                pilot.transitions().toString());
    }

    @Test
    void 从水面起飞_离地速度按离开水面那一刻量() {
        // 浮在水上时接地探头一直说悬空：不能把第一刻当成离地，离地速度按船底离开水面那一刻的速度记。
        Plane plane = new Plane(270);
        plane.water = true;
        FixedWingPilot pilot = new FixedWingPilot(ALL, 3, 10, 84, new Vec3(2000, 64, 0), false);
        for (int i = 0; i < 20 * 60 && !pilot.leftGround(); i++) {
            plane.step(pilot.tick(plane.sample(), plane.attitude(), new FixedWingPilot.Situation(Double.NaN, 64, false, true)));
        }
        assertTrue(pilot.leftGround(), pilot.transitions().toString());
        assertTrue(pilot.liftoffSpeed() > 7 && pilot.liftoffSpeed() < 10, "离地速度：" + pilot.liftoffSpeed());
    }
}
