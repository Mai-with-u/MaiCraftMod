// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 从部件推角色与种类：接的是同一类才定，接了几样、隔着离合器、没有收端都说未定；种类按车轮、浮力、水、螺旋桨推。 */
class VehiclePartsTest {

    @Test
    void 收端旁边接着同一类执行件才定角色() {
        assertEquals("power", VehicleParts.role(2, List.of("aeronautics:propeller_bearing",
                "aeronautics:propeller_bearing")).role());
        assertEquals("lift", VehicleParts.role(1, List.of("aeronautics:adjustable_burner")).role());
        VehicleParts.Role steer = VehicleParts.role(1, List.of("simulated:torsion_spring"));
        assertEquals("steer", steer.role());
        assertTrue(steer.basis().contains("左右"), steer.basis());
    }

    @Test
    void 推不出就说未定() {
        assertEquals("unknown", VehicleParts.role(0, List.of()).role());
        assertEquals("unknown", VehicleParts.role(1, List.of()).role());
        assertEquals("unknown", VehicleParts.role(1, List.of("create:clutch")).role());
        VehicleParts.Role mixed = VehicleParts.role(2, List.of("aeronautics:propeller_bearing", "simulated:torsion_spring"));
        assertEquals("unknown", mixed.role());
        assertTrue(mixed.basis().contains("螺旋桨轴承") && mixed.basis().contains("扭力弹簧"), mixed.basis());
    }

    @Test
    void 种类按部件推() {
        List<String> airship = List.of(StructureGeometry.TYPEWRITER, "aeronautics:white_envelope",
                "aeronautics:propeller_bearing");
        assertEquals("airship", VehicleParts.kind(true, 1, airship, false, false));
        assertEquals("airship", VehicleParts.kind(true, 1, airship, true, false), "有浮力又有机翼按飞艇开");
        assertEquals("unknown", VehicleParts.kind(false, 1, airship, false, false), "没读全说不清");
        assertEquals("structure", VehicleParts.kind(true, 0, airship, false, false), "没有座位只是结构");
        assertEquals("car", VehicleParts.kind(true, 1, List.of(StructureGeometry.TYPEWRITER, "offroad:wheel_mount"),
                false, false));
        List<String> propeller = List.of(StructureGeometry.TYPEWRITER, "aeronautics:propeller_bearing");
        assertEquals("boat", VehicleParts.kind(true, 1, propeller, false, true));
        assertEquals("unknown", VehicleParts.kind(true, 1, propeller, false, false), "只有推进没有机翼说不清");
    }

    @Test
    void 有机翼又有推进是固定翼_带车轮也是() {
        List<String> propeller = List.of(StructureGeometry.TYPEWRITER, "aeronautics:propeller_bearing");
        assertEquals("plane", VehicleParts.kind(true, 1, propeller, true, false));
        assertEquals("plane", VehicleParts.kind(true, 1, List.of(StructureGeometry.TYPEWRITER,
                "aeronautics:propeller_bearing", "offroad:wheel_mount"), true, false), "车轮当起落架");
        assertEquals("plane", VehicleParts.kind(true, 1, propeller, true, true), "浮在水上的固定翼");
        assertEquals("car", VehicleParts.kind(true, 1, List.of(StructureGeometry.TYPEWRITER, "offroad:wheel_mount"),
                true, false), "有风帆没有螺旋桨还是车");
    }

    @Test
    void 执行件按次数归并() {
        assertEquals(List.of("aeronautics:propeller_bearing×2", "simulated:torsion_spring"), VehicleParts.grouped(
                List.of("aeronautics:propeller_bearing", "simulated:torsion_spring", "aeronautics:propeller_bearing")));
    }
}
