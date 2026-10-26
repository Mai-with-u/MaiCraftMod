// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.ability.travel.JourneyInput;
import org.maiwithu.maicraft.ability.vehicle.api.VehicleControls;
import org.maiwithu.maicraft.ability.vehicle.spi.FlightStabilizers;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleDriver;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleReader;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 驾驶能力的决定：参数逐条核对；不在车上先跑一趟出行再回来；走过去还够不着如实结束；在车上就交给驾驶办法；
 * drive 要有去处，操纵表缺必需角色或车头没定就开不了，齐了把"角色 → 输入"交下去；
 * 装了能用的飞行稳定器就带上它的开法（不查升力、转向和车头），装了用不上照旧查操纵表并说为什么。
 */
class VehicleDriveModuleTest {

    @TempDir
    Path folder;

    private static final String TEST = "{\"vehicle\":\"s3\",\"operation\":\"test\",\"inputs\":[{\"control\":\"打字机:W\",\"ticks\":20}]}";

    /** 一个替身驾驶办法：boardAt 按给定的答。 */
    private static VehicleDriver driver(Optional<WorldPosition> board) {
        return new VehicleDriver() {
            @Override public Optional<WorldPosition> boardAt(String structureId) { return board; }
            @Override public Optional<Run> test(TestRequest request) { return Optional.empty(); }
        };
    }

    /** 一辆替身飞艇：回路推出了动力与升力，转向左右未定。 */
    private static final VehicleReader AIRSHIP = id -> Optional.of(new VehicleReader.View(id, "", "airship", true,
            List.of(), List.of(
                    new VehicleReader.Input("打字机:W", "a + b", 1, List.of(), "power", "推的"),
                    new VehicleReader.Input("打字机:空格", "a + c", 1, List.of(), "lift", "推的"),
                    new VehicleReader.Input("打字机:A", "a + d", 1, List.of(), "steer", "推的"),
                    new VehicleReader.Input("打字机:D", "a + e", 1, List.of(), "unknown", "推的")),
            new VehicleReader.State(false, 0, 0, 0, 0, "grounded", ""), ""));

    private VehicleControls controls() {
        return new VehicleControls(new DocumentStore(folder.resolve("world.db")), "w");
    }

    private VehicleDriveModule module(List<VehicleDriver> drivers, VehicleControls controls) {
        return module(drivers, controls, List.of());
    }

    private VehicleDriveModule module(List<VehicleDriver> drivers, VehicleControls controls,
            List<FlightStabilizers> stabilizers) {
        return new VehicleDriveModule(drivers, List.of(AIRSHIP), controls, id -> Optional.of(WorldPosition.here(5, 70, 5)),
                id -> id.equals("s3") ? Optional.of("ship-uuid") : Optional.empty(), name -> Optional.empty(),
                () -> new BlockPos(0, 64, 0), stabilizers);
    }

    private static final BlockPos COUPLER = new BlockPos(3, 0, 0);

    /** 一份替身飞行稳定器：三样都开着能干活，代理按给的频率依次接到耦合器的 +z、-z、+x、-x。 */
    private static FlightStabilizers stabilizers(String... frequencies) {
        List<String> actions = List.of("body_vz_pos", "body_vz_neg", "body_vx_pos", "body_vx_neg");
        List<FlightStabilizers.ProxyInput> inputs = new ArrayList<>();
        for (int i = 0; i < frequencies.length; i++) {
            inputs.add(new FlightStabilizers.ProxyInput(frequencies[i], FlightStabilizers.VELOCITY_COUPLER, COUPLER,
                    actions.get(i)));
        }
        FlightStabilizers.Setup setup = new FlightStabilizers.Setup(List.of(
                new FlightStabilizers.Stabilizer(FlightStabilizers.ALTITUDE_HOLD, new BlockPos(1, 0, 0), true, true,
                        FlightStabilizers.OUTPUT_DIRECT, "hold_set", 80, 70, 0, 0, 0),
                new FlightStabilizers.Stabilizer(FlightStabilizers.ATTITUDE_HOLD, new BlockPos(2, 0, 0), true, true,
                        FlightStabilizers.OUTPUT_DIRECT, "level", Double.NaN, Double.NaN, 0, 0, 0),
                new FlightStabilizers.Stabilizer(FlightStabilizers.VELOCITY_COUPLER, COUPLER, true, true,
                        FlightStabilizers.OUTPUT_DIRECT, "", Double.NaN, Double.NaN, 6, 0, 6)), inputs, true, "");
        return new FlightStabilizers() {
            @Override public Optional<Setup> read(String structureId) { return Optional.of(setup); }
            @Override public boolean holdAltitude(String structureId, BlockPos local, double y) { return true; }
            @Override public boolean switchOn(String structureId, BlockPos local) { return true; }
            @Override public boolean setHorizontalSpeed(String structureId, BlockPos local, double speed) { return true; }
        };
    }

