// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.advanced;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.compat.ae2.Ae2MachineCatalog;
import org.maiwithu.maicraft.compat.ae2.Ae2MenuSlots;
import org.maiwithu.maicraft.compat.ae2.Ae2SemanticsLayout;
import org.maiwithu.maicraft.server.compat.ae2.Ae2DeviceServerReads;

/** AE2 高级机制汇编入口测试：核对设备目录增补、语义菜单白名单、排除空间锚与服务端操作名一致性。 */
class Ae2AdvancedWiringTest {

    /** 既有已注册的 AE2 菜单 ID，本组新增布局规则绝不可与其撞名。 */
    private static final Set<String> EXISTING_MENU_IDS = Set.of(
            "ae2:item_terminal", "ae2:craftingterm", "ae2:patternterm",
            "ae2:wirelessterm", "ae2:wirelesscraftingterm", "ae2:pattern_provider",
            "ae2:wireless_access_point", "ae2:inscriber", "ae2:drive", "ae2:skychest");

    @Test
    void 增补设备目录包含空间三件套且空间传输站名称正确() {
        List<Ae2MachineCatalog.Kind> extra = Ae2Advanced.extraKinds();
        assertEquals(3, extra.size());
        Map<String, Ae2MachineCatalog.Kind> byId = Map.of(
                extra.get(0).id(), extra.get(0),
                extra.get(1).id(), extra.get(1),
                extra.get(2).id(), extra.get(2));
        assertTrue(byId.containsKey("ae2:spatial_pylon"));
        assertEquals("空间传输站", byId.get("ae2:spatial_io_port").name());
        assertTrue(byId.containsKey("ae2:spatial_anchor"));

        List<Ae2MachineCatalog.Kind> merged = Ae2MachineCatalog.kinds(extra);
        assertEquals(Ae2MachineCatalog.kinds().size() + 3, merged.size());
    }

    @Test
    void 菜单语义布局只登记含36格背包的本组独占菜单且严禁登记空间锚() {
        Map<String, Ae2SemanticsLayout.Rule> rules = Ae2Advanced.layoutRules();
        assertEquals(Set.of("ae2:qnb", "ae2:spatialioport"), rules.keySet());
        assertFalse(rules.containsKey("ae2:spatialanchor"));
        for (String id : rules.keySet()) {
            assertFalse(EXISTING_MENU_IDS.contains(id), "菜单 ID 与既有证明冲突：" + id);
        }

        // 模拟携带网络工具箱时（前 9 格为 TOOLBOX），ae2:spatialioport 依然精准识别槽 9 与槽 10。
        Ae2MenuSlots fakeSlots = new Ae2MenuSlots() {
            @Override public boolean isAe2MenuOpen() { return true; }
            @Override public List<Integer> slotsOf(String semanticId) {
                return switch (semanticId) {
                    case "TOOLBOX" -> List.of(0, 1, 2, 3, 4, 5, 6, 7, 8);
                    case "MACHINE_INPUT" -> List.of(9);
                    case "MACHINE_OUTPUT" -> List.of(10);
                    default -> List.of();
                };
            }
            @Override public boolean sendConfigButton(String settingName, boolean backwards) { return false; }
            @Override public boolean sendSetFilter(int slotIndex, ItemStack stack) { return false; }
            @Override public boolean sendNoArgAction(String actionName) { return false; }
            @Override public boolean sendIntAction(String actionName, int value) { return false; }
            @Override public boolean sendLongAction(String actionName, long value) { return false; }
            @Override public boolean sendBooleanAction(String actionName, boolean value) { return false; }
            @Override public boolean sendStringAction(String actionName, String value) { return false; }
            @Override public boolean sendRawJsonAction(String actionName, String jsonPayload) { return false; }
            @Override public boolean openSubMenu(String menuTypeId) { return false; }
            @Override public boolean returnToParentMenu() { return false; }
        };

        Ae2SemanticsLayout layout = new Ae2SemanticsLayout(fakeSlots, rules);
        MenuSlots slots = new MenuSlots() {
            @Override public String menuTypeId() { return "ae2:spatialioport"; }
            @Override public int slotCount() { return 47; }
            @Override public boolean playerBacked(int slot) { return slot >= 11 && slot < 47; }
            @Override public int playerInventoryIndex(int slot) { return slot - 11; }
        };
        List<Integer> playerSlots = IntStream.range(11, 47).boxed().toList();
        List<Integer> otherSlots = IntStream.range(0, 11).boxed().toList();
        MenuLayout.Layout classified = layout.classify(slots, playerSlots, otherSlots);
        MenuLayout.Supported supported = assertInstanceOf(MenuLayout.Supported.class, classified);
        assertEquals(List.of(9, 10), supported.containerSlots());
        assertEquals(Set.of(9, 10), supported.machineSlots());
    }

    @Test
    void 客户端与服务端ae2点device只读操作名及版本号完全一致() {
        assertEquals(Ae2DeviceServerReads.OPERATION, Ae2Advanced.DEVICE_OPERATION);
        assertEquals(Ae2DeviceServerReads.VERSION, Ae2Advanced.DEVICE_OPERATION_VERSION);
    }
}
