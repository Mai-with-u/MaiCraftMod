// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.view;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.maiwithu.maicraft.behavior.perception.SceneSelf;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryRecord;
import org.maiwithu.maicraft.behavior.worldmemory.RememberedRegion;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 世界记忆与命名地点的观察视图：
 * <ul>
 *   <li>{@code observe(what=places)}：紧凑列出按名字记的地点、建筑档案、机器档案和地盘，以及自动记录的分类总数；</li>
 *   <li>{@code observe(what=world_memory)}：支持按 {@code radius}、{@code kind} 过滤，未过滤时按由近到远装到回包上限以内，
 *       不触发整包 {@code too_large} 失败，超出的部分在 {@code records_not_listed} 里按种类如实报数。</li>
 * </ul>
 */
public final class WorldMemoryView {

    /** 留给外层信封与 notes 的余量：控制在 ToolDispatcher 50000 字符上限之内。 */
    public static final int MAX_VIEW_CHARS = 38_000;
    private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();
    private static final List<MemoryKind> AUTO_KINDS = List.of(
            MemoryKind.CONTAINER, MemoryKind.WORKSTATION, MemoryKind.SITE, MemoryKind.TEMPORARY_BLOCK);

    private WorldMemoryView() {}

    /** 紧凑的命名地点视图（observe(what=places)）：速览读记住的地点用它，不再拉整份容器与产地记录。 */
    public static JsonObject placesView(WorldMemory memory, SceneSelf self, String dimension) {
        JsonObject json = new JsonObject();
        json.add("places", namedPlaces(memory, self, dimension));
        json.add("regions", regions(memory.regions()));
        JsonObject counts = new JsonObject();
        Map<MemoryKind, Integer> tally = new LinkedHashMap<>();
        for (MemoryRecord record : memory.allRecords()) {
            if (AUTO_KINDS.contains(record.kind())) {
                tally.merge(record.kind(), 1, Integer::sum);
            }
        }
        for (MemoryKind kind : AUTO_KINDS) {
            Integer count = tally.get(kind);
            if (count != null && count > 0) {
                counts.addProperty(SceneView.lower(kind), count);
            }
        }
        json.add("record_counts", counts);
        return json;
    }

    /** 兼容无过滤调用：不限半径与种类、不单独收说明。 */
    public static JsonObject of(WorldMemory memory, SceneSelf self, Function<WorldPosition, String> lastModifier) {
        return of(memory, self, null, null, null, lastModifier, ignored -> {});
    }

    /**
     * 世界记忆视图：可按半径与种类过滤；记录太多时近的先列到单次回包装得下为止，其余按种类报数。
     */
    public static JsonObject of(WorldMemory memory, SceneSelf self, String dimension,
            Integer radius, MemoryKind kindFilter,
            Function<WorldPosition, String> lastModifier, Consumer<String> notes) {
        JsonObject json = new JsonObject();
        json.add("places", places(memory.places(), self, dimension));
        json.add("regions", regions(memory.regions()));

        List<MemoryRecord> filtered = new ArrayList<>();
        for (MemoryRecord record : memory.allRecords()) {
            if (kindFilter != null && record.kind() != kindFilter) continue;
            if (radius != null) {
                if (!sameDimension(record.position(), dimension)) continue;
                if (self != null && SceneView.distance(self, record.position()) > radius) continue;
            }
            filtered.add(record);
        }
        if (self != null) {
            filtered.sort(Comparator
                    .<MemoryRecord>comparingInt(record -> sameDimension(record.position(), dimension) ? 0 : 1)
                    .thenComparingInt(record -> sameDimension(record.position(), dimension)
                            ? SceneView.distance(self, record.position()) : 0));
        }

        int usedChars = COMPACT.toJson(json).length() + 256;
        JsonArray list = new JsonArray();
        Map<String, Integer> notListed = new LinkedHashMap<>();
        for (MemoryRecord entry : filtered) {
            JsonObject rendered = record(entry, self, dimension, lastModifier);
            int itemChars = COMPACT.toJson(rendered).length() + 2;
            if (notListed.isEmpty() && usedChars + itemChars <= MAX_VIEW_CHARS) {
                list.add(rendered);
                usedChars += itemChars;
            } else {
                notListed.merge(SceneView.lower(entry.kind()), 1, Integer::sum);
            }
        }
        json.add("records", list);
        json.addProperty("records_total", filtered.size());
        json.addProperty("records_listed", list.size());
        if (!notListed.isEmpty()) {
            JsonObject omitted = new JsonObject();
            notListed.forEach(omitted::addProperty);
            json.add("records_not_listed", omitted);
            notes.accept("世界记忆共有 " + filtered.size() + " 条记录，按由近到远列出了最近的 " + list.size()
                    + " 条；只读记住的地点请用 observe(what=\"places\")，要看其余记录可传 radius 或 kind 收窄");
        }
        return json;
    }

