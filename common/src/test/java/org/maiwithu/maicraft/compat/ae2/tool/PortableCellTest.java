// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;

/**
 * 10 种便携存储元件的目录、字节/种类折算空槽、低电保护与库存匹配离线测试。
 */
class PortableCellTest {

    @BeforeAll
    static void 引导注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 目录完整覆盖十种物品与流体便携存储元件() {
        assertEquals(5, PortableCellCatalog.ITEM_CELL_IDS.size());
        assertEquals(5, PortableCellCatalog.FLUID_CELL_IDS.size());
        assertEquals(10, PortableCellCatalog.ALL_CELL_IDS.size());
        assertTrue(PortableCellCatalog.isPortableCellId("ae2:portable_item_cell_1k"));
        assertTrue(PortableCellCatalog.isPortableCellId("ae2:portable_item_cell_256k"));
        assertTrue(PortableCellCatalog.isPortableCellId("ae2:portable_fluid_cell_1k"));
        assertTrue(PortableCellCatalog.isPortableCellId("ae2:portable_fluid_cell_256k"));
        assertFalse(PortableCellCatalog.isPortableCellId("ae2:item_storage_cell_1k"));
    }

    @Test
    void 物品便携元件按剩余种类与字节精确折算空槽且流体与耗电元件报零() {
        // 1k 物品便携元件（512 字节，每类 8 字节，54 种上限）：空盘 512 / (8 + 8) = 32 格。
        Ae2ToolReads.PortableCellSnapshot empty1k = new Ae2ToolReads.PortableCellSnapshot(
                "ae2:portable_item_cell_1k", false, 20000.0, 20000.0, 512, 0, 8, 0, 54, List.of());
        assertEquals(32, PortableCellCatalog.freeSlotsOf(empty1k));

        // 4k 物品便携元件（2048 字节，每类 32 字节，45 种上限）：受种类上限封顶为 45 格。
        Ae2ToolReads.PortableCellSnapshot empty4k = new Ae2ToolReads.PortableCellSnapshot(
                "ae2:portable_item_cell_4k", false, 20000.0, 20000.0, 2048, 0, 32, 0, 45, List.of());
        assertEquals(45, PortableCellCatalog.freeSlotsOf(empty4k));

        // 电量耗尽一律报 0，不参与腾背包。
        Ae2ToolReads.PortableCellSnapshot drained = new Ae2ToolReads.PortableCellSnapshot(
                "ae2:portable_item_cell_4k", false, 0.0, 20000.0, 2048, 0, 32, 0, 45, List.of());
        assertEquals(0, PortableCellCatalog.freeSlotsOf(drained));

        // 流体便携元件不参与腾背包杂物，恒报 0。
        Ae2ToolReads.PortableCellSnapshot fluid4k = new Ae2ToolReads.PortableCellSnapshot(
                "ae2:portable_fluid_cell_4k", true, 20000.0, 20000.0, 2048, 0, 32, 0, 18, List.of());
        assertEquals(0, PortableCellCatalog.freeSlotsOf(fluid4k));

        // 种类已满但剩余字节非空：不能收新种类（freeSlots=0），仍可并堆已有种类。
        Ae2ToolReads.PortableCellSnapshot typesFull = new Ae2ToolReads.PortableCellSnapshot(
                "ae2:portable_item_cell_1k", false, 10000.0, 20000.0, 512, 460, 8, 54, 54,
                List.of(Ae2ToolReads.CellStockEntry.item("minecraft:cobblestone", 128)));
        assertEquals(0, PortableCellCatalog.freeSlotsOf(typesFull));
        assertTrue(PortableCellCatalog.canAcceptItem(typesFull, "minecraft:cobblestone"));
        assertFalse(PortableCellCatalog.canAcceptItem(typesFull, "minecraft:dirt"));
    }

    @Test
    void 便携元件库存摘要与低电提示写明电量与充能器补电建议() {
        Ae2ToolReads.PortableCellSnapshot fluidCell = new Ae2ToolReads.PortableCellSnapshot(
                "ae2:portable_fluid_cell_1k", true, 0.0, 20000.0, 512, 64, 8, 1, 18,
                List.of(Ae2ToolReads.CellStockEntry.fluid("minecraft:water_bucket", "minecraft:water", 4000)));
        assertEquals(Map.of("minecraft:water", 4000), PortableCellCatalog.summarizeContents(fluidCell));
        assertEquals(4L, PortableCellSource.matchingCount(fluidCell, "minecraft:water_bucket"::equals));

        String low = PortableCellCatalog.lowEnergyMessage(fluidCell);
        assertTrue(low.contains("0/20000 AE"));
        assertTrue(low.contains("ae2:charger"));
        assertTrue(low.contains("machine_run"));
    }

    @Test
    void 便携存储来源归属随身途径且离线空背包时安全返回不可用() {
        PortableCellSource source = new PortableCellSource(ToolTestFakes.compat(), Ae2ToolReads.NONE,
                ToolTestFakes.services(() -> null, Map.of(), List.of()));
        assertEquals(AcquireVia.CARRIED, source.via());
        PortableCellBackpack backpack = new PortableCellBackpack(ToolTestFakes.compat(), Ae2ToolReads.NONE,
                ToolTestFakes.services(() -> null, Map.of(), List.of()));
        assertEquals(0, backpack.freeSlots());
        assertTrue(backpack.carried().isEmpty());
    }
}
