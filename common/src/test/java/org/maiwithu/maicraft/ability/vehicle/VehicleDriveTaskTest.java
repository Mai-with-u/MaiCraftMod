// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.ability.vehicle.api.VehicleControls;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleDriver;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleReader;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 驾驶任务结算固定翼：飞着看出舵接反了，推出来、试过的那行改成试过，LLM 定的那行不改、照实说；
 * 停在地上试按固定翼的舵看不出动，不记成 none、提示照接线定。
 */
class VehicleDriveTaskTest {

    @TempDir
    Path folder;

    private VehicleControls controls;

    @BeforeEach
    void setUp() {
        controls = new VehicleControls(new DocumentStore(folder.resolve("world.db")), "w");
    }

    /** 一架替身固定翼：回路推出了动力，两个舵键推成方向未定的转向。 */
    private static final VehicleReader PLANE = id -> Optional.of(new VehicleReader.View(id, "", "plane", true, List.of(),
            List.of(new VehicleReader.Input("打字机:W", "a + b", 1, List.of(), "power", "推的"),
                    new VehicleReader.Input("打字机:↑", "a + c", 1, List.of(), "steer", "推的"),
                    new VehicleReader.Input("打字机:A", "a + d", 1, List.of(), "steer", "推的")),
            new VehicleReader.State(false, 0, 0, 0, 0, "grounded", ""), ""));

    /** 一个替身驾驶办法：第一刻就做完，交出给定的经过。 */
    private static VehicleDriver driver(VehicleDriver.Report report) {
        VehicleDriver.Run run = new VehicleDriver.Run() {
            @Override public VehicleDriver.Report report() { return report; }
            @Override public ActionStatus tick(TickContext context) { return ActionStatus.done(); }
            @Override public String describe() { return "替身驾驶"; }
        };
        return new VehicleDriver() {
            @Override public Optional<WorldPosition> boardAt(String structureId) { return Optional.empty(); }
            @Override public Optional<Run> test(TestRequest request) { return Optional.of(run); }
            @Override public Optional<Run> drive(DriveRequest request) { return Optional.of(run); }
        };
    }

    private TaskResult run(VehicleDriveInput input, VehicleDriver.Report report) {
        VehicleDriveTask task = new VehicleDriveTask(input, List.of(driver(report)), List.of(PLANE), controls);
        task.start(TickContext.of(0, null));
        for (long tick = 1; tick <= 100; tick++) {
            if (task.tick(TickContext.of(tick, null)) instanceof TickResult.Finished finished) return finished.result();
        }
        throw new AssertionError("一百刻还没收场");
    }

    @Test
    void 飞着看出舵接反了_试过的改记_LLM定的不改() {
        controls.assign("ship", Map.of("打字机:A", "yaw_left"), "+z");
        VehicleDriver.Drove drove = new VehicleDriver.Drove(List.of(), "", 300, 5, true, 30, true, 20, "keys",
                new VehicleDriver.Takeoff(24, 8.0, true), 0);
        VehicleDriver.Report report = new VehicleDriver.Report("ship", "坐在驾驶座上", List.of(), true, true, "", drove,
                Map.of("打字机:↑", "pitch_down", "打字机:A", "yaw_right"));
        VehicleDriveInput input = new VehicleDriveInput(null, new VehicleDriver.DriveRequest("ship", new Vec3(300, 64, 0),
                false, null, "plane", "+z", Map.of("power", "打字机:W"), Permissions.DEFAULT));
        TaskResult result = run(input, report);
        VehicleControls.Stored stored = controls.stored("ship");
        assertTrue(stored.entries().contains(new VehicleControls.Entry("打字机:↑", "pitch_down", VehicleControls.TESTED,
                "飞着看出来的")), stored.toString());
        assertTrue(stored.entries().contains(new VehicleControls.Entry("打字机:A", "yaw_left", VehicleControls.ASSIGNED,
                "LLM 定的")), "LLM 定的那行不改：" + stored);
        assertTrue(result.summary().contains("LLM 定的没改：打字机:A 实际是 yaw_right"), result.summary());
    }

    @Test
    void 停在地上试按固定翼的舵看不出动_不记成none_提示照接线定() {
        VehicleDriver.Tried rudder = new VehicleDriver.Tried("打字机:↑", 20, true, true, "看不出动",
                new VehicleDriver.Effect(0.05, 0, 0.02, 0.5, 0.3, 0.1), "", List.of());
        VehicleDriver.Tried power = new VehicleDriver.Tried("打字机:W", 20, true, true, "往前挪了",
                new VehicleDriver.Effect(0, 0, 2.5, 0, 0, 0), "", List.of());
        VehicleDriver.Report report = new VehicleDriver.Report("ship", "坐在驾驶座上", List.of(rudder, power), true, true,
                "", null);
        VehicleDriveInput input = new VehicleDriveInput(new VehicleDriver.TestRequest("ship", BlockPos.ZERO,
                List.of(new VehicleDriver.Press("打字机:↑", 20), new VehicleDriver.Press("打字机:W", 20)),
                Permissions.DEFAULT), null);
        TaskResult result = run(input, report);
        VehicleControls.Stored stored = controls.stored("ship");
        assertEquals(List.of("打字机:W"), stored.entries().stream().map(VehicleControls.Entry::control).toList(),
                "舵键没记进操纵表：" + stored);
        assertEquals("+z", stored.forward());
        assertTrue(result.summary().contains("固定翼的舵要有速度才起作用"), result.summary());
    }
}