    private StepContext step(String params, Target target, List<TaskResult> earlier) {
        Goal goal = new Goal("maicraft:vehicle_drive", null, target,
                module(List.of(), controls()).spec().paramSpecs().parse(JsonParser.parseString(params).getAsJsonObject())
                        .params(), Permissions.DEFAULT, List.of(), null);
        return new StepContext() {
            @Override public Goal goal() { return goal; }
            @Override public int stepIndex() { return 0; }
            @Override public TickContext tick() { throw new IllegalStateException("决定阶段不碰每刻上下文"); }
            @Override public List<TaskResult> taskResults() { return earlier; }
        };
    }

    private static Problem.Kind failed(StepDecision decision) {
        return assertInstanceOf(StepDecision.Finish.class, decision).result().problem().kind();
    }

    @Test
    void 参数逐条核对() {
        VehicleDriveModule module = module(List.of(driver(Optional.empty())), controls());
        assertEquals(Problem.Kind.UNSUPPORTED, failed(module(List.of(), controls()).decide(step(TEST, null, List.of()))));
        assertEquals(Problem.Kind.INVALID_PARAMETER, failed(module.decide(step("{\"vehicle\":\"飞艇\",\"operation\":\"test\"}", null, List.of()))));
        assertEquals(Problem.Kind.TARGET_GONE, failed(module.decide(step("{\"vehicle\":\"s9\",\"operation\":\"test\"}", null, List.of()))));
        assertEquals(Problem.Kind.INVALID_PARAMETER, failed(module.decide(step("{\"vehicle\":\"s3\"}", null, List.of()))),
                "drive 要给去处");
        assertEquals(Problem.Kind.INVALID_PARAMETER, failed(module.decide(step("{\"vehicle\":\"s3\",\"operation\":\"test\"}", null, List.of()))));
        assertEquals(Problem.Kind.INVALID_PARAMETER, failed(module.decide(step(
                "{\"vehicle\":\"s3\",\"operation\":\"test\",\"inputs\":[{\"control\":\"打字机:W\",\"ticks\":0}]}", null, List.of()))));
    }

