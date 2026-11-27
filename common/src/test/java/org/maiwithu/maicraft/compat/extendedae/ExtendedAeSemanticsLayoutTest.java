// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.extendedae;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.compat.ModApiMismatch;
import org.maiwithu.maicraft.compat.VerifiedVersions;

/**
 * ExtendedAE 语义界面布局证明与版本范围边界测试：
 * 覆盖全部 25 个菜单注册 ID、网络工具箱 9 格动态偏移、排除 CONFIG 与储罐槽/通用槽、
 * 分页界面仅暴露启用页槽位、非 36 格角色背包拒绝，以及 VerifiedVersions("1.21-2.2.33", "1.21-2.3") 边界核对。
 */
class ExtendedAeSemanticsLayoutTest {

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

    private static ExtendedAeReads stubReads(Map<String, List<Integer>> enabled, Map<String, List<Integer>> all) {
        return new ExtendedAeReads() {
            @Override public boolean isMenuOpen() { return true; }
            @Override public List<Integer> enabledSlotsOf(String semanticId) {
                return enabled.getOrDefault(semanticId, List.of());
            }
            @Override public List<Integer> allSlotsOf(String semanticId) {
                return all.getOrDefault(semanticId, enabled.getOrDefault(semanticId, List.of()));
            }
        };
    }

    @Test
    void crystalAssemblerExcludesToolboxAndStorageTankSlot() {
        // 携带网络工具箱时前方动态插入 9 格 TOOLBOX（0..8），36 格背包在 9..44，容器槽在 45..59。
        Map<String, List<Integer>> enabled = Map.of(
                "TOOLBOX", range(0, 8),
                "MACHINE_INPUT", range(45, 53),
                "STORAGE", List.of(54),
                "MACHINE_OUTPUT", List.of(55),
                "UPGRADE", range(56, 59));
        ExtendedAeSemanticsLayout layout = new ExtendedAeSemanticsLayout(stubReads(enabled, enabled));
        List<Integer> other = new ArrayList<>(range(0, 8));
        other.addAll(range(45, 59));

        MenuLayout.Layout classified = layout.classify(
                menuOf("extendedae:crystal_assembler", 60, 9), range(9, 44), other);
        MenuLayout.Supported supported = assertInstanceOf(MenuLayout.Supported.class, classified);
        assertEquals(36, supported.playerSlots().size());
        assertFalse(supported.containerSlots().contains(54), "储罐 STORAGE 槽严禁暴露给普通物品搬运");
        for (int tb = 0; tb <= 8; tb++) {
            assertFalse(supported.containerSlots().contains(tb), "TOOLBOX 槽严禁暴露给普通搬运");
        }
        assertEquals(14, supported.containerSlots().size(), "9 输入 + 1 输出 + 4 升级 = 14 格");
        assertEquals(Set.copyOf(supported.containerSlots()), supported.machineSlots());
    }

    @Test
    void exInscriberAndExInterfaceExposeOnlyEnabledPageSlotsAndExcludeConfig() {
        // 扩展压印器：仅第 1 页（EX_1/EX_4/EX_7/EX_10）处于启用态，第 0 页被禁用。
        Map<String, List<Integer>> inscriberEnabled = Map.of(
                "EX_1", List.of(40),
                "EX_4", List.of(41),
                "EX_7", List.of(42),
                "EX_10", List.of(43),
                "UPGRADE", List.of(44, 45, 46, 47));
        Map<String, List<Integer>> inscriberAll = Map.of(
                "INSCRIBER_PLATE_TOP", List.of(36),
                "EX_1", List.of(40),
                "EX_4", List.of(41),
                "EX_7", List.of(42),
                "EX_10", List.of(43),
                "UPGRADE", List.of(44, 45, 46, 47));
        ExtendedAeSemanticsLayout inscriberLayout = new ExtendedAeSemanticsLayout(
                stubReads(inscriberEnabled, inscriberAll));
        MenuLayout.Supported inscriberSupported = assertInstanceOf(MenuLayout.Supported.class,
                inscriberLayout.classify(menuOf("extendedae:ex_inscriber", 48, 0), range(0, 35), range(36, 47)));
        assertEquals(List.of(40, 41, 42, 43, 44, 45, 46, 47), inscriberSupported.containerSlots());
        assertFalse(inscriberSupported.containerSlots().contains(36), "未启用页槽位不得暴露");

        // 扩展接口：EX_1 为幽灵配置槽必须排除，仅暴露当前页存储槽 EX_2 与升级槽 UPGRADE（仅 UPGRADE 标为机器槽）。
        Map<String, List<Integer>> ifaceEnabled = Map.of(
                "EX_1", range(36, 44),
                "EX_2", range(45, 53),
                "UPGRADE", List.of(54, 55));
        ExtendedAeSemanticsLayout ifaceLayout = new ExtendedAeSemanticsLayout(stubReads(ifaceEnabled, ifaceEnabled));
        MenuLayout.Supported ifaceSupported = assertInstanceOf(MenuLayout.Supported.class,
                ifaceLayout.classify(menuOf("extendedae:ex_interface", 56, 0), range(0, 35), range(36, 55)));
        assertEquals(11, ifaceSupported.containerSlots().size());
        assertFalse(ifaceSupported.containerSlots().contains(36), "幽灵配置槽 EX_1 不得暴露");
        assertEquals(Set.of(54, 55), ifaceSupported.machineSlots());
    }

