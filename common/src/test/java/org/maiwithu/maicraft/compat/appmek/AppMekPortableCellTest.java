// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.appmek;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.compat.appmek.AppMekReads.ChemicalStockEntry;
import org.maiwithu.maicraft.compat.appmek.AppMekReads.PortableChemicalCellSnapshot;
import org.maiwithu.maicraft.kernel.goal.Permissions;

/**
 * 便携化学品元件界面布局证明与 5 档免开窗只读检查离线测试：
 * 核对 36 格角色槽与网络工具箱 9 槽免疫、全 5 档认领范围、电量容量与化学品清单输出及低电充能提示。
 */
class AppMekPortableCellTest {

    @BeforeAll
    static void 初始化注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 便携化学品元件布局严检三十六格角色槽并免疫网络工具箱九槽() {
        AppMekPortableCellLayout proof = new AppMekPortableCellLayout();
        MenuLayouts layouts = new MenuLayouts(List.of(proof));

        // 模拟 36 格玩家槽 + 5 格升级/视图槽 + 携带网络工具时多出的 9 格工具箱槽（共 50 格）。
        MenuSlots withToolbox = new MenuSlots() {
            @Override public String menuTypeId() { return AppMekPortableCellLayout.MENU_PORTABLE_CHEMICAL_CELL; }
            @Override public int slotCount() { return 50; }
            @Override public boolean playerBacked(int slot) { return slot < 36; }
            @Override public int playerInventoryIndex(int slot) { return slot; }
        };
        MenuLayout.Layout result = layouts.classify(withToolbox);
        MenuLayout.Supported supported = assertInstanceOf(MenuLayout.Supported.class, result);
        assertEquals(36, supported.playerSlots().size());
        assertTrue(supported.containerSlots().isEmpty(), "盘内化学品不在普通物品格里，容器侧必须为空");
        assertTrue(supported.machineSlots().isEmpty());

        // 角色槽数量不对时直接拒绝证明。
        MenuLayout.Layout bad = proof.classify(withToolbox, IntStream.range(0, 27).boxed().toList(), List.of());
        assertInstanceOf(MenuLayout.Unsupported.class, bad);
    }

    @Test
    void 只读物品设置覆盖全部五档便携化学品元件并按电量输出补电提示() {
        PortableChemicalCellSnapshot charged = new PortableChemicalCellSnapshot(
                "appmek:portable_chemical_cell_16k", 18500.0, 20000.0, 16384L, 128L, 128, 2, 18,
                List.of(new ChemicalStockEntry("mekanism:hydrogen", 64000L),
                        new ChemicalStockEntry("mekanism:oxygen", 16000L)));
        PortableChemicalCellSnapshot drained = new PortableChemicalCellSnapshot(
                "appmek:portable_chemical_cell_1k", 0.0, 20000.0, 1024L, 0L, 8, 0, 18, List.of());

        Map<ItemStack, String> ids = new LinkedHashMap<>();
        ItemStack stack16k = new ItemStack(Items.PAPER);
        ItemStack stack1k = new ItemStack(Items.BOOK);
        ItemStack ae2ItemCell = new ItemStack(Items.MAP);
        ids.put(stack16k, "appmek:portable_chemical_cell_16k");
        ids.put(stack1k, "appmek:portable_chemical_cell_1k");
        ids.put(ae2ItemCell, "ae2:portable_item_cell_16k");

        AppMekReads reads = new AppMekReads() {
            @Override public Optional<ItemStack> wrapChemical(String chemicalId, long amount) {
                return Optional.empty();
            }

            @Override public Optional<PortableChemicalCellSnapshot> readPortableChemicalCell(ItemStack stack) {
                if (stack == stack16k) return Optional.of(charged);
                if (stack == stack1k) return Optional.of(drained);
                return Optional.empty();
            }

            @Override public boolean isPortableChemicalCellMenu(AbstractContainerMenu menu) {
                return false;
            }
        };
        AppMekCompat compat = new AppMekCompat(reads);
        AppMekPortableCellSettings settings = new AppMekPortableCellSettings(compat,
                stack -> ids.getOrDefault(stack, ""));

        assertEquals(Set.of(
                "appmek:portable_chemical_cell_1k",
                "appmek:portable_chemical_cell_4k",
                "appmek:portable_chemical_cell_16k",
                "appmek:portable_chemical_cell_64k",
                "appmek:portable_chemical_cell_256k"),
                AppMekPortableCellSettings.PORTABLE_CHEMICAL_CELL_IDS);
        assertTrue(settings.covers(stack16k));
        assertTrue(settings.covers(stack1k));
        assertFalse(settings.covers(ae2ItemCell));
        assertTrue(settings.change(0, Map.of("energy", "20000"), Permissions.DEFAULT).isEmpty());

        Map<String, MachineSetting> chargedMap = byKey(settings.settings(stack16k));
        assertEquals("18500", chargedMap.get("energy").value());
        assertEquals("20000", chargedMap.get("energy_max").value());
        assertEquals("128/16384", chargedMap.get("bytes").value());
        assertEquals("2/18", chargedMap.get("types").value());
        assertEquals("mekanism:hydrogen 64000 mB, mekanism:oxygen 16000 mB", chargedMap.get("chemicals").value());

        Map<String, MachineSetting> drainedMap = byKey(settings.settings(stack1k));
        assertEquals("0", drainedMap.get("energy").value());
        assertEquals("empty", drainedMap.get("chemicals").value());
        assertTrue(drainedMap.get("energy").description().contains("ae2:charger"));
        assertTrue(drainedMap.get("energy").description().contains("machine_run"));
    }

    private static Map<String, MachineSetting> byKey(List<MachineSetting> list) {
        Map<String, MachineSetting> map = new LinkedHashMap<>();
        for (MachineSetting setting : list) {
            map.put(setting.key(), setting);
        }
        return map;
    }
}
