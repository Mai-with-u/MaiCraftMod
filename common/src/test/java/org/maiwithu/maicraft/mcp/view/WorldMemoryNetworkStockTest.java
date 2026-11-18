// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.view;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.inventory.spi.NetworkStorage;
import org.maiwithu.maicraft.behavior.perception.SceneSelf;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 世界记忆观察视图里 {@code network_stock} 一节的渲染、半径过滤与超长存货上限保护测试。
 */
class WorldMemoryNetworkStockTest {

    private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();
    private static final Instant NOW = Instant.parse("2026-11-18T05:00:00Z");
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    @TempDir
    Path temp;

    private SceneSelf selfAtOrigin() {
        return new SceneSelf(0.5, 64, 0.5, 180f, "北", 20f, 18, 300, 300, null, List.of(), List.of(), true, false);
    }

    @Test
    void rendersWirelessAndPlacedNetworkStockSortedByDistanceWithAllFields() {
        WorldMemory memory = new WorldMemory(new DocumentStore(temp.resolve("mem.sqlite")), "a".repeat(64));
        SceneSelf self = selfAtOrigin();

        JsonObject emptyView = WorldMemoryView.of(memory, self, OVERWORLD, null, null, null, ignored -> { }, null,
                List.of());
        assertEquals(0, emptyView.getAsJsonArray("network_stock").size(), "没有网络存货记录时默认也出空数组");

        List<NetworkStorage.RememberedNetwork> networks = List.of(
                NetworkStorage.RememberedNetwork.placed("ae2:terminal",
                        new WorldPosition(12, 64, 0, OVERWORLD), Direction.WEST,
                        true, true, Map.of("minecraft:torch", 32L), Set.of(), NOW),
                NetworkStorage.RememberedNetwork.wireless("ae2:wireless_terminal",
                        true, true, Map.of("minecraft:cobblestone", 8192L, "minecraft:iron_ingot", 256L),
                        Set.of("minecraft:hopper", "minecraft:piston"), NOW),
                NetworkStorage.RememberedNetwork.placed("ae2:crafting_terminal",
                        new WorldPosition(3, 64, 0, OVERWORLD), Direction.NORTH,
                        false, false, Map.of(), Set.of(), NOW));

        JsonObject view = WorldMemoryView.of(memory, self, OVERWORLD, null, null, null, ignored -> { }, null, networks);
        JsonArray stock = view.getAsJsonArray("network_stock");
        assertEquals(3, stock.size());

        JsonObject first = stock.get(0).getAsJsonObject();
        assertEquals("ae2:wireless_terminal", first.get("terminal").getAsString());
        assertTrue(first.get("wireless").getAsBoolean());
        assertFalse(first.has("position"));
        assertFalse(first.has("side"));
        assertTrue(first.get("linked").getAsBoolean());
        assertTrue(first.get("complete").getAsBoolean());
        assertEquals(2, first.get("item_types").getAsInt());
        assertEquals(8448L, first.get("total_items").getAsLong());
        JsonArray contents = first.getAsJsonArray("contents");
        assertEquals("minecraft:cobblestone", contents.get(0).getAsJsonObject().get("item").getAsString());
        assertEquals(8192L, contents.get(0).getAsJsonObject().get("count").getAsLong());
        assertEquals("minecraft:iron_ingot", contents.get(1).getAsJsonObject().get("item").getAsString());
        assertEquals(2, first.getAsJsonArray("craftable").size());
        assertEquals(NOW.toString(), first.get("recorded_at").getAsString());

        JsonObject second = stock.get(1).getAsJsonObject();
        assertEquals("ae2:crafting_terminal", second.get("terminal").getAsString());
        assertFalse(second.get("wireless").getAsBoolean());
        assertEquals("north", second.get("side").getAsString());
        assertEquals(3, second.get("distance").getAsInt());
        assertFalse(second.get("linked").getAsBoolean());
        assertFalse(second.get("complete").getAsBoolean());
        assertEquals(0, second.get("item_types").getAsInt());
        assertEquals(0L, second.get("total_items").getAsLong());
        assertFalse(second.has("craftable"));

        JsonObject third = stock.get(2).getAsJsonObject();
        assertEquals("ae2:terminal", third.get("terminal").getAsString());
        assertEquals(12, third.get("distance").getAsInt());
    }

