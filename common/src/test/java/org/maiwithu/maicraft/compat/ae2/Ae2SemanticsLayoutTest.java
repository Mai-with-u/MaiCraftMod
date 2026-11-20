// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.compat.ModApiMismatch;

/**
 * 声明式 AE2 槽位语义布局证明与 Ae2MenuSlots 入口测试：
 * 核对有序容器语义、机器槽标注、排除 TOOLBOX 与 CONFIG、纯配置界面双空规则，以及联动入口异常停用保护。
 */
class Ae2SemanticsLayoutTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static MenuSlots menuOf(String typeId, int totalSlots, int playerStart) {
        return new MenuSlots() {
            @Override public String menuTypeId() { return typeId; }
            @Override public int slotCount() { return totalSlots; }
            @Override public boolean playerBacked(int slot) { return slot >= playerStart && slot < playerStart + 36; }
            @Override public int playerInventoryIndex(int slot) { return slot - playerStart; }
        };
    }

    private static List<Integer> range(int from, int toInclusive) {
        List<Integer> values = new ArrayList<>();
        for (int i = from; i <= toInclusive; i++) values.add(i);
        return List.copyOf(values);
    }

    private static Ae2MenuSlots fakeSlots(Map<String, List<Integer>> semantics, List<String> sent) {
        return new Ae2MenuSlots() {
            @Override public boolean isAe2MenuOpen() { return true; }
            @Override public List<Integer> slotsOf(String semanticId) {
                return semantics.getOrDefault(semanticId, List.of());
            }
            @Override public boolean sendConfigButton(String settingName, boolean backwards) {
                sent.add("config:" + settingName + ":" + backwards);
                return true;
            }
            @Override public boolean sendSetFilter(int slotIndex, ItemStack stack) {
                sent.add("filter:" + slotIndex + ":" + stack.isEmpty());
                return true;
            }
            @Override public boolean sendNoArgAction(String actionName) {
                sent.add("action:" + actionName);
                return true;
            }
            @Override public boolean sendIntAction(String actionName, int value) {
                sent.add("int:" + actionName + ":" + value);
                return true;
            }
            @Override public boolean sendLongAction(String actionName, long value) {
                sent.add("long:" + actionName + ":" + value);
                return true;
            }
            @Override public boolean sendBooleanAction(String actionName, boolean value) {
                sent.add("bool:" + actionName + ":" + value);
                return true;
            }
            @Override public boolean sendStringAction(String actionName, String value) {
                sent.add("str:" + actionName + ":" + value);
                return true;
            }
            @Override public boolean sendRawJsonAction(String actionName, String jsonPayload) {
                sent.add("json:" + actionName + ":" + jsonPayload);
                return true;
            }
            @Override public boolean openSubMenu(String menuTypeId) {
                sent.add("sub:" + menuTypeId);
                return true;
            }
            @Override public boolean returnToParentMenu() {
                sent.add("return");
                return true;
            }
        };
    }

    @Test
    void 按声明语义顺序输出容器槽并自动跳过网络工具箱与幽灵过滤槽() {
        // 模拟携带网络工具箱的界面：0..8 TOOLBOX，9..11 UPGRADE，12..20 CONFIG，21..56 玩家背包，57 MACHINE_INPUT。
        Map<String, List<Integer>> semantics = Map.of(
                "TOOLBOX", range(0, 8),
                "UPGRADE", List.of(9, 10, 11),
                "CONFIG", range(12, 20),
                "MACHINE_INPUT", List.of(57));
        Ae2SemanticsLayout layout = new Ae2SemanticsLayout(
                fakeSlots(semantics, new ArrayList<>()),
                Map.of("ae2:vibrationchamber",
                        new Ae2SemanticsLayout.Rule(List.of("MACHINE_INPUT", "UPGRADE"), Set.of("MACHINE_INPUT"))));

        List<Integer> other = new ArrayList<>();
        other.addAll(range(0, 20));
        other.add(57);

        MenuLayout.Layout classified = layout.classify(menuOf("ae2:vibrationchamber", 58, 21), range(21, 56), other);
        MenuLayout.Supported supported = assertInstanceOf(MenuLayout.Supported.class, classified);
        assertEquals(List.of(57, 9, 10, 11), supported.containerSlots(),
                "按 MACHINE_INPUT 在前、UPGRADE 在后排好，自动跳过 TOOLBOX 与 CONFIG");
        assertEquals(Set.of(57), supported.machineSlots(), "仅声明为机器槽的语义进入 machineSlots");
        assertEquals(36, supported.playerSlots().size());
    }

    @Test
    void 规则禁止声明幽灵过滤槽或网络工具箱_纯配置界面支持双空规则() {
        assertThrows(IllegalArgumentException.class,
                () -> new Ae2SemanticsLayout.Rule(List.of("CONFIG"), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Ae2SemanticsLayout.Rule(List.of("TOOLBOX"), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Ae2SemanticsLayout.Rule(List.of("STORAGE"), Set.of("CONFIG")));

        Ae2SemanticsLayout layout = new Ae2SemanticsLayout(
                Ae2MenuSlots.NONE,
                Map.of("ae2:energy_level_emitter", Ae2SemanticsLayout.Rule.configOnly(),
                        "ae2:condenser", new Ae2SemanticsLayout.Rule(List.of("MACHINE_INPUT"), Set.of("MACHINE_INPUT"))));

        MenuLayout.Supported configOnly = assertInstanceOf(MenuLayout.Supported.class,
                layout.classify(menuOf("ae2:energy_level_emitter", 45, 9), range(9, 44), range(0, 8)));
        assertTrue(configOnly.containerSlots().isEmpty());
        assertTrue(configOnly.machineSlots().isEmpty());
        assertEquals(36, configOnly.playerSlots().size());

        // 非纯配置规则在读不到任何语义槽时如实返回不支持。
        assertInstanceOf(MenuLayout.Unsupported.class,
                layout.classify(menuOf("ae2:condenser", 39, 3), range(3, 38), range(0, 2)));
        assertInstanceOf(MenuLayout.Unsupported.class,
                layout.classify(menuOf("ae2:unknown_menu", 36, 0), range(0, 35), List.of()));
    }

    @Test
    void 经联动入口调用通用菜单语义发包并在接口对不上时自动停用() {
        List<String> sent = new ArrayList<>();
        Ae2MenuSlots slots = fakeSlots(Map.of("UPGRADE", List.of(9, 10)), sent);
        Ae2Terminals noTerminals = new Ae2Terminals() {
            @Override public List<Terminal> near(ClientLevel level, BlockPos center, int radius) { return List.of(); }
            @Override public boolean stillThere(ClientLevel level, BlockPos block, Direction side) { return false; }
        };
        Ae2TerminalMenu noMenu = new Ae2TerminalMenu() {
            @Override public boolean belongsTo(AbstractContainerMenu menu, ClientLevel level, BlockPos block, Direction side) {
                return false;
            }
            @Override public Optional<Boolean> linked(AbstractContainerMenu menu) { return Optional.empty(); }
            @Override public Optional<List<StockEntry>> stock(AbstractContainerMenu menu) { return Optional.empty(); }
            @Override public void takeStack(AbstractContainerMenu menu, long serial) {}
            @Override public void takeOne(AbstractContainerMenu menu, long serial) {}
            @Override public void putBack(AbstractContainerMenu menu) {}
        };
        Ae2Compat compat = new Ae2Compat(noTerminals, noMenu, new NoGrid(), Ae2Patterns.NONE,
                Ae2Transforms.NONE, Ae2Inscribers.NONE, slots);

        assertTrue(compat.ae2MenuOpen());
        assertEquals(List.of(9, 10), compat.menuSlotsOf("UPGRADE"));
        assertTrue(compat.sendConfigButton("auto_export", false));
        assertTrue(compat.sendSetFilter(12, new ItemStack(Items.IRON_INGOT)));
        assertTrue(compat.sendMenuAction("clear"));
        assertTrue(compat.sendMenuIntAction("setPriority", 5));
        assertTrue(compat.sendMenuLongAction("setReportingValue", 1000L));
        assertTrue(compat.sendMenuBooleanAction("setSubstitution", true));
        assertTrue(compat.sendMenuStringAction("setName", "press"));
        assertTrue(compat.sendMenuJsonAction("setMode", "\"PROCESSING\""));
        assertTrue(compat.openSubMenu("ae2:priority"));
        assertTrue(compat.returnToParentMenu());
        assertEquals(10, sent.size());

        // 读写端抛 LinkageError 时联动入口收住并停用整个 AE2 模块。
        Ae2MenuSlots broken = new Ae2MenuSlots() {
            @Override public boolean isAe2MenuOpen() { throw new NoSuchMethodError("AEBaseMenu.getSlots"); }
            @Override public List<Integer> slotsOf(String semanticId) { return List.of(); }
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
        Ae2Compat brokenCompat = new Ae2Compat(noTerminals, noMenu, new NoGrid(), Ae2Patterns.NONE,
                Ae2Transforms.NONE, Ae2Inscribers.NONE, broken);
        assertThrows(ModApiMismatch.class, brokenCompat::ae2MenuOpen);
        assertFalse(brokenCompat.active());
    }
}
