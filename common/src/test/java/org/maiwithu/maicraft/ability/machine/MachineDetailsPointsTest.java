// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 看机器的结果指着哪几处：一格的看那一格，几格合成的看中间，部件看它装的那一格，最多五台。 */
class MachineDetailsPointsTest {

    private static MachineDetails.DeviceView device(String id, BlockPos at, MachineDetails.Box box, BlockPos host) {
        return new MachineDetails.DeviceView(id, "create:mechanical_press", "processor", at, box == null ? null : 4, box,
                host, host == null ? null : Direction.UP, null, null, null, List.of(), List.of());
    }

    private static MachineDetails details(List<MachineDetails.DeviceView> devices) {
        return new MachineDetails(List.of(), devices, List.of(), List.of(), 0, true, null, null, "", null);
    }

    @Test
    void 一格的看那一格_几格合成的看中间_部件看装的那一格() {
        MachineDetails details = details(List.of(
                device("d1", new BlockPos(1, 64, 2), null, null),
                device("d2", null, new MachineDetails.Box(new BlockPos(10, 64, 10), new BlockPos(12, 66, 12)), null),
                device("d3", null, null, new BlockPos(-3, 65, 4))));
        assertEquals(List.of(WorldPosition.here(1, 64, 2), WorldPosition.here(11, 65, 11), WorldPosition.here(-3, 65, 4)),
                details.pointsAt());
        assertTrue(details.sweep(), "一片机器从左到右扫过");
    }

    @Test
    void 一大片也只扫五台() {
        List<MachineDetails.DeviceView> devices = new ArrayList<>();
        for (int i = 0; i < 9; i++) devices.add(device("d" + i, new BlockPos(i, 64, 0), null, null));
        assertEquals(5, details(devices).pointsAt().size());
    }
}