    @Test
    void radiusFiltersOutFarAndCrossDimensionFixedTerminalsWhileKeepingWireless() {
        WorldMemory memory = new WorldMemory(new DocumentStore(temp.resolve("radius.sqlite")), "b".repeat(64));
        SceneSelf self = selfAtOrigin();
        List<NetworkStorage.RememberedNetwork> networks = List.of(
                NetworkStorage.RememberedNetwork.wireless("ae2:wireless_terminal", true, true,
                        Map.of("minecraft:bread", 16L), Set.of(), NOW),
                NetworkStorage.RememberedNetwork.placed("ae2:crafting_terminal",
                        new WorldPosition(5, 64, 0, OVERWORLD), Direction.UP,
                        true, true, Map.of("minecraft:oak_log", 64L), Set.of(), NOW),
                NetworkStorage.RememberedNetwork.placed("ae2:terminal",
                        new WorldPosition(25, 64, 0, OVERWORLD), Direction.SOUTH,
                        true, true, Map.of("minecraft:coal", 64L), Set.of(), NOW),
                NetworkStorage.RememberedNetwork.placed("ae2:terminal",
                        new WorldPosition(2, 64, 0, NETHER), Direction.EAST,
                        true, true, Map.of("minecraft:quartz", 64L), Set.of(), NOW));

        JsonObject filtered = WorldMemoryView.of(memory, self, OVERWORLD, 10, null, null, ignored -> { },
                Set.of("network_stock"), networks);
        assertEquals(1, filtered.keySet().size());
        JsonArray stock = filtered.getAsJsonArray("network_stock");
        assertEquals(2, stock.size(), "随身无线终端保留，超出半径或异维度的固定终端过滤");
        assertTrue(stock.get(0).getAsJsonObject().get("wireless").getAsBoolean());
        assertEquals("ae2:crafting_terminal", stock.get(1).getAsJsonObject().get("terminal").getAsString());
    }

    @Test
    void foldsExcessStockItemsIntoItemsNotListedStayingUnderMaxViewChars() {
        WorldMemory memory = new WorldMemory(new DocumentStore(temp.resolve("large.sqlite")), "c".repeat(64));
        SceneSelf self = selfAtOrigin();
        for (int index = 0; index < 260; index++) {
            memory.rememberContainerOpened(new WorldPosition(index + 1, 64, 0, OVERWORLD),
                    "minecraft:chest", List.of("minecraft:cobblestone", "minecraft:torch"), NOW);
        }
        Map<String, Long> hugeStock = new LinkedHashMap<>();
        for (int index = 0; index < 1500; index++) {
            hugeStock.put("modpack:industrial_component_item_" + index, (long) (1500 - index) * 100L);
        }
        List<NetworkStorage.RememberedNetwork> networks = List.of(
                NetworkStorage.RememberedNetwork.placed("ae2:crafting_terminal",
                        new WorldPosition(2, 64, 0, OVERWORLD), Direction.NORTH,
                        true, true, hugeStock, Set.of("minecraft:piston"), NOW));

        List<String> sectionNotes = new ArrayList<>();
        JsonObject onlyNetwork = WorldMemoryView.of(memory, self, OVERWORLD, null, null, null,
                sectionNotes::add, Set.of("network_stock"), networks);
        int onlyNetworkChars = COMPACT.toJson(onlyNetwork).length();
        assertTrue(onlyNetworkChars <= WorldMemoryView.MAX_VIEW_CHARS, "字符数：" + onlyNetworkChars);
        JsonObject firstEntry = onlyNetwork.getAsJsonArray("network_stock").get(0).getAsJsonObject();
        assertEquals(1500, firstEntry.get("item_types").getAsInt());
        int listedOnly = firstEntry.getAsJsonArray("contents").size();
        int omittedOnly = firstEntry.get("items_not_listed").getAsInt();
        assertEquals(1500, listedOnly + omittedOnly);
        assertTrue(omittedOnly > 0);
        assertFalse(sectionNotes.isEmpty());

        List<String> allNotes = new ArrayList<>();
        JsonObject allFour = WorldMemoryView.of(memory, self, OVERWORLD, null, null, null,
                allNotes::add, null, networks);
        int allFourChars = COMPACT.toJson(allFour).length();
        assertTrue(allFourChars <= WorldMemoryView.MAX_VIEW_CHARS, "四节合包字符数不能超限：" + allFourChars);
        assertTrue(allFour.get("records_listed").getAsInt() > 0, "近处容器记录不会被超长网络存货挤空");
        assertTrue(allFour.has("records_not_listed"));
        assertTrue(allFour.getAsJsonArray("network_stock").get(0).getAsJsonObject().get("items_not_listed").getAsInt() > 0);
    }
}
