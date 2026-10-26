// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.vehicle.api.VehicleControls;

/**
 * 拆回再组装的档案怎么跟：车头按结构此刻的朝向换成世界轴；方块清单一半以上对得上才认作同一艘，增减照实列；
 * 回路逐个输入比；试过的角色接线一变就不带，LLM 定的照带，输入没了的都不带。
 */
class ReassemblyTest {

    @Test
    void 车头按结构此刻的朝向就近换成世界轴() {
        assertEquals("+z", Reassembly.worldAxis("+z", 0), "没转：结构的 +z 就是世界的 +z");
        assertEquals("-x", Reassembly.worldAxis("+z", 90), "+z 朝西");
        assertEquals("+z", Reassembly.worldAxis("+x", 90), "转了 90°，+x 朝南");
        assertEquals("-z", Reassembly.worldAxis("+z", 176.4), "差几度就近取整 90°");
        assertEquals("+x", Reassembly.worldAxis("-x", 180));
        assertEquals("+x", Reassembly.worldAxis("+z", 271), "朝东");
        assertEquals("", Reassembly.worldAxis("", 90), "车头没定原样空着");
    }

    @Test
    void 方块清单一半以上对得上认作同一艘_增减照实列() {
        Map<String, Integer> before = Map.of("minecraft:oak_planks", 40, "simulated:physics_assembler", 1,
                "aeronautics:propeller_bearing", 2);
        Reassembly.Verdict edited = Reassembly.match(before, Map.of("minecraft:oak_planks", 40,
                "simulated:physics_assembler", 1, "aeronautics:propeller_bearing", 1, "minecraft:iron_block", 4));
        assertTrue(edited.same());
        assertEquals(42, edited.shared());
        assertEquals(List.of("少了 aeronautics:propeller_bearing×1", "多了 minecraft:iron_block×4"), edited.changes());

        Reassembly.Verdict other = Reassembly.match(before, Map.of("minecraft:stone", 50, "simulated:physics_assembler", 1));
        assertFalse(other.same(), "只有组装器对得上是另一艘");
        assertTrue(other.changes().isEmpty(), "认不出时不列增减");

        assertFalse(Reassembly.match(Map.of(), before).same(), "拆前清单没读到认不出");
    }

    @Test
    void 回路逐个输入比_多了没了接线变了分开说() {
        List<Reassembly.Circuit> before = List.of(
                new Reassembly.Circuit("打字机:W", "a + b", List.of("aeronautics:propeller_bearing")),
                new Reassembly.Circuit("打字机:空格", "a + c", List.of("aeronautics:adjustable_burner")),
                new Reassembly.Circuit("打字机:A", "a + d", List.of("simulated:torsion_spring")));
        List<Reassembly.Circuit> after = List.of(
                new Reassembly.Circuit("打字机:W", "a + b", List.of("aeronautics:propeller_bearing")),
                new Reassembly.Circuit("打字机:空格", "a + e", List.of("aeronautics:adjustable_burner")),
                new Reassembly.Circuit("打字机:D", "a + f", List.of("simulated:torsion_spring")));
        Reassembly.CircuitChange change = Reassembly.circuits(before, after);
        assertEquals(Set.of("打字机:空格"), change.changed());
        assertEquals(Set.of("打字机:A"), change.gone());
        assertEquals(List.of("打字机:空格 的频率从 a + c 换成 a + e", "没了 打字机:A",
                "新多了 打字机:D（接着 simulated:torsion_spring）"), change.lines());
    }

    @Test
    void 试过的接线一变不带_LLM定的照带_输入没了都不带() {
        VehicleControls.Stored stored = new VehicleControls.Stored(List.of(
                new VehicleControls.Entry("打字机:W", "power", VehicleControls.TESTED, "试的"),
                new VehicleControls.Entry("打字机:空格", "lift", VehicleControls.TESTED, "试的"),
                new VehicleControls.Entry("打字机:S", "reverse", VehicleControls.ASSIGNED, "LLM 定的"),
                new VehicleControls.Entry("打字机:A", "yaw_left", VehicleControls.ASSIGNED, "LLM 定的")), "-x", "tested");
        Reassembly.CircuitChange change = new Reassembly.CircuitChange(List.of(),
                Set.of("打字机:空格", "打字机:S"), Set.of("打字机:A"));
        Reassembly.Carried carried = Reassembly.carry(stored, change);
        assertEquals(List.of("打字机:W", "打字机:S"),
                carried.stored().entries().stream().map(VehicleControls.Entry::control).toList());
        assertEquals("-x", carried.stored().forward(), "车头照带");
        assertEquals(List.of("打字机:空格 试过的 lift（接线变了，要重新试按）", "打字机:A 的 yaw_left（这个输入没了）"),
                carried.dropped());
    }
}
