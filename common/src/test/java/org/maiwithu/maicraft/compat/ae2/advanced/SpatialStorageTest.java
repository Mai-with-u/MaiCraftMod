// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.advanced;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.compat.ae2.advanced.Ae2AdvancedReads.SpatialAnchorCell;
import org.maiwithu.maicraft.compat.ae2.advanced.Ae2AdvancedReads.SpatialAnchorMenuStatus;
import org.maiwithu.maicraft.compat.ae2.advanced.Ae2AdvancedReads.SpatialCellSpec;
import org.maiwithu.maicraft.compat.ae2.advanced.Ae2AdvancedReads.SpatialMenuStatus;
import org.maiwithu.maicraft.compat.ae2.advanced.Ae2AdvancedReads.SpatialPylonCell;
import org.maiwithu.maicraft.compat.ae2.advanced.Ae2AdvancedReads.SpatialTransferCell;

/** 空间存储离线测试：覆盖空间塔公式与读数、空间传输站未切换原因诊断、空间锚能耗与多维统计。 */
class SpatialStorageTest {

    @Test
    void 空间塔需求块数效率与切换总能耗严格对齐AE2公式() {
        // 最小内腔 2×2×2 -> 外包跨度 3×3×3：surface = 27，27*3/8 = 10 >= 6。
        assertEquals(10, SpatialPylons.requiredPylonBlocksForInner(2, 2, 2));
        // 更小内腔 1×1×1 -> 外包跨度 2×2×2：surface = 12，12*3/8 = 4 -> 兜底 6。
        assertEquals(6, SpatialPylons.requiredPylonBlocksForInner(1, 1, 1));

        assertEquals(1.0, SpatialPylons.efficiency(12, 10), 1e-6);
        assertEquals(0.5, SpatialPylons.efficiency(5, 10), 1e-6);

        // 效率 1.0 时能耗 = 3*3*3*1250 = 33750；效率降到 0.5 时指数为 1.175，能耗明显高于 33750。
        long fullEffPower = SpatialPylons.requiredPowerForInner(2, 2, 2, 1.0);
        long halfEffPower = SpatialPylons.requiredPowerForInner(2, 2, 2, 0.5);
        assertEquals(33750L, fullEffPower);
        assertTrue(halfEffPower > fullEffPower);
    }

    @Test
    void 空间塔合并客户端同步态与服务端区域统计() {
        SpatialPylonCell client = new SpatialPylonCell(true, true, "y", "middle");
        JsonObject reply = new JsonObject();
        reply.addProperty("found", true);
        JsonObject readings = new JsonObject();
        readings.addProperty("valid_region", true);
        readings.addProperty("x_size", 4);
        readings.addProperty("y_size", 4);
        readings.addProperty("z_size", 4);
        readings.addProperty("pylon_blocks", 28);
        readings.addProperty("required_pylon_blocks", 28);
        readings.addProperty("efficiency", 100.0);
        readings.addProperty("required_power", 156250L);
        reply.add("readings", readings);

        MachineState state = SpatialPylons.composeState(client, Optional.of(reply));
        assertEquals(MachineState.Activity.RUNNING, state.activity());
        assertEquals("y", state.readings().get("axis"));
        assertEquals("middle", state.readings().get("axis_position"));
        assertEquals(4L, state.readings().get("x_size"));
        assertEquals(156250L, state.readings().get("required_power"));
        assertTrue(state.note().contains("4×4×4"));
    }

    @Test
    void 空间传输站如实诊断尺寸超限锁定尺寸冲突与缺电而不伪造成功() {
        SpatialCellSpec lockedCell = new SpatialCellSpec("ae2:spatial_storage_cell_2", 2, 7, 2, 2, 2);
        SpatialMenuStatus status = new SpatialMenuStatus(
                10000.0, 50000.0, 80000.0, 75.0,
                4, 4, 4,
                Optional.of(lockedCell), Optional.empty());

        List<String> diagnosis = SpatialTransfer.diagnoseUnchanged(Optional.of(status), Optional.of(lockedCell), true);
        String joined = String.join("；", diagnosis);
        assertTrue(joined.contains("4x4x4"));
        assertTrue(joined.contains("电量不足"));
        assertTrue(joined.contains("上限 2"));
        assertTrue(joined.contains("锁定尺寸 2x2x2"));

        MachineState state = SpatialTransfer.composeState(new SpatialTransferCell(true),
                Optional.of(status), Optional.empty());
        assertEquals("4x4x4", state.readings().get("region_size"));
        assertEquals("ae2:spatial_storage_cell_2", state.readings().get("input_cell"));
        assertEquals("2x2x2", state.readings().get("input_plot_size"));
        assertEquals("empty", state.readings().get("output_cell"));
    }

    @Test
    void 空间锚能耗严格等于80加三角数并合并多维加载统计() {
        assertEquals(80L, SpatialAnchor.powerConsumption(0));
        assertEquals(81L, SpatialAnchor.powerConsumption(1));
        assertEquals(90L, SpatialAnchor.powerConsumption(4));
        assertEquals(125L, SpatialAnchor.powerConsumption(9));

        SpatialAnchorMenuStatus menuStatus = new SpatialAnchorMenuStatus(125L, 9, "yes", 2, 15, 2, 20);
        MachineState state = SpatialAnchor.composeState(new SpatialAnchorCell(true, 9),
                Optional.of(menuStatus), Optional.empty());
        assertEquals(MachineState.Activity.RUNNING, state.activity());
        assertEquals(125L, state.readings().get("power_consumption"));
        assertEquals(9L, state.readings().get("loaded_chunks"));
        assertEquals("yes", state.readings().get("overlay_mode"));
        assertEquals(2L, state.readings().get("all_loaded_worlds"));
        assertEquals(15L, state.readings().get("all_loaded_chunks"));
    }
}
