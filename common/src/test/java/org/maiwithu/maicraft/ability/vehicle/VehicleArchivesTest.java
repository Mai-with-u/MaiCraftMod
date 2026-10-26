// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.ability.vehicle.api.VehicleControls;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleAssembler;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleReader;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 拆回再组装时档案跟着走（经组装任务、替身组装办法与替身读载具）：拆回改记到组装器落回的那一格、旧身份删掉、
 * 车头换成世界轴；从同一台组装器组装出改过的船，认作同一艘，操纵表跟到新结构，方块与回路的变化照实列出，
 * 回路变了的试过的角色不带；方块对不上照新载具；没拆回过照新载具；找不到组装器落点就留在原身份下；车站组车不谈档案。
 */
class VehicleArchivesTest {

    @TempDir
    Path folder;

    private static final BlockPos ASSEMBLER = new BlockPos(10, 64, 10);
    private static final Map<String, Integer> SHIP = Map.of("minecraft:oak_planks", 40, "simulated:physics_assembler", 1,
            "aeronautics:propeller_bearing", 2, "aeronautics:adjustable_burner", 1, "simulated:linked_typewriter", 1);

    private final Map<String, VehicleReader.View> views = new HashMap<>();
    private final Map<String, String> seen = new HashMap<>();
    private VehicleControls controls;
    private VehicleArchives archives;

    @BeforeEach
    void setUp() {
        DocumentStore documents = new DocumentStore(folder.resolve("world.db"));
        controls = new VehicleControls(documents, "w");
        VehicleReader reader = id -> Optional.ofNullable(views.get(id));
        archives = new VehicleArchives(controls, documents, "w", List.of(reader), () -> "minecraft:overworld",
                id -> Optional.ofNullable(seen.get(id)));
    }

    private static VehicleReader.View view(String id, double heading, VehicleReader.Input... inputs) {
        return new VehicleReader.View(id, "", "airship", true, List.of(), List.of(inputs),
                new VehicleReader.State(false, 0, heading, 0, 0, "grounded", ""), "");
    }

    private static VehicleReader.Input input(String control, String frequency, String drive) {
        return new VehicleReader.Input(control, frequency, 1, List.of(drive), "unknown", "");
    }

    /** 一个替身组装办法：第一刻就做完，交出给定的结局；拆回时先报要拆的结构。 */
    private static VehicleAssembler assembler(VehicleAssembler.Conversion conversion, String structure) {
        return request -> Optional.of(new VehicleAssembler.Run() {
            @Override public VehicleAssembler.Conversion conversion() { return conversion; }
            @Override public Optional<String> structure() { return Optional.ofNullable(structure); }
            @Override public ActionStatus tick(TickContext context) { return ActionStatus.done(); }
            @Override public String describe() { return "替身拉杆"; }
        });
    }

    private TaskResult run(boolean assemble, VehicleAssembler.Conversion conversion, String structure) {
        VehicleAssembleTask task = new VehicleAssembleTask(new VehicleAssembler.Request(assemble, ASSEMBLER, null,
                Permissions.DEFAULT), List.of(assembler(conversion, structure)), archives);
        task.start(TickContext.of(0, null));
        for (long tick = 1; tick <= 100; tick++) {
            if (task.tick(TickContext.of(tick, null)) instanceof TickResult.Finished finished) return finished.result();
        }
        throw new AssertionError("一百刻还没收场");
    }

    private static VehicleArchives.Handover archive(TaskResult result) {
        return assertInstanceOf(VehicleAssembleDetails.class, result.details()).archive();
    }

    private void disassembleOldShip() {
        views.put("ship-1", view("ship-1", 90,
                input("打字机:W", "a + b", "aeronautics:propeller_bearing"),
                input("打字机:空格", "a + c", "aeronautics:adjustable_burner"),
                input("打字机:A", "a + d", "simulated:torsion_spring")));
        controls.tested("ship-1", List.of(
                new VehicleControls.Entry("打字机:W", "power", VehicleControls.TESTED, "试的"),
                new VehicleControls.Entry("打字机:空格", "lift", VehicleControls.TESTED, "试的")), "+z");
        controls.assign("ship-1", Map.of("打字机:A", "yaw_left"), "");
        TaskResult result = run(false, new VehicleAssembler.Conversion(false, true, "ship-1", -1, "拆回", ASSEMBLER, SHIP),
                "ship-1");
        VehicleArchives.Handover outcome = archive(result);
        assertEquals(VehicleArchives.MOVED_TO_WORLD, outcome.state(), outcome.note());
        assertEquals("世界里 10,64,10", outcome.structure());
        assertEquals(VehicleControls.Stored.EMPTY, controls.stored("ship-1"), "旧身份那一份删掉");
        views.remove("ship-1");
    }