    @Test
    void 只按遥控器可以不指哪辆() {
        VehicleDriveModule module = module(List.of(driver(Optional.empty())), controls());
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class, module.decide(step(
                "{\"operation\":\"test\",\"inputs\":[{\"control\":\"遥控器:上\",\"ticks\":20}]}", null, List.of())));
        assertEquals("", assertInstanceOf(VehicleDriveInput.class, run.input()).test().structureId());
        assertEquals(Problem.Kind.INVALID_PARAMETER, failed(module.decide(step(
                "{\"operation\":\"test\",\"inputs\":[{\"control\":\"打字机:W\",\"ticks\":20}]}", null, List.of()))));
    }

    @Test
    void 在车上就交给驾驶办法试按() {
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                module(List.of(driver(Optional.empty())), controls()).decide(step(TEST, null, List.of())));
        VehicleDriver.TestRequest request = assertInstanceOf(VehicleDriveInput.class, run.input()).test();
        assertEquals("ship-uuid", request.structureId());
        assertEquals(List.of(new VehicleDriver.Press("打字机:W", 20)), request.inputs());
    }

    @Test
    void 不在车上先跑一趟出行_回来还够不着如实结束() {
        VehicleDriveModule module = module(List.of(driver(Optional.of(WorldPosition.here(6, 71, 5)))), controls());
        StepDecision.Run walk = assertInstanceOf(StepDecision.Run.class, module.decide(step(TEST, null, List.of())));
        assertInstanceOf(JourneyInput.class, walk.input());
        assertTrue(walk.decideAgain(), "走完回来再决定");
        assertEquals(Problem.Kind.UNREACHABLE, failed(module.decide(step(TEST, null, List.of(TaskResult.done("到了"))))));
    }

    @Test
    void 操纵表不齐开不了_齐了交下角色() {
        VehicleControls controls = controls();
        VehicleDriveModule module = module(List.of(driver(Optional.empty())), controls);
        Target there = new Target.Position(200, 70, 0, null);
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, failed(module.decide(step("{\"vehicle\":\"s3\"}", there, List.of()))),
                "转向左右没定、车头没定");
        controls.assign("ship-uuid", Map.of("打字机:A", "yaw_left"), "");
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, failed(module.decide(step("{\"vehicle\":\"s3\"}", there, List.of()))),
                "车头还没定");
        controls.assign("ship-uuid", Map.of(), "+z");
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                module.decide(step("{\"vehicle\":\"s3\",\"cruise_altitude\":120}", there, List.of())));
        VehicleDriver.DriveRequest drive = assertInstanceOf(VehicleDriveInput.class, run.input()).drive();
        assertEquals(Map.of("power", "打字机:W", "lift", "打字机:空格", "yaw_left", "打字机:A"), drive.roles());
        assertEquals("+z", drive.forward());
        assertEquals(120, drive.cruiseAltitude());
        assertTrue(drive.heightKnown());
    }

    @Test
    void 装了能用的飞行稳定器_缺转向和车头也开得了_请求带上开法() {
        Target there = new Target.Position(200, 70, 0, null);
        VehicleDriveModule module = module(List.of(driver(Optional.empty())), controls(),
                List.of(stabilizers("a + b", "a + c", "a + d", "a + e")));
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                module.decide(step("{\"vehicle\":\"s3\"}", there, List.of())));
        VehicleDriver.DriveRequest drive = assertInstanceOf(VehicleDriveInput.class, run.input()).drive();
        assertEquals(Map.of("+z", "打字机:W", "-z", "打字机:空格", "+x", "打字机:A", "-x", "打字机:D"),
                drive.stabilized().directions());
        assertEquals(List.of(new BlockPos(1, 0, 0)), drive.stabilized().altitudeHolds());
    }

    @Test
    void 装了但只接了三个方向_照旧查操纵表并说为什么() {
        Target there = new Target.Position(200, 70, 0, null);
        VehicleDriveModule module = module(List.of(driver(Optional.empty())), controls(),
                List.of(stabilizers("a + b", "a + c", "a + d")));
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                module.decide(step("{\"vehicle\":\"s3\"}", there, List.of())));
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, finish.result().problem().kind());
        assertTrue(finish.result().problem().message().contains("飞行稳定器也用不上：线速度耦合器沿机体的方向只接上了 +z、-z、+x，还缺 -x"),
                finish.result().problem().message());
    }

    @Test
    void 没装飞行稳定器照旧按键_请求不带开法() {
        VehicleControls controls = controls();
        controls.assign("ship-uuid", Map.of("打字机:A", "yaw_left"), "+z");
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class, module(List.of(driver(Optional.empty())), controls)
                .decide(step("{\"vehicle\":\"s3\"}", new Target.Position(200, 70, 0, null), List.of())));
        assertNull(assertInstanceOf(VehicleDriveInput.class, run.input()).drive().stabilized());
    }
}