    private record NamedEntry(String name, String kind, WorldPosition position) {}

    private static JsonArray namedPlaces(WorldMemory memory, SceneSelf self, String dimension) {
        Map<String, NamedEntry> merged = new LinkedHashMap<>();
        memory.places().forEach((name, pos) -> merged.put("place:" + name, new NamedEntry(name, "place", pos)));
        for (MemoryRecord record : memory.allRecords()) {
            if ((record.kind() == MemoryKind.BUILDING || record.kind() == MemoryKind.MACHINE)
                    && record.blockType() != null && !record.blockType().isBlank()) {
                String name = record.blockType();
                String kind = SceneView.lower(record.kind());
                NamedEntry plain = merged.get("place:" + name);
                if (plain != null && MemoryRecord.sameSpot(plain.position(), record.position())) {
                    merged.remove("place:" + name);
                }
                merged.put(kind + ":" + name, new NamedEntry(name, kind, record.position()));
            }
        }
        List<NamedEntry> entries = new ArrayList<>(merged.values());
        entries.sort(Comparator
                .<NamedEntry>comparingInt(entry -> sameDimension(entry.position(), dimension) ? 0 : 1)
                .thenComparingInt(entry -> self != null && sameDimension(entry.position(), dimension)
                        ? SceneView.distance(self, entry.position()) : 0)
                .thenComparing(NamedEntry::name));
        JsonArray list = new JsonArray();
        for (NamedEntry entry : entries) {
            JsonObject item = new JsonObject();
            item.addProperty("name", entry.name());
            item.addProperty("kind", entry.kind());
            item.add("position", SceneView.position(entry.position()));
            if (self != null && sameDimension(entry.position(), dimension)) {
                item.addProperty("distance", SceneView.distance(self, entry.position()));
            }
            list.add(item);
        }
        return list;
    }

    private static JsonArray regions(List<RememberedRegion> remembered) {
        JsonArray regions = new JsonArray();
        for (RememberedRegion region : remembered) {
            JsonObject item = new JsonObject();
            item.addProperty("name", region.name());
            item.add("center", SceneView.position(region.center()));
            item.addProperty("radius", region.radiusBlocks());
            regions.add(item);
        }
        return regions;
    }

    private static JsonArray places(Map<String, WorldPosition> places, SceneSelf self, String dimension) {
        List<Map.Entry<String, WorldPosition>> entries = new ArrayList<>(places.entrySet());
        if (self != null) {
            entries.sort(Comparator
                    .<Map.Entry<String, WorldPosition>>comparingInt(
                            entry -> sameDimension(entry.getValue(), dimension) ? 0 : 1)
                    .thenComparingInt(entry -> sameDimension(entry.getValue(), dimension)
                            ? SceneView.distance(self, entry.getValue()) : 0)
                    .thenComparing(Map.Entry::getKey));
        }
        JsonArray list = new JsonArray();
        for (Map.Entry<String, WorldPosition> entry : entries) {
            JsonObject item = new JsonObject();
            item.addProperty("name", entry.getKey());
            item.add("position", SceneView.position(entry.getValue()));
            if (self != null && sameDimension(entry.getValue(), dimension)) {
                item.addProperty("distance", SceneView.distance(self, entry.getValue()));
            }
            list.add(item);
        }
        return list;
    }

    static JsonObject record(MemoryRecord record, SceneSelf self, Function<WorldPosition, String> lastModifier) {
        return record(record, self, null, lastModifier);
    }

    static JsonObject record(MemoryRecord record, SceneSelf self, String dimension,
            Function<WorldPosition, String> lastModifier) {
        JsonObject item = new JsonObject();
        item.addProperty("kind", SceneView.lower(record.kind()));
        if (record.blockType() != null) item.addProperty("block", record.blockType());
        item.add("position", SceneView.position(record.position()));
        if (self != null && sameDimension(record.position(), dimension)) {
            item.addProperty("distance", SceneView.distance(self, record.position()));
        }
        // 容器带最后由谁放下：只对容器记录给，问不到就不出字段。
        String modifier = record.kind() == MemoryKind.CONTAINER && lastModifier != null
                ? lastModifier.apply(record.position()) : null;
        if (modifier != null) item.addProperty("last_modified_by", modifier);
        if (record.contents() != null) {
            JsonArray contents = new JsonArray();
            record.contents().forEach(contents::add);
            item.add("contents", contents);
        }
        item.addProperty("origin", SceneView.lower(record.origin()));
        item.addProperty("recorded_at", record.recordedAt().toString());
        return item;
    }

    private static boolean sameDimension(WorldPosition position, String currentDimension) {
        return position.dimension() == null || currentDimension == null
                || position.dimension().equals(currentDimension);
    }
}