    @Test
    void canerAndAllThirteenCablePartMenusCoveredAndNon36PlayerRejected() {
        ExtendedAeSemanticsLayout layout = new ExtendedAeSemanticsLayout(stubReads(
                Map.of("EX_1", List.of(36), "EX_2", List.of(37), "CONFIG", List.of(38), "UPGRADE", List.of(39)),
                Map.of("EX_1", List.of(36), "EX_2", List.of(37), "CONFIG", List.of(38), "UPGRADE", List.of(39))));

        // 装罐机只暴露容器槽 EX_2，排除流体通用槽 EX_1。
        MenuLayout.Supported caner = assertInstanceOf(MenuLayout.Supported.class,
                layout.classify(menuOf("extendedae:caner", 40, 0), range(0, 35), range(36, 39)));
        assertEquals(List.of(37), caner.containerSlots());
        assertEquals(Set.of(37), caner.machineSlots());

        // 全部 13 种扩展线缆部件菜单均已登记且在有/无升级槽时均可安全放行并排除 CONFIG。
        List<String> partMenus = List.of(
                "extendedae:ex_pattern_access_terminal",
                "extendedae:ex_export_bus",
                "extendedae:ex_import_bus",
                "extendedae:tag_export_bus",
                "extendedae:tag_storage_bus",
                "extendedae:mod_export_bus",
                "extendedae:mod_storage_bus",
                "extendedae:precise_export_bus",
                "extendedae:precise_storage_bus",
                "extendedae:threshold_export_bus",
                "extendedae:threshold_level_emitter",
                "extendedae:active_formation_plane",
                "extendedae:smart_annihilation_plane");
        for (String menuId : partMenus) {
            assertTrue(layout.menuTypes().contains(menuId), "缺少部件菜单规则：" + menuId);
            MenuLayout.Supported ok = assertInstanceOf(MenuLayout.Supported.class,
                    layout.classify(menuOf(menuId, 40, 0), range(0, 35), range(36, 39)));
            assertFalse(ok.containerSlots().contains(38), "部件菜单不得暴露 CONFIG：" + menuId);
        }

        // 组装矩阵纯配置界面双空放行，但缺少完整 36 格背包时一律拒绝。
        MenuLayout.Supported matrix = assertInstanceOf(MenuLayout.Supported.class,
                layout.classify(menuOf("extendedae:assembler_matrix", 36, 0), range(0, 35), List.of()));
        assertTrue(matrix.containerSlots().isEmpty());
        assertInstanceOf(MenuLayout.Unsupported.class,
                layout.classify(menuOf("extendedae:assembler_matrix", 20, 0), range(0, 19), List.of()));
        assertInstanceOf(MenuLayout.Unsupported.class,
                layout.classify(menuOf("extendedae:crystal_assembler", 20, 0), range(0, 19), List.of()));
    }

    @Test
    void verifiedVersionsMatchesPrefixedExtendedAeRangeBoundaries() {
        VerifiedVersions range = new VerifiedVersions("1.21-2.2.33", "1.21-2.3");
        assertTrue(range.contains("1.21-2.2.33-neoforge"));
        assertTrue(range.contains("1.21-2.2.35-neoforge"));
        assertFalse(range.contains("1.21-2.2.32-neoforge"));
        assertFalse(range.contains("1.21-2.3.0-neoforge"));
        assertFalse(range.contains("2.2.33"));
    }

    @Test
    void compatModuleDisablesItselfOnLinkageError() {
        ExtendedAeReads broken = new ExtendedAeReads() {
            @Override public boolean isMenuOpen() {
                throw new NoSuchMethodError("broken extendedae method");
            }
        };
        ExtendedAeCompat compat = new ExtendedAeCompat(broken);
        assertThrows(ModApiMismatch.class, compat::isMenuOpen);
        assertFalse(compat.active());
    }
}
