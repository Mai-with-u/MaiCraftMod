// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.network.ServerOperationException;
import org.maiwithu.maicraft.server.ServerOperationRegistry;
import org.maiwithu.maicraft.server.compat.AskedCell;
import org.maiwithu.maicraft.server.compat.ServerCompatModule;
import org.maiwithu.maicraft.server.compat.ServerCompatRegistry;
import org.maiwithu.maicraft.server.compat.ae2.Ae2DeviceServerReads.DeviceSnapshot;

/** AE2 服务端设备级只读操作 ae2.device：验证注册、方向解析、128 格同维已加载边界与只读 JSON 写出。 */
class Ae2DeviceServerReadsTest {

    @Test
    void 能向服务端登记表注册ae2点device只读操作() {
        ServerOperationRegistry operations = new ServerOperationRegistry();
        ServerCompatRegistry compatRegistry = new ServerCompatRegistry(operations);
        ServerCompatModule stub = new ServerCompatModule("ae2", "应用能源2") {
            @Override public void contribute(ServerCompatRegistry registry) {
                Ae2DeviceServerReads.register(this, registry, Ae2DeviceServerReads.Reader.NONE);
            }
        };
        compatRegistry.add(stub);
        assertTrue(operations.registered(Ae2DeviceServerReads.OPERATION));
    }

    @Test
    void 解析请求里的可选方向并拒绝非法面名() {
        JsonObject missing = new JsonObject();
        assertNull(Ae2DeviceServerReads.parseSide(missing));

        JsonObject north = new JsonObject();
        north.addProperty("side", "NORTH");
        assertEquals(Direction.NORTH, Ae2DeviceServerReads.parseSide(north));

        JsonObject bad = new JsonObject();
        bad.addProperty("side", "diagonal");
        ServerOperationException thrown = assertThrows(ServerOperationException.class,
                () -> Ae2DeviceServerReads.parseSide(bad));
        assertEquals("bad_request", thrown.code());
    }

    @Test
    void 严格遵守128格距离与同维已加载边界() {
        BlockPos playerAt = new BlockPos(100, 64, 100);
        JsonObject atEdge = coords(100 + AskedCell.MAX_DISTANCE, 64, 100);
        assertEquals(new BlockPos(228, 64, 100),
                Ae2DeviceServerReads.validateNearCell(playerAt, true, true, atEdge));

        // 超过 128 格必须按 too_far 拒绝。
        JsonObject tooFar = coords(100 + AskedCell.MAX_DISTANCE + 1, 64, 100);
        ServerOperationException farError = assertThrows(ServerOperationException.class,
                () -> Ae2DeviceServerReads.validateNearCell(playerAt, true, true, tooFar));
        assertEquals("too_far", farError.code());

        // 未加载或跨维必须按 not_loaded 拒绝。
        ServerOperationException unloadedError = assertThrows(ServerOperationException.class,
                () -> Ae2DeviceServerReads.validateNearCell(playerAt, true, false, atEdge));
        assertEquals("not_loaded", unloadedError.code());

        ServerOperationException crossDimError = assertThrows(ServerOperationException.class,
                () -> Ae2DeviceServerReads.validateNearCell(playerAt, false, true, atEdge));
        assertEquals("not_loaded", crossDimError.code());

        // 缺坐标按 bad_request 拒绝。
        JsonObject incomplete = new JsonObject();
        incomplete.addProperty("x", 100);
        ServerOperationException badReq = assertThrows(ServerOperationException.class,
                () -> Ae2DeviceServerReads.validateNearCell(playerAt, true, true, incomplete));
        assertEquals("bad_request", badReq.code());
    }

    @Test
    void 设备快照完整序列化为只读JSON并区分未命中() {
        JsonObject empty = Ae2DeviceServerReads.notFound();
        assertFalse(empty.get("found").getAsBoolean());

        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("p2p_output", true);
        readings.put("p2p_frequency", 12345);
        readings.put("enchantments", Map.of("minecraft:fortune", 3));
        DeviceSnapshot part = new DeviceSnapshot("cable_part", "ae2:cable_bus", "east", "ae2:me_p2p_tunnel",
                Map.of("redstone_controlled", "ignore"),
                List.of("ae2:speed_card", "ae2:capacity_card"),
                readings);

        JsonObject json = Ae2DeviceServerReads.toJson(part);
        assertTrue(json.get("found").getAsBoolean());
        assertEquals("cable_part", json.get("kind").getAsString());
        assertEquals("ae2:cable_bus", json.get("block_id").getAsString());
        assertEquals("east", json.get("side").getAsString());
        assertEquals("ae2:me_p2p_tunnel", json.get("part_id").getAsString());
        assertEquals("ignore", json.getAsJsonObject("settings").get("redstone_controlled").getAsString());
        assertEquals(2, json.getAsJsonArray("upgrades").size());
        JsonObject body = json.getAsJsonObject("readings");
        assertTrue(body.get("p2p_output").getAsBoolean());
        assertEquals(12345, body.get("p2p_frequency").getAsInt());
        assertEquals(3, body.getAsJsonObject("enchantments").get("minecraft:fortune").getAsInt());
    }

    @Test
    void 空读端返回空快照() {
        assertEquals(Optional.empty(), Ae2DeviceServerReads.Reader.NONE.read(null, BlockPos.ZERO, null));
    }

    private static JsonObject coords(int x, int y, int z) {
        JsonObject req = new JsonObject();
        req.addProperty("x", x);
        req.addProperty("y", y);
        req.addProperty("z", z);
        return req;
    }
}