    @Test
    void 拆回改记世界_再组装跟到新结构并标出变化() {
        disassembleOldShip();
        Map<String, Integer> edited = new HashMap<>(SHIP);
        edited.put("aeronautics:propeller_bearing", 1);
        edited.put("minecraft:iron_block", 4);
        views.put("ship-2", view("ship-2", 0,
                input("打字机:W", "a + b", "aeronautics:propeller_bearing"),
                input("打字机:空格", "a + e", "aeronautics:adjustable_burner"),
                input("打字机:D", "a + f", "simulated:torsion_spring")));
        seen.put("ship-2", "s7");
        TaskResult result = run(true, new VehicleAssembler.Conversion(false, true, "ship-2", 49, "组装", ASSEMBLER, edited),
                null);
        VehicleArchives.Handover outcome = archive(result);
        assertEquals(VehicleArchives.FOLLOWED, outcome.state(), outcome.note());
        assertEquals("s7", outcome.structure());
        assertEquals(List.of("少了 aeronautics:propeller_bearing×1", "多了 minecraft:iron_block×4",
                "打字机:空格 的频率从 a + c 换成 a + e", "没了 打字机:A", "新多了 打字机:D（接着 simulated:torsion_spring）"), outcome.changes());
        assertEquals(List.of("打字机:空格 试过的 lift（接线变了，要重新试按）", "打字机:A 的 yaw_left（这个输入没了）"),
                outcome.dropped());
        VehicleControls.Stored now = controls.stored("ship-2");
        assertEquals(List.of("打字机:W"), now.entries().stream().map(VehicleControls.Entry::control).toList());
        assertEquals("-x", now.forward(), "拆前 +z 朝西，组装后结构的轴就是世界轴");
        assertTrue(result.summary().contains("档案跟到新结构 s7"), result.summary());
        assertEquals(VehicleArchives.NEW_VEHICLE, archive(run(true, new VehicleAssembler.Conversion(false, true, "ship-3", 49,
                "组装", ASSEMBLER, edited), null)).state(), "拆回记录用过就删");
    }

    @Test
    void 方块对不上照新载具_旧档案不搬() {
        disassembleOldShip();
        VehicleArchives.Handover outcome = archive(run(true, new VehicleAssembler.Conversion(false, true, "ship-9", 51, "组装",
                ASSEMBLER, Map.of("minecraft:stone", 50, "simulated:physics_assembler", 1)), null));
        assertEquals(VehicleArchives.NEW_VEHICLE, outcome.state());
        assertTrue(outcome.note().contains("不到一半"), outcome.note());
        assertEquals("ship-9", outcome.structure(), "还没领到 s# 就写结构身份");
        assertEquals(VehicleControls.Stored.EMPTY, controls.stored("ship-9"));
    }

    @Test
    void 没拆回过照新载具_车站组车不谈档案() {
        assertEquals(VehicleArchives.NEW_VEHICLE, archive(run(true, new VehicleAssembler.Conversion(false, true, "ship-5", 46,
                "组装", ASSEMBLER, SHIP), null)).state());
        assertNull(archive(run(true, new VehicleAssembler.Conversion(false, true, "train-1", 3, "组车"), null)));
    }

    @Test
    void 找不到组装器落点就留在原身份下() {
        views.put("ship-1", view("ship-1", 0, input("打字机:W", "a + b", "aeronautics:propeller_bearing")));
        controls.tested("ship-1", List.of(new VehicleControls.Entry("打字机:W", "power", VehicleControls.TESTED, "试的")), "+z");
        VehicleArchives.Handover outcome = archive(run(false, new VehicleAssembler.Conversion(false, true, "ship-1", -1, "拆回",
                null, SHIP), "ship-1"));
        assertEquals(VehicleArchives.KEPT, outcome.state());
        assertEquals("+z", controls.stored("ship-1").forward(), "没搬就不删");
    }
}
