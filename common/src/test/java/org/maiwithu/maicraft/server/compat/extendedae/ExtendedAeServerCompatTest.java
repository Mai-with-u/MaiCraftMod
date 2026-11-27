// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.compat.extendedae;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.network.ServerOperationException;
import org.maiwithu.maicraft.server.ServerOperationRegistry;
import org.maiwithu.maicraft.server.compat.AskedCell;
import org.maiwithu.maicraft.server.compat.ServerCompatRegistry;
import org.maiwithu.maicraft.server.compat.extendedae.ExtendedAeDeviceQuery.DeviceSnapshot;

/**
 * ExtendedAE 服务端只读操作 extendedae.device 离线单元测试：
 * 覆盖模块注册、128 格距离与同维已加载边界校验、未命中回答与完整设备快照 JSON 序列化。
 */
class ExtendedAeServerCompatTest {

    @Test
    void registersExtendedAeDeviceOperationVersion1() {
        ServerOperationRegistry operations = new ServerOperationRegistry();
        ServerCompatRegistry compatRegistry = new ServerCompatRegistry(operations);
        compatRegistry.add(new ExtendedAeServerCompat(ExtendedAeDeviceQuery.Reader.NONE));
        assertTrue(operations.registered(ExtendedAeServerCompat.OPERATION));
        assertEquals(Optional.empty(), ExtendedAeDeviceQuery.Reader.NONE.read(null, BlockPos.ZERO));
    }

    @Test
    void enforces128BlockRadiusSameDimensionAndLoadedChunkBoundary() {
        BlockPos playerAt = new BlockPos(100, 64, -50);
        JsonObject onEdge = coords(100 + AskedCell.MAX_DISTANCE, 64, -50);
        assertEquals(new BlockPos(228, 64, -50),
                ExtendedAeDeviceQuery.validateNearCell(playerAt, true, true, onEdge));

        // 超过 128 格必须按 too_far 拒绝。
        JsonObject tooFar = coords(100 + AskedCell.MAX_DISTANCE + 1, 64, -50);
        ServerOperationException farErr = assertThrows(ServerOperationException.class,
                () -> ExtendedAeDeviceQuery.validateNearCell(playerAt, true, true, tooFar));
        assertEquals("too_far", farErr.code());

        // 未加载或跨维度必须按 not_loaded 拒绝。
        ServerOperationException unloadedErr = assertThrows(ServerOperationException.class,
                () -> ExtendedAeDeviceQuery.validateNearCell(playerAt, true, false, onEdge));
        assertEquals("not_loaded", unloadedErr.code());

        ServerOperationException crossDimErr = assertThrows(ServerOperationException.class,
                () -> ExtendedAeDeviceQuery.validateNearCell(playerAt, false, true, onEdge));
        assertEquals("not_loaded", crossDimErr.code());

        // 缺少坐标按 bad_request 拒绝。
        JsonObject bad = new JsonObject();
        bad.addProperty("x", 100);
        ServerOperationException badErr = assertThrows(ServerOperationException.class,
                () -> ExtendedAeDeviceQuery.validateNearCell(playerAt, true, true, bad));
        assertEquals("bad_request", badErr.code());
    }

    @Test
    void serializesDeviceSnapshotAndNotFoundCleanly() {
        JsonObject notFound = ExtendedAeDeviceQuery.notFound();
        assertFalse(notFound.get("found").getAsBoolean());

        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("tank_fluid", "minecraft:water");
        readings.put("tank_amount_mb", 4000L);
        readings.put("output_sides", List.of("north", "east"));
        readings.put("ports", List.of(Map.of("index", 0, "frequency", 99L, "other_side", "10, 64, 20")));

        DeviceSnapshot snapshot = new DeviceSnapshot("crystal_assembler", "extendedae:crystal_assembler",
                Map.of("auto_export", "yes"),
                List.of("ae2:speed_card", "", "", ""),
                readings);
        JsonObject json = ExtendedAeDeviceQuery.toJson(snapshot);
        assertTrue(json.get("found").getAsBoolean());
        assertEquals("crystal_assembler", json.get("kind").getAsString());
        assertEquals("extendedae:crystal_assembler", json.get("block_id").getAsString());
        assertEquals("yes", json.getAsJsonObject("settings").get("auto_export").getAsString());
        assertEquals(4, json.getAsJsonArray("upgrades").size());
        JsonObject body = json.getAsJsonObject("readings");
        assertEquals("minecraft:water", body.get("tank_fluid").getAsString());
        assertEquals(4000L, body.get("tank_amount_mb").getAsLong());
        assertEquals(2, body.getAsJsonArray("output_sides").size());
        assertEquals(99L, body.getAsJsonArray("ports").get(0).getAsJsonObject().get("frequency").getAsLong());
    }

    private static JsonObject coords(int x, int y, int z) {
        JsonObject req = new JsonObject();
        req.addProperty("x", x);
        req.addProperty("y", y);
        req.addProperty("z", z);
        return req;
    }
}
