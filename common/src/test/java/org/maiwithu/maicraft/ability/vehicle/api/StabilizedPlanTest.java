// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.vehicle.spi.FlightStabilizers;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleReader;

/**
 * 用飞行稳定器开飞艇的计划：高度稳定器、智能姿态稳定器、线速度耦合器都能干活，且无线红石打字机的键经代理接到耦合器四个水平方向，
 * 才走"设目标高度 + 给方向"；少哪样照旧按键并说清；没开的、最大速度是 0 的列出来先处理；看载具那一节不写没有的数。
 */
class StabilizedPlanTest {

    private static final BlockPos HOLD = new BlockPos(1, 0, 0);
    private static final BlockPos ATTITUDE = new BlockPos(2, 0, 0);
    private static final BlockPos COUPLER = new BlockPos(3, 0, 0);

    private static FlightStabilizers.Stabilizer part(String kind, BlockPos at, boolean on, boolean powered, String output,
            double maxX) {
        return new FlightStabilizers.Stabilizer(kind, at, on, powered, output, "", kind.equals("altitude_hold") ? 80 : Double.NaN,
                kind.equals("altitude_hold") ? 72.4 : Double.NaN, maxX, 0, maxX);
    }

    private static List<FlightStabilizers.Stabilizer> parts() {
        return List.of(part(FlightStabilizers.ALTITUDE_HOLD, HOLD, true, true, FlightStabilizers.OUTPUT_DIRECT, 0),
                part(FlightStabilizers.ATTITUDE_HOLD, ATTITUDE, false, true, FlightStabilizers.OUTPUT_DIRECT, 0),
                part(FlightStabilizers.VELOCITY_COUPLER, COUPLER, true, true, FlightStabilizers.OUTPUT_COMPUTER, 0));
    }

    private static FlightStabilizers.ProxyInput wire(String frequency, String action) {
        return new FlightStabilizers.ProxyInput(frequency, FlightStabilizers.VELOCITY_COUPLER, COUPLER, action);
    }

    private static final List<FlightStabilizers.ProxyInput> FOUR = List.of(wire("a + w", "body_vz_pos"),
            wire("a + s", "body_vz_neg"), wire("a + d", "body_vx_pos"), wire("a + a", "body_vx_neg"));

    private static final List<VehicleReader.Input> KEYS = List.of(key("打字机:W", "a + w"), key("打字机:S", "a + s"),
            key("打字机:D", "a + d"), key("打字机:A", "a + a"));

    private static VehicleReader.Input key(String control, String frequency) {
        return new VehicleReader.Input(control, frequency, 1, List.of(), "unknown", "");
    }

    @Test
    void 三样都能干活_四个方向都接上就设目标高度加给方向() {
        StabilizedPlan.Plan plan = StabilizedPlan.of("airship", KEYS, new FlightStabilizers.Setup(parts(), FOUR, true, ""));
        assertTrue(plan.ready(), plan.why());
        assertEquals(Map.of("+z", "打字机:W", "-z", "打字机:S", "+x", "打字机:D", "-x", "打字机:A"), plan.directions());
        assertEquals(List.of(HOLD), plan.altitudeHolds());
        assertEquals(List.of(ATTITUDE), plan.switchOn(), "姿态稳定器没开，开之前先打开");
        assertEquals(List.of(COUPLER), plan.slowCouplers(), "耦合器的最大水平速度还是 0，开之前先设");
        assertTrue(StabilizedPlan.of("unknown", KEYS, new FlightStabilizers.Setup(parts(), FOUR, true, "")).ready(),
                "认不出种类（推进器没在名单里）也能用稳定器飞");
    }

    @Test
    void 少哪样照旧按键并说清() {
        assertEquals("没装飞行稳定器", StabilizedPlan.of("airship", KEYS,
                new FlightStabilizers.Setup(List.of(), List.of(), true, "")).why());
        assertFalse(StabilizedPlan.of("car", KEYS, new FlightStabilizers.Setup(parts(), FOUR, true, "")).ready());

        List<FlightStabilizers.Stabilizer> unpowered = new ArrayList<>(parts());
        unpowered.set(0, part(FlightStabilizers.ALTITUDE_HOLD, HOLD, true, false, FlightStabilizers.OUTPUT_DIRECT, 0));
        assertTrue(StabilizedPlan.of("airship", KEYS, new FlightStabilizers.Setup(unpowered, FOUR, true, "")).why()
                .startsWith("高度稳定器底下的轴没转"));

        List<FlightStabilizers.Stabilizer> idle = new ArrayList<>(parts());
        idle.set(2, part(FlightStabilizers.VELOCITY_COUPLER, COUPLER, true, true, FlightStabilizers.OUTPUT_NONE, 6));
        assertTrue(StabilizedPlan.of("airship", KEYS, new FlightStabilizers.Setup(idle, FOUR, true, "")).why()
                .startsWith("线速度耦合器既没接飞控计算机也没开直接施力"));

        StabilizedPlan.Plan half = StabilizedPlan.of("airship", List.of(KEYS.get(0), KEYS.get(1)),
                new FlightStabilizers.Setup(parts(), FOUR, true, ""));
        assertFalse(half.ready());
        assertEquals("线速度耦合器沿机体的方向只接上了 +z、-z，还缺 +x、-x（+x（频率 a + d 没有键发）；-x（频率 a + a 没有键发））"
                + "；四个方向都接上才能不转向直接往目标走", half.why());
        assertEquals("", half.wires().get(2).control(), "没有键发的那一路照样列出来");
    }

    @Test
    void 看载具那一节不写没有的数() {
        FlightStabilizers.Setup setup = new FlightStabilizers.Setup(parts(), FOUR, true, "");
        StabilizedPlan.Section section = StabilizedPlan.section(setup, StabilizedPlan.of("airship", KEYS, setup));
        assertEquals(StabilizedPlan.BY_STABILIZERS, section.drivenBy());
        assertEquals(80.0, section.parts().get(0).target());
        assertEquals(72.4, section.parts().get(0).altitude());
        assertNull(section.parts().get(0).maxX(), "高度稳定器没有最大速度");
        assertNull(section.parts().get(1).target(), "姿态稳定器没有目标高度");
        assertEquals(0.0, section.parts().get(2).maxX());
        assertEquals("打字机:W", section.inputs().get(0).control());
    }
}
