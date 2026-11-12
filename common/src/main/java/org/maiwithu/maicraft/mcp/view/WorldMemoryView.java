// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.view;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.maiwithu.maicraft.behavior.perception.FacilityKinds;
import org.maiwithu.maicraft.behavior.perception.SceneSelf;
import org.maiwithu.maicraft.behavior.permission.ContainerWhose;
import org.maiwithu.maicraft.behavior.permission.Protection;
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
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 世界记忆与命名地点的观察视图：
 * <ul>
 *   <li>{@code observe(what=places)}：紧凑列出按名字记的地点、建筑档案、机器档案和地盘，以及自动记录的分类总数；</li>
 *   <li>{@code observe(what=world_memory)}：支持按 {@code radius}、{@code kind} 过滤，只出点名的节（places / regions / records）；
 *       未过滤且三节都要时按由近到远装到回包上限以内，不触发整包 {@code too_large} 失败，
 *       超出的部分在 {@code records_not_listed} 里按种类如实报数。</li>
 * </ul>
 */
public final class WorldMemoryView {

    /** 留给外层信封与 notes 的余量：控制在 ToolDispatcher 50000 字符上限之内。 */
    public static final int MAX_VIEW_CHARS = 38_000;
    private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();
    private static final List<MemoryKind> AUTO_KINDS = List.of(
            MemoryKind.CONTAINER, MemoryKind.WORKSTATION, MemoryKind.SITE, MemoryKind.TEMPORARY_BLOCK);

    private WorldMemoryView() {}

    /** 按位置、方块类型与是否开过断定容器是谁的。 */
    @FunctionalInterface
    public interface ContainerOrigin {
        ContainerWhose of(WorldPosition position, String blockType, boolean openedBySelf);
    }

    /** 生产与完整观察测试共用：按战利品方块标签、区块归属表与世界记忆断定容器来源，缺维度的坐标补上当前维度。 */
    public static ContainerOrigin originFrom(FacilityKinds kinds, Protection protection, String dimension) {
        return (position, blockType, openedBySelf) -> {
            WorldPosition located = position != null && position.dimension() == null && dimension != null
                    ? new WorldPosition(position.x(), position.y(), position.z(), dimension) : position;
            boolean naturalLoot = kinds != null && blockType != null && kinds.isNaturalLootContainer(blockType);
            return ContainerWhose.of(located, naturalLoot, protection, openedBySelf);
        };
    }

    static ContainerOrigin fromLegacyModifier(Function<WorldPosition, String> lastModifier) {
        if (lastModifier == null) {
            return (position, blockType, openedBySelf) ->
                    new ContainerWhose(ContainerWhose.Kind.UNKNOWN, null, openedBySelf);
        }
        return (position, blockType, openedBySelf) -> {
            String modifier = lastModifier.apply(position);
            return modifier != null
                    ? new ContainerWhose(ContainerWhose.Kind.PLAYER, modifier, openedBySelf)
                    : new ContainerWhose(ContainerWhose.Kind.NO_RECORD, null, openedBySelf);
        };
    }

    static void writeContainerOrigin(JsonObject json, ContainerWhose origin) {
        if (origin == null) return;
        json.addProperty("whose", origin.wire());
        if (origin.lastModifiedBy() != null) json.addProperty("last_modified_by", origin.lastModifiedBy());
        json.addProperty("opened_by_self", origin.openedBySelf());
    }

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

    /** 兼容无过滤调用：不限半径与种类、三节都出、不单独收说明。 */
    public static JsonObject of(WorldMemory memory, SceneSelf self, Function<WorldPosition, String> lastModifier) {
        return of(memory, self, null, null, null, fromLegacyModifier(lastModifier), ignored -> {}, null);
    }

    /** 兼容传最后放置者函数的调用：三节都出。 */
    public static JsonObject of(WorldMemory memory, SceneSelf self, String dimension,
            Integer radius, MemoryKind kindFilter,
            Function<WorldPosition, String> lastModifier, Consumer<String> notes) {
        return of(memory, self, dimension, radius, kindFilter, fromLegacyModifier(lastModifier), notes, null);
    }

    /**
     * 世界记忆视图：可按半径与种类过滤；records 会随游玩一直变长，只要个别节（places）的调用方按 sections 点名取那一节，
     * 不让整包顶到单回包上限；不点名就三节都出。记录太多时近的先列到单次回包装得下为止，其余按种类报数。
     *
     * @param self         角色此刻的位置，用来按远近排序；还没看清自己时为 null，按记录先后排
     * @param sections     只出哪几节（places / regions / records）；null 出全部三节
     */
    public static JsonObject of(WorldMemory memory, SceneSelf self, String dimension,
            Integer radius, MemoryKind kindFilter,
            ContainerOrigin containerOrigin, Consumer<String> notes, Set<String> sections) {
        JsonObject json = new JsonObject();
        if (serves(sections, "places")) json.add("places", places(memory.places(), self, dimension));
        if (serves(sections, "regions")) json.add("regions", regions(memory.regions()));

        List<MemoryRecord> filtered = new ArrayList<>();
        if (serves(sections, "records")) {
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
        }

        int usedChars = COMPACT.toJson(json).length() + 256;
        JsonArray list = new JsonArray();
        Map<String, Integer> notListed = new LinkedHashMap<>();
        for (MemoryRecord entry : filtered) {
            JsonObject rendered = record(entry, self, dimension, containerOrigin);
            int itemChars = COMPACT.toJson(rendered).length() + 2;
            if (notListed.isEmpty() && usedChars + itemChars <= MAX_VIEW_CHARS) {
                list.add(rendered);
                usedChars += itemChars;
            } else {
                notListed.merge(SceneView.lower(entry.kind()), 1, Integer::sum);
            }
        }
        if (serves(sections, "records")) {
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
        }
        return json;
    }

    /** 这一节这回出不出：没点名任何节（null）就三节都出。 */
    private static boolean serves(Set<String> sections, String section) {
        return sections == null || sections.contains(section);
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
        return record(record, self, null, fromLegacyModifier(lastModifier));
    }

    static JsonObject record(MemoryRecord record, SceneSelf self, String dimension,
            ContainerOrigin containerOrigin) {
        JsonObject item = new JsonObject();
        item.addProperty("kind", SceneView.lower(record.kind()));
        if (record.blockType() != null) item.addProperty("block", record.blockType());
        item.add("position", SceneView.position(record.position()));
        if (self != null && sameDimension(record.position(), dimension)) {
            item.addProperty("distance", SceneView.distance(self, record.position()));
        }
        // 容器记录统一带 whose、last_modified_by（仅 player 时）与 opened_by_self。
        if (record.kind() == MemoryKind.CONTAINER && containerOrigin != null) {
            writeContainerOrigin(item, containerOrigin.of(record.position(), record.blockType(), record.openedBefore()));
        }
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
