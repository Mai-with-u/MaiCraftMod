// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.vehicle.spi.FlightStabilizers;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleReader;

/**
 * 用飞行稳定器开飞艇的计划（纯函数）：结构上装了能干活的高度稳定器、智能姿态稳定器、线速度耦合器，
 * 而且无线红石打字机的键经飞控红石代理接到了耦合器沿机体 +x、-x、+z、-z 四个方向，驾驶就改成"设目标高度 + 给方向"——
 * 高度交给高度稳定器，船身交给姿态稳定器配平，往哪走按键经代理告诉耦合器，松开键它就刹住；船不用转向，
 * 所以四个方向都要接上。少哪样就照旧按键开，并说清少的是什么。开之前要打开的稳定器、最大水平速度还是 0 的耦合器也列出来，
 * 驾驶时先照做。看载具（machine_inspect）与驾驶（vehicle_drive）共用这一份。
 */
public final class StabilizedPlan {

    /** 四个水平方向：沿机体 +x、-x、+z、-z。 */
    public static final List<String> DIRECTIONS = List.of("+x", "-x", "+z", "-z");
    /** 线速度耦合器的方向动作 → 机体方向。 */
    private static final Map<String, String> COUPLER_ACTIONS = Map.of("body_vx_pos", "+x", "body_vx_neg", "-x",
            "body_vz_pos", "+z", "body_vz_neg", "-z");
    /** 驾驶走哪条路：设目标高度 + 给方向，或照旧按键。 */
    public static final String BY_STABILIZERS = "stabilizers";
    public static final String BY_KEYS = "keys";

    /**
     * 飞控红石代理一个接了动作的面接到了哪个键。
     *
     * @param control   哪个键发这个频率（打字机:D）；没有键发、面旁边不是无线收端为空串
     * @param frequency 面旁边无线收端听的频率；不是无线收端为空串
     * @param device    转给谁：altitude_hold / attitude_hold / velocity_coupler，别的是方块 ID
     * @param action    动作原名（body_vz_pos、master_switch……）
     */
    public record Wire(String control, String frequency, String device, String action) {}

    /**
     * 计划。
     *
     * @param ready         能走"设目标高度 + 给方向"
     * @param why           走不了时为什么，一句话；能走时空串
     * @param directions    机体方向 → 按哪个键
     * @param altitudeHolds 要设目标高度的高度稳定器（地块格）
     * @param switchOn      开之前要打开的智能姿态稳定器、线速度耦合器（地块格）
     * @param slowCouplers  最大水平目标速度是 0、开之前要先设速度的线速度耦合器（地块格）
     * @param wires         代理每个接了动作的面接到了哪个键
     */
    public record Plan(boolean ready, String why, Map<String, String> directions, List<BlockPos> altitudeHolds,
                       List<BlockPos> switchOn, List<BlockPos> slowCouplers, List<Wire> wires) {
        public Plan {
            directions = Map.copyOf(directions);
            altitudeHolds = List.copyOf(altitudeHolds);
            switchOn = List.copyOf(switchOn);
            slowCouplers = List.copyOf(slowCouplers);
            wires = List.copyOf(wires);
        }
    }

    private StabilizedPlan() {}

    /**
     * @param kind     载具种类（machine_inspect 的 kind）：车和船不走稳定器
     * @param controls 无线红石打字机每个键发的频率
     */
    public static Plan of(String kind, List<VehicleReader.Input> controls, FlightStabilizers.Setup setup) {
        List<Wire> wires = new ArrayList<>();
        for (FlightStabilizers.ProxyInput input : setup.inputs()) {
            String control = input.frequency().isEmpty() ? "" : controls.stream()
                    .filter(key -> key.frequency().equals(input.frequency())).map(VehicleReader.Input::control)
                    .findFirst().orElse("");
            wires.add(new Wire(control, input.frequency(), input.device(), input.action()));
        }
        if (setup.stabilizers().isEmpty()) return refused("没装飞行稳定器", wires);
        if (kind.equals("car") || kind.equals("boat") || kind.equals("structure")) {
            return refused("种类是 " + kind + "，飞行稳定器只给飞的载具用", wires);
        }
        List<FlightStabilizers.Stabilizer> holds = working(setup, FlightStabilizers.ALTITUDE_HOLD);
        List<FlightStabilizers.Stabilizer> attitudes = working(setup, FlightStabilizers.ATTITUDE_HOLD);
        List<FlightStabilizers.Stabilizer> couplers = working(setup, FlightStabilizers.VELOCITY_COUPLER);
        Optional<String> lacking = lacking(setup, FlightStabilizers.ALTITUDE_HOLD, "高度稳定器", holds)
                .or(() -> lacking(setup, FlightStabilizers.ATTITUDE_HOLD, "智能姿态稳定器", attitudes))
                .or(() -> lacking(setup, FlightStabilizers.VELOCITY_COUPLER, "线速度耦合器", couplers));
        if (lacking.isPresent()) return refused(lacking.get(), wires);
        // 四个方向各找一个键：转给能干活的耦合器、动作是沿机体水平方向的、有键发这个频率。
        Set<BlockPos> usable = couplers.stream().map(FlightStabilizers.Stabilizer::local).collect(Collectors.toSet());
        Map<String, String> directions = new LinkedHashMap<>();
        List<String> unkeyed = new ArrayList<>();
        for (int index = 0; index < setup.inputs().size(); index++) {
            FlightStabilizers.ProxyInput input = setup.inputs().get(index);
            String direction = COUPLER_ACTIONS.get(input.action());
            if (direction == null || !input.device().equals(FlightStabilizers.VELOCITY_COUPLER)
                    || !usable.contains(input.target())) {
                continue;
            }
            Wire wire = wires.get(index);
            if (wire.control().isEmpty()) unkeyed.add(direction + (input.frequency().isEmpty() ? "（代理那一面旁边不是无线收端）"
                    : "（频率 " + input.frequency() + " 没有键发）"));
            else directions.putIfAbsent(direction, wire.control());
        }
        List<String> missing = DIRECTIONS.stream().filter(direction -> !directions.containsKey(direction)).toList();
        if (!missing.isEmpty()) {
            return refused("线速度耦合器沿机体的方向" + (directions.isEmpty() ? "一个也没接到无线红石打字机的键上"
                    : "只接上了 " + String.join("、", directions.keySet())) + "，还缺 " + String.join("、", missing)
                    + (unkeyed.isEmpty() ? "" : "（" + String.join("；", unkeyed) + "）")
                    + "；四个方向都接上才能不转向直接往目标走", wires);
        }
        List<BlockPos> switchOn = new ArrayList<>();
        for (FlightStabilizers.Stabilizer part : attitudes) if (!part.on()) switchOn.add(part.local());
        for (FlightStabilizers.Stabilizer part : couplers) if (!part.on()) switchOn.add(part.local());
        List<BlockPos> slow = couplers.stream().filter(part -> part.maxX() <= 0 || part.maxZ() <= 0)
                .map(FlightStabilizers.Stabilizer::local).toList();
        return new Plan(true, "", directions, holds.stream().map(FlightStabilizers.Stabilizer::local).toList(),
                switchOn, slow, wires);
    }

