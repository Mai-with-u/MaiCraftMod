// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.advanced;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.compat.ae2.advanced.Ae2AdvancedReads.QuantumCell;
import org.maiwithu.maicraft.game.serverlink.ClientRequest;
import org.maiwithu.maicraft.game.serverlink.ServerReads;

/** 量子环与量子链接仓离线测试：覆盖 3×3 平面审阅、四角禁止连线、跨维读数与服务端缓存刷新。 */
class QuantumRingTest {

    @Test
    void 校验三个平面的3x3x1量子环结构并准确报出缺块与加载提示() {
        BlockPos center = new BlockPos(10, 64, 20);
        Set<BlockPos> xzRing = new HashSet<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                xzRing.add(center.offset(dx, 0, dz));
            }
        }
        QuantumRing.StructureReview complete = QuantumRing.reviewStructure(center, xzRing::contains, center::equals);
        assertTrue(complete.valid());
        assertEquals("xz", complete.plane());
        assertTrue(complete.missingRings().isEmpty());
        assertTrue(complete.note().contains("区块"));

        // 缺一块环块时指出缺失坐标。
        BlockPos removed = center.offset(1, 0, 1);
        xzRing.remove(removed);
        QuantumRing.StructureReview incomplete = QuantumRing.reviewStructure(center, xzRing::contains, center::equals);
        assertFalse(incomplete.valid());
        assertEquals(List.of(removed), incomplete.missingRings());

        // 中心不是量子链接仓时直接指出中心格。
        QuantumRing.StructureReview wrongCenter = QuantumRing.reviewStructure(center, xzRing::contains, pos -> false);
        assertFalse(wrongCenter.valid());
        assertEquals(List.of(center), wrongCenter.missingRings());
    }

    @Test
    void 四角量子环与中心链接仓绝不暴露外部线缆接口_仅四边中点暴露() {
        BlockPos pos = new BlockPos(0, 64, 0);
        QuantumCell corner = new QuantumCell(false, true, true, true, true, 0L);
        QuantumCell edge = new QuantumCell(false, true, false, true, true, 0L);
        QuantumCell unformed = new QuantumCell(false, false, false, false, false, 0L);
        QuantumCell center = new QuantumCell(true, true, false, true, true, 0L);

        assertTrue(QuantumRing.exchangePoints(QuantumRing.RING_BLOCK, pos, Optional.of(corner)).isEmpty());
        assertTrue(QuantumRing.exchangePoints(QuantumRing.RING_BLOCK, pos, Optional.of(unformed)).isEmpty());
        assertTrue(QuantumRing.exchangePoints(QuantumRing.LINK_BLOCK, pos, Optional.of(center)).isEmpty());

        List<ExchangePoint> edgePoints = QuantumRing.exchangePoints(QuantumRing.RING_BLOCK, pos, Optional.of(edge));
        assertEquals(6, edgePoints.size());
        assertTrue(edgePoints.stream().allMatch(p -> "me".equals(p.network().id())));
    }

    @Test
    void 客户端读数与服务端跨维连通及频率正确合并() {
        QuantumCell cell = new QuantumCell(true, true, false, true, true, 0L);
        JsonObject serverReply = new JsonObject();
        serverReply.addProperty("found", true);
        JsonObject readings = new JsonObject();
        readings.addProperty("frequency", 987654321L);
        readings.addProperty("peer_connected", true);
        readings.addProperty("peer_cross_dimension", true);
        readings.addProperty("peer_dimension", "minecraft:the_nether");
        serverReply.add("readings", readings);

        MachineState withServer = QuantumRing.state(QuantumRing.LINK_BLOCK, cell,
                Optional.of(serverReply), Optional.empty(), Optional.empty());
        assertEquals(MachineState.Activity.RUNNING, withServer.activity());
        assertEquals(987654321L, withServer.readings().get("frequency"));
        assertEquals(true, withServer.readings().get("peer_connected"));
        assertEquals(true, withServer.readings().get("peer_cross_dimension"));
        assertEquals("minecraft:the_nether", withServer.readings().get("peer_dimension"));

        // 无服务端且未开菜单时，有奇点但客户端不知频率 -> 标为 unknown。
        MachineState clientOnly = QuantumRing.state(QuantumRing.LINK_BLOCK, cell,
                Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals("unknown", clientOnly.readings().get("frequency"));
        assertTrue(clientOnly.note().contains("ae2.device"));
    }

    @Test
    void 设备服务端只读缓存支持按格查询与保质期过期重问() {
        AtomicLong clock = new AtomicLong(1_000L);
        int[] askCount = {0};
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        JsonObject firstReply = deviceReply(101L);
        JsonObject secondReply = deviceReply(202L);

        ServerReads fakeReads = new ServerReads() {
            @Override public boolean available(String operationId) {
                return Ae2DeviceReadings.DEVICE_OPERATION.equals(operationId);
            }

            @Override public Optional<UUID> ask(String operationId, JsonObject body) {
                askCount[0]++;
                return Optional.of(askCount[0] == 1 ? firstId : secondId);
            }

            @Override public Optional<ClientRequest.Snapshot> answer(UUID request) {
                JsonObject res = request.equals(firstId) ? firstReply : secondReply;
                return Optional.of(new ClientRequest.Snapshot(request, Ae2DeviceReadings.DEVICE_OPERATION, 1, false,
                        ClientRequest.Status.SUCCEEDED, ClientRequest.Effect.APPLIED, false, "", "", 1L, res));
            }
        };

        Ae2DeviceReadings readings = new Ae2DeviceReadings(fakeReads, clock::get);
        BlockPos pos = new BlockPos(5, 64, 5);
        // 首次发起询问，回答在途为空。
        assertTrue(readings.latest(pos, null).isEmpty());
        assertEquals(1, askCount[0]);
        // 下一刻收回第一次回答并在保质期内复用，不重问。
        assertEquals(101L, Ae2DeviceReadings.readLong(readings.latest(pos, null).orElseThrow(), "frequency").orElse(0L));
        assertEquals(1, askCount[0]);
        // 超过保质期后重问，并收回新值。
        clock.addAndGet(Ae2DeviceReadings.FRESH_MILLIS + 100L);
        assertEquals(101L, Ae2DeviceReadings.readLong(readings.latest(pos, null).orElseThrow(), "frequency").orElse(0L));
        assertEquals(2, askCount[0]);
        assertEquals(202L, Ae2DeviceReadings.readLong(readings.latest(pos, null).orElseThrow(), "frequency").orElse(0L));
    }

    private static JsonObject deviceReply(long frequency) {
        JsonObject reply = new JsonObject();
        reply.addProperty("found", true);
        JsonObject readings = new JsonObject();
        readings.addProperty("frequency", frequency);
        reply.add("readings", readings);
        return reply;
    }
}
