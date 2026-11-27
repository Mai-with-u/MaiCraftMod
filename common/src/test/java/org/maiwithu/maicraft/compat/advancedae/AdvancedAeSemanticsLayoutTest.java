// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.advancedae;

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
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.compat.ModApiMismatch;
import org.maiwithu.maicraft.compat.VerifiedVersions;

/**
 * 高级 AE 语义界面布局证明与版本范围测试：
 * 覆盖反应仓 9 输入 + 1 输出 + 4 升级槽布局、随身网络工具箱 9 格偏移与幽灵槽排除、
 * 非 36 格角色背包（含输出方向子菜单）拒绝，以及联动入口 LinkageError 停用保护与验证版本范围。
 */
class AdvancedAeSemanticsLayoutTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static MenuSlots menuOf(String typeId, int totalSlots, int playerStart, int playerCount) {
        return new MenuSlots() {
            @Override public String menuTypeId() { return typeId; }
            @Override public int slotCount() { return totalSlots; }
            @Override public boolean playerBacked(int slot) {
                return slot >= playerStart && slot < playerStart + playerCount;
            }
            @Override public int playerInventoryIndex(int slot) { return slot - playerStart; }
        };
    }

    private static List<Integer> range(int from, int toInclusive) {
        List<Integer> values = new ArrayList<>();
        for (int i = from; i <= toInclusive; i++) values.add(i);
        return List.copyOf(values);
    }

    private static AdvancedAeReads fakeReads(Map<String, List<Integer>> semantics) {
        return new AdvancedAeReads() {
            @Override public Optional<ChamberView> chamberAt(ClientLevel level, BlockPos at) {
                return Optional.empty();
            }
            @Override public List<Integer> menuSlotsOf(String semanticId) {
                return semantics.getOrDefault(semanticId, List.of());
            }
            @Override public Optional<ChamberMenuView> openChamberMenu() { return Optional.empty(); }
            @Override public Optional<Set<String>> openOutputDirections() { return Optional.empty(); }
            @Override public boolean sendAutoExportToggle() { return false; }
            @Override public boolean sendClearFluidIn() { return false; }
            @Override public boolean sendClearFluidOut() { return false; }
            @Override public boolean sendOpenOutputDirection() { return false; }
            @Override public boolean sendToggleOutputSide(String relativeSide) { return false; }
            @Override public boolean sendReturnToParentMenu() { return false; }
        };
    }

    @Test
    void 反应仓标准布局识别九输入一输出四升级且全标为机器槽() {
        // 0..3 UPGRADE，4..39 角色背包 36 格，40..48 MACHINE_INPUT（9 格），49 MACHINE_OUTPUT（1 格）。
        Map<String, List<Integer>> semantics = Map.of(
                "UPGRADE", range(0, 3),
                "MACHINE_INPUT", range(40, 48),
                "MACHINE_OUTPUT", List.of(49));
        AdvancedAeSemanticsLayout layout = new AdvancedAeSemanticsLayout(fakeReads(semantics));

        List<Integer> other = new ArrayList<>();
        other.addAll(range(0, 3));
        other.addAll(range(40, 49));
        MenuLayout.Layout result = layout.classify(
                menuOf("advanced_ae:reaction_chamber", 50, 4, 36), range(4, 39), other);

        MenuLayout.Supported supported = assertInstanceOf(MenuLayout.Supported.class, result);
        assertEquals(36, supported.playerSlots().size());
        List<Integer> expectedContainer = new ArrayList<>();
        expectedContainer.addAll(range(40, 48));
        expectedContainer.add(49);
        expectedContainer.addAll(range(0, 3));
        assertEquals(expectedContainer, supported.containerSlots());
        assertEquals(Set.copyOf(expectedContainer), supported.machineSlots());
    }

    @Test
    void 携带网络工具箱九格偏移与幽灵槽时自动跳过并精准取回真实槽位() {
        // 携带网络工具箱：0..8 TOOLBOX，9..12 UPGRADE，13..15 CONFIG，16..51 角色背包，52..60 MACHINE_INPUT，61 MACHINE_OUTPUT。
        Map<String, List<Integer>> semantics = Map.of(
                "TOOLBOX", range(0, 8),
                "UPGRADE", range(9, 12),
                "CONFIG", range(13, 15),
                "MACHINE_INPUT", range(52, 60),
                "MACHINE_OUTPUT", List.of(61));
        AdvancedAeSemanticsLayout layout = new AdvancedAeSemanticsLayout(fakeReads(semantics));

        List<Integer> other = new ArrayList<>();
        other.addAll(range(0, 15));
        other.addAll(range(52, 61));
        MenuLayout.Layout result = layout.classify(
                menuOf("advanced_ae:reaction_chamber", 62, 16, 36), range(16, 51), other);

        MenuLayout.Supported supported = assertInstanceOf(MenuLayout.Supported.class, result);
        assertEquals(14, supported.containerSlots().size());
        for (int toolbox : range(0, 8)) {
            assertFalse(supported.containerSlots().contains(toolbox), "绝不可包含 TOOLBOX 槽位 " + toolbox);
        }
        for (int config : range(13, 15)) {
            assertFalse(supported.containerSlots().contains(config), "绝不可包含 CONFIG 槽位 " + config);
        }
        assertTrue(supported.containerSlots().containsAll(range(52, 61)));
        assertTrue(supported.containerSlots().containsAll(range(9, 12)));
    }

    @Test
    void 非三十六格背包或未知菜单与非法规则一律拒绝() {
        AdvancedAeSemanticsLayout layout = new AdvancedAeSemanticsLayout(
                fakeReads(Map.of("MACHINE_INPUT", range(0, 8))));
        // 输出方向子菜单不含 36 格角色背包：必须拒绝。
        assertInstanceOf(MenuLayout.Unsupported.class,
                layout.classify(menuOf("advanced_ae:reaction_chamber", 0, 0, 0), List.of(), List.of()));
        assertInstanceOf(MenuLayout.Unsupported.class,
                layout.classify(menuOf("advanced_ae:reaction_chamber", 36, 9, 27), range(9, 35), range(0, 8)));
        assertInstanceOf(MenuLayout.Unsupported.class,
                layout.classify(menuOf("ae2addonlib:output_direction", 36, 0, 36), range(0, 35), List.of()));
        // 规则禁止声明 TOOLBOX、CONFIG 或非子集机器槽。
        assertThrows(IllegalArgumentException.class,
                () -> new AdvancedAeSemanticsLayout.Rule(List.of("TOOLBOX"), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new AdvancedAeSemanticsLayout.Rule(List.of("CONFIG"), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new AdvancedAeSemanticsLayout.Rule(List.of("MACHINE_INPUT"), Set.of("UPGRADE")));
    }

    @Test
    void 验证版本范围匹配实测版本且读写端接口错配时自动停用() {
        VerifiedVersions versions = new VerifiedVersions("1.6.11", "1.7");
        assertTrue(versions.contains("1.6.11-1.21.1"));
        assertTrue(versions.contains("1.6.12"));
        assertFalse(versions.contains("1.6.10"));
        assertFalse(versions.contains("1.7.0"));

        AdvancedAeReads broken = new AdvancedAeReads() {
            @Override public Optional<ChamberView> chamberAt(ClientLevel level, BlockPos at) {
                throw new NoSuchMethodError("ReactionChamberEntity.getTank");
            }
            @Override public List<Integer> menuSlotsOf(String semanticId) { return List.of(); }
            @Override public Optional<ChamberMenuView> openChamberMenu() { return Optional.empty(); }
            @Override public Optional<Set<String>> openOutputDirections() { return Optional.empty(); }
            @Override public boolean sendAutoExportToggle() { return false; }
            @Override public boolean sendClearFluidIn() { return false; }
            @Override public boolean sendClearFluidOut() { return false; }
            @Override public boolean sendOpenOutputDirection() { return false; }
            @Override public boolean sendToggleOutputSide(String relativeSide) { return false; }
            @Override public boolean sendReturnToParentMenu() { return false; }
        };
        AdvancedAeCompat compat = new AdvancedAeCompat(broken);
        assertThrows(ModApiMismatch.class, () -> compat.chamberAt(null, BlockPos.ZERO));
        assertFalse(compat.active());
    }
}
