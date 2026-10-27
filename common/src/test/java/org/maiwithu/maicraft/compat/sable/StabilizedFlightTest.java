// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.vehicle.spi.FlightStabilizers;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleDriver;

/**
 * 用飞行稳定器开飞艇手上那一半（替身稳定器）：船底高度换成转动点高度设进高度稳定器，读回是自己设的才算收下，
 * 60 刻没收下照实停；差不到半格、隔不够 10 刻不重设；开之前打开没开的、给耦合器设速度并读回；够不着的稳定器说离多远；
 * 水平速度按耦合器的最大速度折成四个方向的键，超了两根轴一起缩。
 */
class StabilizedFlightTest {

    private static final BlockPos HOLD = new BlockPos(0, 0, 0);
    private static final BlockPos ATTITUDE = new BlockPos(1, 0, 0);
    private static final BlockPos COUPLER = new BlockPos(2, 0, 0);

    /** 替身稳定器：记下设过的目标高度；accepts 为假时服务器像没收到一样，读回的还是原来的样子。 */
    private static final class Fake implements FlightStabilizers {
        final boolean accepts;
        final List<Double> targets = new ArrayList<>();
        String mode = "hold_current";
        double target = 64;
        boolean attitudeOn;
        double speed;

        Fake(boolean accepts) {
            this.accepts = accepts;
        }

        @Override public Optional<Setup> read(String structureId) {
            return Optional.of(new Setup(List.of(
                    new Stabilizer(ALTITUDE_HOLD, HOLD, true, true, OUTPUT_DIRECT, mode, target, 70, 0, 0, 0),
                    new Stabilizer(ATTITUDE_HOLD, ATTITUDE, attitudeOn, true, OUTPUT_DIRECT, "level", Double.NaN, Double.NaN, 0, 0, 0),
                    new Stabilizer(VELOCITY_COUPLER, COUPLER, true, true, OUTPUT_DIRECT, "", Double.NaN, Double.NaN, speed, 0, speed)),
                    List.of(), true, ""));
        }

        @Override public boolean holdAltitude(String structureId, BlockPos local, double y) {
            targets.add(y);
            if (accepts) {
                mode = "hold_set";
                target = y;
            }
            return true;
        }

        @Override public boolean switchOn(String structureId, BlockPos local) {
            if (accepts) attitudeOn = true;
            return true;
        }

        @Override public boolean setHorizontalSpeed(String structureId, BlockPos local, double value) {
            if (accepts) speed = value;
            return true;
        }
    }

    private static VehicleDriver.Stabilized plan(Fake fake) {
        return new VehicleDriver.Stabilized(fake, Map.of("+x", "打字机:D", "-x", "打字机:A", "+z", "打字机:W", "-z", "打字机:S"),
                List.of(HOLD), List.of(ATTITUDE), List.of(COUPLER));
    }

    /** 一艘没转的船：转动点在世界 (0.5, 72, 0.5)，船底 y=70（转动点比船底高 2 格）。 */
    private static SableStructures.Structure ship() {
        SableStructures.Pose pose = new SableStructures.Pose(new Vec3(0.5, 72, 0.5), new double[]{0, 0, 0, 1},
                new Vec3(0.5, 2, 0.5), new Vec3(1, 1, 1));
        return new SableStructures.Structure("ship", "", new AABB(-2, 70, -2, 3, 74, 3), pose, pose, BlockPos.ZERO,
                new BlockPos(2, 4, 2), true, BlockPos.ZERO);
    }

    private static StabilizedFlight flight(Fake fake) {
        return new StabilizedFlight("ship", plan(fake), Map.of("+x", 1, "-x", 2, "+z", 3, "-z", 4));
    }

    @Test
    void 船底高度换成转动点高度设下去_读回是自己设的才算收下() {
        Fake fake = new Fake(true);
        StabilizedFlight flight = flight(fake);
        flight.fly(0, new StabilizedPilot.Command(80, 0, 0), ship());
        assertEquals(List.of(82.0), fake.targets, "转动点比船底高 2 格");
        flight.fly(5, new StabilizedPilot.Command(80.3, 0, 0), ship());
        assertEquals(1, fake.targets.size(), "差不到半格不重设");
        flight.fly(6, new StabilizedPilot.Command(85, 0, 0), ship());
        assertEquals(1, fake.targets.size(), "隔不够 10 刻不重设");
        for (long tick = 10; tick <= 200; tick++) flight.fly(tick, new StabilizedPilot.Command(85, 0, 0), ship());
        assertEquals(List.of(82.0, 87.0), fake.targets);
        assertNull(flight.problem(), "读回的目标就是设下去的");
    }

    @Test
    void 高度稳定器不收目标就照实停() {
        Fake fake = new Fake(false);
        StabilizedFlight flight = flight(fake);
        for (long tick = 0; tick <= StabilizedFlight.CONFIRM_TICKS + 20; tick++) {
            flight.fly(tick, new StabilizedPilot.Command(80, 0, 0), ship());
        }
        assertNotNull(flight.problem());
        assertTrue(flight.problem().contains("没收下目标高度"), flight.problem());
    }

    @Test
    void 开之前打开没开的_设上速度_读回对了才走() {
        Fake fake = new Fake(true);
        StabilizedFlight flight = flight(fake);
        assertEquals("", flight.prime(0));
        assertEquals(StabilizedFlight.SPEED, fake.speed);
        assertEquals(List.of("打开了 1 个没开的稳定器", "线速度耦合器的最大水平速度是 0，设成每秒 6 格"), flight.notes());

        StabilizedFlight ignored = flight(new Fake(false));
        assertNull(ignored.prime(0), "还在等读回");
        String why = ignored.prime(StabilizedFlight.CONFIRM_TICKS);
        assertEquals("打开稳定器、设线速度耦合器的速度后 3 秒读回还没变", why);
    }

    @Test
    void 坐着够不着的稳定器说离多远() {
        Fake fake = new Fake(true);
        VehicleDriver.Stabilized far = new VehicleDriver.Stabilized(fake, Map.of(), List.of(new BlockPos(0, 2, 12)),
                List.of(), List.of());
        String why = StabilizedFlight.outOfReach(far, ship(), new Vec3(0.5, 73, 0.5));
        assertNotNull(why);
        assertTrue(why.startsWith("高度稳定器离驾驶位 12"), why);
        assertNull(StabilizedFlight.outOfReach(plan(fake), ship(), new Vec3(0.5, 73, 0.5)));
    }

    @Test
    void 水平速度按耦合器的最大速度折成四个方向的键() {
        Map<String, Double> slow = StabilizedFlight.demands(3, -4, 6, 6);
        assertEquals(0.5, slow.get("+x"), 1e-9);
        assertEquals(4 / 6.0, slow.get("-z"), 1e-9);
        Map<String, Double> fast = StabilizedFlight.demands(12, 6, 6, 6);
        assertEquals(1.0, fast.get("+x"), 1e-9, "超了两根轴一起缩，保住方向");
        assertEquals(0.5, fast.get("+z"), 1e-9);
        assertEquals(0.0, StabilizedFlight.demands(0, 5, 6, 0).get("+z"), 1e-9, "那根轴最大速度是 0 就不按");
    }
}
