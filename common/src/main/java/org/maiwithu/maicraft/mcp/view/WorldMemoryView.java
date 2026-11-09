// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.view;

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
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * observe(world_memory) 的样子：角色记得的地点、地盘、容器、工作站和产地，近的在前。
 *
 * <p>这是角色对世界的记忆，不是 LLM 的对话记忆。每条带记录时间：太久以前的记忆不全可信，
 * 用之前到现场会再核对。容器记录带"最后由谁放下"：宿主与 LLM 看得出动的是谁的东西。
 * 全部列出，不截断。
 */
public final class WorldMemoryView {
    private WorldMemoryView() {}

    /**
     * @param self         角色此刻的位置，用来按远近排序；还没看清自己时为 null，按记录先后排
     * @param lastModifier 一格是谁放的，说成名字；归属没问到、角色自己放的给 null，记录里就不带字段
     */
    public static JsonObject of(WorldMemory memory, SceneSelf self, Function<WorldPosition, String> lastModifier) {
        JsonObject json = new JsonObject();
        json.add("places", places(memory.places(), self));
        JsonArray regions = new JsonArray();
        for (RememberedRegion region : memory.regions()) {
            JsonObject item = new JsonObject();
            item.addProperty("name", region.name());
            item.add("center", SceneView.position(region.center()));
            item.addProperty("radius", region.radiusBlocks());
            regions.add(item);
        }
        json.add("regions", regions);
        List<MemoryRecord> records = new ArrayList<>(memory.allRecords());
        if (self != null) {
            records.sort(Comparator.comparingInt(record -> SceneView.distance(self, record.position())));
        }
        JsonArray list = new JsonArray();
        for (MemoryRecord record : records) {
            list.add(record(record, self, lastModifier));
        }
        json.add("records", list);
        return json;
    }

    private static JsonArray places(Map<String, WorldPosition> places, SceneSelf self) {
        List<Map.Entry<String, WorldPosition>> entries = new ArrayList<>(places.entrySet());
        if (self != null) {
            entries.sort(Comparator.comparingInt(entry -> SceneView.distance(self, entry.getValue())));
        }
        JsonArray list = new JsonArray();
        for (Map.Entry<String, WorldPosition> entry : entries) {
            JsonObject item = new JsonObject();
            item.addProperty("name", entry.getKey());
            item.add("position", SceneView.position(entry.getValue()));
            if (self != null) item.addProperty("distance", SceneView.distance(self, entry.getValue()));
            list.add(item);
        }
        return list;
    }

    static JsonObject record(MemoryRecord record, SceneSelf self, Function<WorldPosition, String> lastModifier) {
        JsonObject item = new JsonObject();
        item.addProperty("kind", SceneView.lower(record.kind()));
        if (record.blockType() != null) item.addProperty("block", record.blockType());
        item.add("position", SceneView.position(record.position()));
        if (self != null) item.addProperty("distance", SceneView.distance(self, record.position()));
        // 容器带最后由谁放下：只对容器记录给，问不到就不出字段（07 写明"没带就是还没问到"）。
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
}
