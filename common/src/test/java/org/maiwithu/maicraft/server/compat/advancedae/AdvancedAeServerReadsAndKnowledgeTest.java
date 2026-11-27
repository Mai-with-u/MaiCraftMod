// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.compat.advancedae;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.compat.advancedae.AdvancedAeKnowledge;
import org.maiwithu.maicraft.network.ServerOperationException;
import org.maiwithu.maicraft.server.ServerOperationRegistry;
import org.maiwithu.maicraft.server.compat.ServerCompatRegistry;

/**
 * 高级 AE 服务端只读操作（advanced_ae.device）边界、JSON 序列化与中文知识页单元测试。
 */
class AdvancedAeServerReadsAndKnowledgeTest {

    @Test
    void 服务端入口注册版本号为一的只读设备操作() {
        ServerOperationRegistry operations = new ServerOperationRegistry();
        ServerCompatRegistry registry = new ServerCompatRegistry(operations);
        registry.add(new AdvancedAeServerCompat(AdvancedAeServerReads.Reader.NONE));

        assertTrue(operations.registered("advanced_ae.device"));
        assertEquals(1, registry.modules().size());
        assertEquals(1, AdvancedAeServerReads.VERSION);
    }

    @Test
    void 一百二十八格视野边界与未加载及跨维和缺坐标均按规矩校验() {
        BlockPos playerAt = new BlockPos(10, 64, 10);
        // 刚好 128 格边界放行。
        JsonObject atEdge = coords(138, 64, 10);
        assertEquals(new BlockPos(138, 64, 10),
                AdvancedAeServerReads.validateNearCell(playerAt, true, true, atEdge));

        // 129 格超距拒绝。
        ServerOperationException tooFar = assertThrows(ServerOperationException.class,
                () -> AdvancedAeServerReads.validateNearCell(playerAt, true, true, coords(139, 64, 10)));
        assertEquals("too_far", tooFar.code());

        // 未加载区块拒绝。
        ServerOperationException unloaded = assertThrows(ServerOperationException.class,
                () -> AdvancedAeServerReads.validateNearCell(playerAt, true, false, coords(12, 64, 10)));
        assertEquals("not_loaded", unloaded.code());

        // 跨维拒绝。
        ServerOperationException otherDim = assertThrows(ServerOperationException.class,
                () -> AdvancedAeServerReads.validateNearCell(playerAt, false, true, coords(12, 64, 10)));
        assertEquals("not_loaded", otherDim.code());

        // 缺少坐标字段拒绝。
        JsonObject missingZ = new JsonObject();
        missingZ.addProperty("x", 10);
        missingZ.addProperty("y", 64);
        ServerOperationException badReq = assertThrows(ServerOperationException.class,
                () -> AdvancedAeServerReads.validateNearCell(playerAt, true, true, missingZ));
        assertEquals("bad_request", badReq.code());
    }

    @Test
    void 反应仓权威快照序列化完整保留双流体槽进度电量与输出面() {
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("working", true);
        readings.put("powered", true);
        readings.put("active", true);
        readings.put("input_fluid", "minecraft:water");
        readings.put("input_fluid_mb", 4000L);
        readings.put("input_fluid_capacity_mb", 16000L);
        readings.put("output_fluid", "advanced_ae:quantum_infusion");
        readings.put("output_fluid_mb", 2000L);
        readings.put("output_fluid_capacity_mb", 16000L);
        readings.put("processing_time", 80);
        readings.put("max_processing_time", 200);
        readings.put("energy_stored", 420000.0);
        readings.put("energy_capacity", 500000.0);
        readings.put("auto_export", "yes");
        readings.put("output_sides", List.of("top", "right"));
        readings.put("show_warning", false);
        readings.put("input_items", List.of("ae2:charged_certus_quartz_crystal x32", "minecraft:redstone x32"));
        readings.put("output_items", List.of("ae2:fluix_crystal x64"));

        AdvancedAeServerReads.DeviceSnapshot snapshot = new AdvancedAeServerReads.DeviceSnapshot(
                "reaction_chamber",
                "advanced_ae:reaction_chamber",
                Map.of("auto_export", "yes"),
                List.of("ae2:speed_card", "ae2:speed_card", "", ""),
                readings);

        JsonObject json = AdvancedAeServerReads.toJson(snapshot);
        assertTrue(json.get("found").getAsBoolean());
        assertEquals("reaction_chamber", json.get("kind").getAsString());
        assertEquals("advanced_ae:reaction_chamber", json.get("block_id").getAsString());
        assertEquals("yes", json.getAsJsonObject("settings").get("auto_export").getAsString());
        assertEquals(4, json.getAsJsonArray("upgrades").size());
        JsonObject r = json.getAsJsonObject("readings");
        assertEquals("minecraft:water", r.get("input_fluid").getAsString());
        assertEquals(4000L, r.get("input_fluid_mb").getAsLong());
        assertEquals("advanced_ae:quantum_infusion", r.get("output_fluid").getAsString());
        assertEquals(80, r.get("processing_time").getAsInt());
        assertEquals(200, r.get("max_processing_time").getAsInt());
        assertEquals(420000.0, r.get("energy_stored").getAsDouble());
        JsonArray sides = r.getAsJsonArray("output_sides");
        assertEquals(2, sides.size());
        assertEquals("top", sides.get(0).getAsString());
        assertFalse(AdvancedAeServerReads.notFound().get("found").getAsBoolean());
    }

    @Test
    void 知识页挂载反应仓与核心批量产物并写清要点() {
        AdvancedAeKnowledge knowledge = new AdvancedAeKnowledge();
        assertEquals(1, knowledge.entries().size());
        assertEquals(AdvancedAeKnowledge.INDEX, knowledge.entries().getFirst().uri());

        assertFalse(knowledge.entriesAbout("advanced_ae:reaction_chamber").isEmpty());
        assertFalse(knowledge.entriesAbout("extendedae:entro_crystal").isEmpty());
        assertFalse(knowledge.entriesAbout("ae2:fluix_crystal").isEmpty());
        assertFalse(knowledge.entriesAbout("advanced_ae:quantum_infusion_bucket").isEmpty());
        assertTrue(knowledge.entriesAbout("minecraft:dirt").isEmpty());

        var doc = knowledge.read(AdvancedAeKnowledge.INDEX);
        assertNotNull(doc);
        assertTrue(doc.text().contains("64 颗"));
        assertTrue(doc.text().contains("无需开界面"));
        assertTrue(doc.text().contains("能源元件"));
        assertTrue(doc.text().contains("flush_fluid_in"));
        assertTrue(doc.text().contains("output_side."));
        assertNull(knowledge.read("maicraft://knowledge/machine/advanced_ae/missing"));
    }

    private static JsonObject coords(int x, int y, int z) {
        JsonObject obj = new JsonObject();
        obj.addProperty("x", x);
        obj.addProperty("y", y);
        obj.addProperty("z", z);
        return obj;
    }
}
