// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.view;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.maiwithu.maicraft.behavior.inventory.spi.NetworkStorage;
import org.maiwithu.maicraft.behavior.perception.SceneSelf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * {@code observe(what=world_memory)} 里 {@code network_stock} 一节的渲染与字符上限保护：
 * 随身无线终端不受方块距离过滤排除并排在最前，固定终端按同维度及由近到远排序；
 * 单条网络或整体逼近回包字符上限时，把剩余现货物品折算为 {@code items_not_listed} 计数，绝不触发整包 {@code too_large}。
 */
final class NetworkStockView {

    private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();
    /** 为 items_not_listed、craftable_not_listed 与 recorded_at 预留的尾部字符余量。 */
    private static final int TAIL_MARGIN_CHARS = 128;

    private NetworkStockView() {}

    static JsonArray render(List<NetworkStorage.RememberedNetwork> networks, SceneSelf self, String dimension,
            Integer radius, int charBudget, Consumer<String> notes) {
        JsonArray array = new JsonArray();
        List<NetworkStorage.RememberedNetwork> filtered = filterAndSort(networks, self, dimension, radius);
        if (filtered.isEmpty()) {
            return array;
        }
        int usedChars = 2;
        int omittedNetworks = 0;
        for (int index = 0; index < filtered.size(); index++) {
            NetworkStorage.RememberedNetwork entry = filtered.get(index);
            int remainingBudget = charBudget - usedChars - 2;
            JsonObject rendered = renderEntry(entry, self, dimension, remainingBudget, index == 0, notes);
            if (rendered == null) {
                omittedNetworks = filtered.size() - index;
                break;
            }
            array.add(rendered);
            usedChars += COMPACT.toJson(rendered).length() + 1;
        }
        if (omittedNetworks > 0) {
            notes.accept("世界记忆里还有 " + omittedNetworks + " 台 ME 终端的存货快照因回包上限未展开，可传 radius 收窄");
        }
        return array;
    }

    private static List<NetworkStorage.RememberedNetwork> filterAndSort(
            List<NetworkStorage.RememberedNetwork> networks, SceneSelf self, String dimension, Integer radius) {
        if (networks == null || networks.isEmpty()) return List.of();
        List<NetworkStorage.RememberedNetwork> filtered = new ArrayList<>();
        for (NetworkStorage.RememberedNetwork entry : networks) {
            if (!entry.wireless() && radius != null) {
                if (!WorldMemoryView.sameDimension(entry.position(), dimension)) continue;
                if (self != null && SceneView.distance(self, entry.position()) > radius) continue;
            }
            filtered.add(entry);
        }
        filtered.sort(Comparator
                .<NetworkStorage.RememberedNetwork>comparingInt(entry -> entry.wireless() ? 0 : 1)
                .thenComparingInt(entry -> entry.wireless()
                        || WorldMemoryView.sameDimension(entry.position(), dimension) ? 0 : 1)
                .thenComparingInt(entry -> !entry.wireless() && self != null
                        && WorldMemoryView.sameDimension(entry.position(), dimension)
                        ? SceneView.distance(self, entry.position()) : 0)
                .thenComparing(Comparator.comparing(NetworkStorage.RememberedNetwork::recordedAt).reversed())
                .thenComparing(NetworkStorage.RememberedNetwork::terminal));
        return filtered;
    }

    private static JsonObject renderEntry(NetworkStorage.RememberedNetwork entry, SceneSelf self, String dimension,
            int budget, boolean mustIncludeShell, Consumer<String> notes) {
        JsonObject item = new JsonObject();
        item.addProperty("terminal", entry.terminal());
        item.addProperty("wireless", entry.wireless());
        if (!entry.wireless()) {
            item.add("position", SceneView.position(entry.position()));
            item.addProperty("side", entry.side().getName());
            if (self != null && WorldMemoryView.sameDimension(entry.position(), dimension)) {
                item.addProperty("distance", SceneView.distance(self, entry.position()));
            }
        }
        item.addProperty("linked", entry.linked());
        item.addProperty("complete", entry.complete());
        item.addProperty("item_types", entry.storedByItem().size());
        long totalItems = 0L;
        for (long count : entry.storedByItem().values()) {
            totalItems += count;
        }
        item.addProperty("total_items", totalItems);

        String recordedAt = entry.recordedAt().toString();
        int shellChars = COMPACT.toJson(item).length() + recordedAt.length() + TAIL_MARGIN_CHARS;
        if (!mustIncludeShell && shellChars > budget) {
            return null;
        }
        int contentBudget = Math.max(0, budget - shellChars);
        int usedContentChars = writeContents(item, entry.storedByItem(), contentBudget, notes, entry.terminal());
        writeCraftable(item, entry.craftableItems(), Math.max(0, contentBudget - usedContentChars));
        item.addProperty("recorded_at", recordedAt);
        return item;
    }

    private static int writeContents(JsonObject item, Map<String, Long> storedByItem, int budget,
            Consumer<String> notes, String terminal) {
        JsonArray contents = new JsonArray();
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(storedByItem.entrySet());
        sorted.sort(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey));
        int used = 2;
        int omitted = 0;
        for (int i = 0; i < sorted.size(); i++) {
            Map.Entry<String, Long> stock = sorted.get(i);
            JsonObject stack = new JsonObject();
            stack.addProperty("item", stock.getKey());
            stack.addProperty("count", stock.getValue());
            int stackChars = COMPACT.toJson(stack).length() + 1;
            if (used + stackChars <= budget) {
                contents.add(stack);
                used += stackChars;
            } else {
                omitted = sorted.size() - i;
                break;
            }
        }
        item.add("contents", contents);
        if (omitted > 0) {
            item.addProperty("items_not_listed", omitted);
            notes.accept("终端 " + terminal + " 共有 " + sorted.size() + " 种现货，按件数从多到少列出了前 "
                    + contents.size() + " 种，其余 " + omitted + " 种见 items_not_listed");
        }
        return used;
    }

    private static void writeCraftable(JsonObject item, Set<String> craftableItems, int budget) {
        if (craftableItems == null || craftableItems.isEmpty()) return;
        List<String> sorted = craftableItems.stream().sorted().toList();
        JsonArray craftable = new JsonArray();
        int used = 16;
        int omitted = 0;
        for (int i = 0; i < sorted.size(); i++) {
            String itemId = sorted.get(i);
            int entryChars = itemId.length() + 3;
            if (used + entryChars <= budget) {
                craftable.add(itemId);
                used += entryChars;
            } else {
                omitted = sorted.size() - i;
                break;
            }
        }
        item.add("craftable", craftable);
        if (omitted > 0) {
            item.addProperty("craftable_not_listed", omitted);
        }
    }
}