    // 一种稳定器里能干活的（有动力、有地方出力）。
    private static List<FlightStabilizers.Stabilizer> working(FlightStabilizers.Setup setup, String kind) {
        return setup.stabilizers().stream().filter(part -> part.kind().equals(kind) && part.working()).toList();
    }

    // 少了某一种能干活的稳定器：没装、轴没转、没地方出力，按看到的第一个说。
    private static Optional<String> lacking(FlightStabilizers.Setup setup, String kind, String name,
            List<FlightStabilizers.Stabilizer> working) {
        if (!working.isEmpty()) return Optional.empty();
        Optional<FlightStabilizers.Stabilizer> any = setup.stabilizers().stream().filter(part -> part.kind().equals(kind))
                .findFirst();
        if (any.isEmpty()) return Optional.of("没装" + name);
        if (!any.get().powered()) return Optional.of(name + "底下的轴没转（没接动力或应力过载），它什么都不做");
        return Optional.of(name + "既没接飞控计算机也没开直接施力，算了也不出力");
    }

    private static Plan refused(String why, List<Wire> wires) {
        return new Plan(false, why, Map.of(), List.of(), List.of(), List.of(), wires);
    }

    /**
     * 看载具时列出来的一个稳定器：不带地块里的格（那是结构内部的坐标），没有的数不写。
     *
     * @param target   高度稳定器的目标高度（结构转动点的世界 y）
     * @param altitude 高度稳定器此刻读到的高度
     * @param maxX     线速度耦合器沿机体 x 的最大目标速度（格每秒）
     */
    public record Shown(String kind, boolean on, boolean powered, String output, String mode, Double target,
                        Double altitude, Double maxX, Double maxY, Double maxZ) {}

    /**
     * 看载具（machine_inspect）里的 stabilizers 一节。
     *
     * @param parts    每个稳定器
     * @param inputs   代理每个接了动作的面接到了哪个键
     * @param drivenBy vehicle_drive 开它会走哪条路：stabilizers 设目标高度 + 给方向 / keys 照旧按键
     * @param why      照旧按键时为什么；走稳定器时空串
     * @param note     没读全的部分；没有为空串
     */
    public record Section(List<Shown> parts, List<Wire> inputs, String drivenBy, String why, String note) {
        public Section {
            parts = List.copyOf(parts);
            inputs = List.copyOf(inputs);
        }
    }

    /** 看载具时的那一节：计划交代走哪条路，稳定器按 LLM 读得懂的样子列。 */
    public static Section section(FlightStabilizers.Setup setup, Plan plan) {
        List<Shown> parts = new ArrayList<>();
        for (FlightStabilizers.Stabilizer part : setup.stabilizers()) {
            boolean coupler = part.kind().equals(FlightStabilizers.VELOCITY_COUPLER);
            parts.add(new Shown(part.kind(), part.on(), part.powered(), part.output(), part.mode(), number(part.target()),
                    number(part.altitude()), coupler ? part.maxX() : null, coupler ? part.maxY() : null,
                    coupler ? part.maxZ() : null));
        }
        return new Section(parts, plan.wires(), plan.ready() ? BY_STABILIZERS : BY_KEYS, plan.why(), setup.note());
    }

    private static Double number(double value) {
        return Double.isFinite(value) ? Math.round(value * 10) / 10.0 : null;
    }
}
