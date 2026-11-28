// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.compat.ae2.Ae2Grid;

/**
 * AE2 驱动器十槽读数与 DriveMenu 布局证明的离线测试：核实十槽存储元件与指示灯状态完整透出、
 * 离线供电态如实标记，以及 DriveMenu 十槽加 36 格角色背包的布局证明边界。
 */
class Ae2DriveTest {

    private static MenuSlots driveMenuSlots(int containerCount) {
        int total = containerCount + 36;
        return new MenuSlots() {
            @Override public String menuTypeId() { return "ae2:drive"; }
            @Override public int slotCount() { return total; }
            @Override public boolean playerBacked(int slot) { return slot >= containerCount; }
            @Override public int playerInventoryIndex(int slot) { return slot - containerCount; }
        };
    }

    private static List<Integer> range(int from, int toInclusive) {
        List<Integer> values = new ArrayList<>();
        for (int i = from; i <= toInclusive; i++) values.add(i);
        return List.copyOf(values);
    }

    @Test
    void 驱动器界面恰好十个存储元件槽加三十六格角色背包_布局证明通过() {
        MenuLayout.Layout layout = new Ae2DriveLayout().classify(
                driveMenuSlots(10), range(10, 45), range(0, 9));
        MenuLayout.Supported supported = assertInstanceOf(MenuLayout.Supported.class, layout);
        assertEquals(range(0, 9), supported.containerSlots(), "十个存储元件槽按 0..9 顺序映射");
        assertEquals(36, supported.playerSlots().size());
        assertTrue(supported.machineSlots().isEmpty(), "驱动器元件槽按普通槽核对，放入后留在该格");
    }

    @Test
    void 驱动器界面槽位数量不对_证明不了布局() {
        assertInstanceOf(MenuLayout.Unsupported.class,
                new Ae2DriveLayout().classify(driveMenuSlots(8), range(8, 43), range(0, 7)));
    }

    @Test
    void 驱动器十槽元件与指示灯状态全部透出到状态读数() {
        List<Ae2Grid.DriveSlot> slots = new ArrayList<>();
        slots.add(new Ae2Grid.DriveSlot(0, "ae2:item_storage_cell_1k", "types_full"));
        slots.add(new Ae2Grid.DriveSlot(1, "ae2:item_storage_cell_4k", "not_empty"));
        for (int i = 2; i < 10; i++) {
            slots.add(new Ae2Grid.DriveSlot(i, null, "absent"));
        }
        MachineState state = Ae2MachineType.Ae2Readings.driveState(new Ae2Grid.DriveSlots(true, slots));
        assertEquals(MachineState.Activity.RUNNING, state.activity());
        assertEquals(2, state.shown().size(), "两块已装元件进入 shown 清单");
        assertEquals("ae2:item_storage_cell_1k", state.shown().get(0).itemId());
        assertEquals("ae2:item_storage_cell_4k", state.shown().get(1).itemId());
        assertEquals("ae2:item_storage_cell_1k (types_full)", state.readings().get("slot_0"));
        assertEquals("ae2:item_storage_cell_4k (not_empty)", state.readings().get("slot_1"));
        assertEquals("empty", state.readings().get("slot_9"));
        assertEquals(2, state.readings().get("occupied_slots"));
        assertEquals(8, state.readings().get("empty_slots"));
        assertEquals(10, state.readings().get("total_slots"));
        assertEquals(Boolean.TRUE, state.readings().get("online"));
        assertTrue(state.note().contains("2/10"), state.note());
    }

    @Test
    void 驱动器没电或离网时标为离线但仍透出槽内元件() {
        List<Ae2Grid.DriveSlot> slots = new ArrayList<>();
        slots.add(new Ae2Grid.DriveSlot(0, "ae2:item_storage_cell_4k", "empty"));
        for (int i = 1; i < 10; i++) {
            slots.add(new Ae2Grid.DriveSlot(i, null, "absent"));
        }
        MachineState state = Ae2MachineType.Ae2Readings.driveState(new Ae2Grid.DriveSlots(false, slots));
        assertEquals(MachineState.Activity.OFFLINE, state.activity());
        assertEquals(1, state.shown().size());
        assertEquals(Boolean.FALSE, state.readings().get("online"));
        assertTrue(state.note().contains("没电"), state.note());
    }
}
