// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.part;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.MachineSettings;
import org.maiwithu.maicraft.ability.machine.spi.RunRequest;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2DeviceOps;
import org.maiwithu.maicraft.compat.ae2.Ae2MenuSlots;
import org.maiwithu.maicraft.game.serverlink.ClientRequest;
import org.maiwithu.maicraft.game.serverlink.ServerReads;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;

/**
 * ME IO 传输站（CellTransfer）与无线访问点补全（WirelessAccessPoint）离线测试：
 * 核对红石卡 ignore 附注、倒盘 FullRun 全流程、增幅卡精确范围与能耗公式（0/1/16/64）、
 * 未同步标注（绝不报假 16.0）以及按差额放取增幅卡。
 */
class CellTransferAndWirelessTest {

    private static final BlockPos AT = new BlockPos(0, 64, 0);

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 传输站配置切换与装红石卡设为忽略时附注低电平实效() {
        PartTestFakes.ScriptedSession session = new PartTestFakes.ScriptedSession(Ae2PartKeys.MENU_CELL_TRANSFER);
        session.settings.put("operation_mode", "empty");
        session.settings.put("fullness_mode", "empty");
        session.settings.put("redstone_mode", "high_signal");
        session.hasRedstoneCard = true;
        session.cycleOrders.put("operation_mode", List.of("empty", "fill"));
        session.cycleOrders.put("fullness_mode", List.of("empty", "half", "full"));
        session.cycleOrders.put("redstone_controlled", List.of("ignore", "low_signal", "high_signal"));

        Ae2PartCache cache = new Ae2PartCache();
        Ae2Compat compat = PartTestFakes.compat(PartTestFakes.cableGrid(AT, Map.of()), session);
        PlayerServices services = PartTestFakes.services(() -> null, ServerReads.NONE);
        CellTransferOps ops = new CellTransferOps(compat, session, cache);

        // 切换 operation_mode -> fill、redstone_mode -> ignore，刷新缓存后检查红石卡附注生效。
        Ae2MenuConfigAction.Spec opSpec = new Ae2MenuConfigAction.Spec(AT, null, "ME IO 传输站",
                Ae2PartKeys.MENU_CELL_TRANSFER, Ae2MenuConfigAction.Kind.CONFIG_BUTTON, "operation_mode", "fill",
                "operation_mode", -1, false, null);
        assertInstanceOf(ActionStatus.Done.class, runToEnd(new Ae2MenuConfigAction(compat, session, cache, services,
                opSpec, PartTestFakes.immediateOpening(session))));

        session.closed = false;
        Ae2MenuConfigAction.Spec rsSpec = new Ae2MenuConfigAction.Spec(AT, null, "ME IO 传输站",
                Ae2PartKeys.MENU_CELL_TRANSFER, Ae2MenuConfigAction.Kind.CONFIG_BUTTON, "redstone_mode", "ignore",
                "redstone_controlled", -1, false, null);
        assertInstanceOf(ActionStatus.Done.class, runToEnd(new Ae2MenuConfigAction(compat, session, cache, services,
                rsSpec, PartTestFakes.immediateOpening(session))));

        Map<String, Object> readings = ops.extraReadings(AT, null);
        assertEquals("fill", readings.get("operation_mode"));
        assertEquals("ignore", readings.get("redstone_mode"));
        assertEquals(CellTransferOps.REDSTONE_IGNORE_NOTE, readings.get("redstone_note"));
    }

    @Test
    void 传输站完整倒盘流程送入输入槽等转移后从输出槽收回背包() {
        PartTestFakes.ScriptedSession session = new PartTestFakes.ScriptedSession(Ae2PartKeys.MENU_CELL_TRANSFER);
        session.hasRedstoneCard = true;
        session.settings.put("redstone_mode", "ignore");
        for (int i = 0; i < 6; i++) {
            session.inputSlots.add(new Ae2PartControl.MenuSlotEntry(i, i, true, null, 0));
            session.outputSlots.add(new Ae2PartControl.MenuSlotEntry(6 + i, i, true, null, 0));
        }
        session.putPlayerItem(100, new ItemStack(Items.CHEST, 1));
        Ae2PartCache cache = new Ae2PartCache();

        RunRequest request = new RunRequest("minecraft:chest", 1, null, null, 0, 5);
        CellTransferRun run = new CellTransferRun(session, cache, AT, request,
                PartTestFakes.immediateOpening(session));

        ActionStatus status = ActionStatus.running();
        boolean simulatedTransfer = false;
        for (int i = 0; i < 40; i++) {
            status = run.tick(PartTestFakes.dummyTick());
            // 一旦元件进入输入槽 0，模拟传输站完成倒盘移入输出槽 0。
            if (!simulatedTransfer && !session.inputSlots.getFirst().isEmpty()) {
                String moved = session.inputSlots.getFirst().itemId();
                session.inputSlots.set(0, new Ae2PartControl.MenuSlotEntry(0, 0, true, null, 0));
                session.outputSlots.set(0, new Ae2PartControl.MenuSlotEntry(6, 0, true, moved, 1));
                simulatedTransfer = true;
            }
            if (!(status instanceof ActionStatus.Running)) break;
        }
        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(1, run.produced());
        assertTrue(run.complete());
        assertTrue(session.outputSlots.getFirst().isEmpty());
        assertTrue(run.notes().contains(CellTransferOps.REDSTONE_IGNORE_NOTE));
    }

    @Test
    void 无线访问点精确公式与未同步区分绝不返假十六格() {
        // 核对 AE2 精确公式：range = 16 + boosters^1.5；power_drain = 8 + boosters^(1 + boosters/64)。
        assertEquals(16.0, WirelessAccessPointOps.maxRange(0), 1e-6);
        assertEquals(17.0, WirelessAccessPointOps.maxRange(1), 1e-6);
        assertEquals(80.0, WirelessAccessPointOps.maxRange(16), 1e-6);
        assertEquals(528.0, WirelessAccessPointOps.maxRange(64), 1e-6);

        assertEquals(8.0, WirelessAccessPointOps.powerDrain(0), 1e-6);
        assertEquals(9.0, WirelessAccessPointOps.powerDrain(1), 1e-6);
        assertEquals(40.0, WirelessAccessPointOps.powerDrain(16), 1e-6);
        assertEquals(4104.0, WirelessAccessPointOps.powerDrain(64), 1e-6);

        Ae2PartCache cache = new Ae2PartCache();
        WirelessAccessPointOps ops = new WirelessAccessPointOps(Ae2PartControl.NONE, cache);

        // 未同步时绝不返回 16.0，必须明确标 boosters_synced=false 与 unknown。
        Map<String, Object> unsynced = ops.readingsFor(AT, ServerReads.NONE);
        assertEquals(false, unsynced.get("boosters_synced"));
        assertEquals("unknown", unsynced.get("boosters"));
        assertEquals("unknown", unsynced.get("range"));
        assertEquals("unknown", unsynced.get("power_drain"));

        // 收到服务端 ae2.device 增幅卡张数 16 后，立即按公式算出 80.0 格与 40.0 AE/t。
        JsonObject reply = new JsonObject();
        reply.addProperty("boosters", 16);
        cache.acceptServerDevice(AT, null, reply);
        Map<String, Object> synced = ops.readingsFor(AT, ServerReads.NONE);
        assertEquals(true, synced.get("boosters_synced"));
        assertEquals(16, synced.get("boosters"));
        assertEquals(80.0, (double) synced.get("range"), 1e-6);
        assertEquals(40.0, (double) synced.get("power_drain"), 1e-6);

        // 越界增幅卡参数直接拒收。
        PlayerServices services = PartTestFakes.services(() -> null, ServerReads.NONE);
        MachineSettings settings = ops.settings(AT, services).orElseThrow();
        Action over = settings.change(AT, "boosters", "65", Permissions.DEFAULT).orElseThrow();
        ActionStatus.Failed overFail = assertInstanceOf(ActionStatus.Failed.class, over.tick(PartTestFakes.dummyTick()));
        assertEquals(Problem.Kind.INVALID_PARAMETER, overFail.problem().kind());
    }

    @Test
    void 无线访问点按差额放取增幅卡并刷新缓存且缺卡报错() {
        PartTestFakes.ScriptedSession session = new PartTestFakes.ScriptedSession(Ae2PartKeys.MENU_WIRELESS_ACCESS_POINT);
        session.storageSlots.add(new Ae2PartControl.MenuSlotEntry(0, 0, true, null, 0));
        session.wirelessBoosters = 0;
        session.putPlayerItem(100, new ItemStack(Items.REDSTONE, 20));
        Ae2PartCache cache = new Ae2PartCache();

        // 1. 0 -> 16：从背包 20 张里放入 16 张，背包留 4 张，缓存刷新为 16。
        WirelessBoosterChange add = new WirelessBoosterChange(session, cache, AT, 16, "minecraft:redstone",
                PartTestFakes.immediateOpening(session));
        assertInstanceOf(ActionStatus.Done.class, runToEnd(add));
        assertEquals(16, session.wirelessBoosters);
        assertEquals(4, session.playerSlots.get(100).count());
        assertEquals(16, cache.syncedBoosters(AT, ServerReads.NONE).orElseThrow());

        // 2. 16 -> 10：按差额取出 6 张回到背包，槽内留 10 张，缓存刷新为 10。
        session.closed = false;
        WirelessBoosterChange shrink = new WirelessBoosterChange(session, cache, AT, 10, "minecraft:redstone",
                PartTestFakes.immediateOpening(session));
        assertInstanceOf(ActionStatus.Done.class, runToEnd(shrink));
        assertEquals(10, session.wirelessBoosters);
        assertEquals(10, cache.syncedBoosters(AT, ServerReads.NONE).orElseThrow());

        // 3. 10 -> 40（身上不够 30 张）：如实报 NEED_ITEM。
        session.closed = false;
        WirelessBoosterChange shortage = new WirelessBoosterChange(session, cache, AT, 40, "minecraft:redstone",
                PartTestFakes.immediateOpening(session));
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, runToEnd(shortage));
        assertEquals(Problem.Kind.NEED_ITEM, failed.problem().kind());
        assertFalse(failed.problem().message().isBlank());
    }

    @Test
    void 无线访问点减少增幅卡时背包原格溢出六十四会自动分两格放回() {
        PartTestFakes.ScriptedSession session = new PartTestFakes.ScriptedSession(Ae2PartKeys.MENU_WIRELESS_ACCESS_POINT);
        session.storageSlots.add(new Ae2PartControl.MenuSlotEntry(0, 0, true, "minecraft:redstone", 16));
        session.wirelessBoosters = 16;
        // 背包 100 格已有 60 张增幅卡，从访问点 16 张减到 6 张需取回 10 张：4 张补满 100 格至 64，剩余 6 张放进 101 格。
        session.putPlayerItem(100, new ItemStack(Items.REDSTONE, 60));
        Ae2PartCache cache = new Ae2PartCache();

        WirelessBoosterChange shrink = new WirelessBoosterChange(session, cache, AT, 6, "minecraft:redstone",
                PartTestFakes.immediateOpening(session));
        assertInstanceOf(ActionStatus.Done.class, runToEnd(shrink));
        assertEquals(6, session.wirelessBoosters);
        assertTrue(session.cursorEmpty());
        assertEquals(64, session.playerSlots.get(100).count());
        assertEquals(6, session.playerSlots.get(101).count());
        assertEquals(6, cache.syncedBoosters(AT, ServerReads.NONE).orElseThrow());
    }

    @Test
    void 注入服务端读数源后首次查看额外读数自动触发设备查询并合并权威值() {
        AtomicInteger askedCount = new AtomicInteger();
        UUID reqId = UUID.randomUUID();
        JsonObject reply = new JsonObject();
        reply.addProperty("found", true);
        JsonObject nestedReadings = new JsonObject();
        nestedReadings.addProperty("boosters", 16);
        reply.add("readings", nestedReadings);
        ServerReads serverReads = new ServerReads() {
            @Override public boolean available(String operationId) {
                return Ae2PartCache.DEVICE_OPERATION.equals(operationId);
            }
            @Override public Optional<UUID> ask(String operationId, JsonObject body) {
                askedCount.incrementAndGet();
                return Optional.of(reqId);
            }
            @Override public Optional<ClientRequest.Snapshot> answer(UUID request) {
                if (!reqId.equals(request)) return Optional.empty();
                return Optional.of(new ClientRequest.Snapshot(reqId, Ae2PartCache.DEVICE_OPERATION, 1, false,
                        ClientRequest.Status.SUCCEEDED, ClientRequest.Effect.APPLIED, false,
                        "ok", "", 10L, reply));
            }
        };

        Ae2Compat compat = PartTestFakes.compat(PartTestFakes.cableGrid(AT, Map.of()), Ae2MenuSlots.NONE);
        Ae2Parts parts = new Ae2Parts(compat, Ae2PartControl.NONE, () -> serverReads);
        Ae2DeviceOps wirelessOps = parts.deviceOps().get("ae2:wireless_access_point");

        // 第一次 extraReadings 发出异步 ae2.device 查询；第二次收到应答并直接返回精确范围与能耗。
        Map<String, Object> first = wirelessOps.extraReadings(AT, null);
        assertEquals(1, askedCount.get());
        assertEquals(false, first.get("boosters_synced"));

        Map<String, Object> second = wirelessOps.extraReadings(AT, null);
        assertEquals(true, second.get("boosters_synced"));
        assertEquals(16, second.get("boosters"));
        assertEquals(80.0, (double) second.get("range"), 1e-6);
        assertEquals(40.0, (double) second.get("power_drain"), 1e-6);
    }

    private static ActionStatus runToEnd(Action action) {
        ActionStatus last = ActionStatus.running();
        for (int i = 0; i < 80; i++) {
            last = action.tick(PartTestFakes.dummyTick());
            if (!(last instanceof ActionStatus.Running)) return last;
        }
        return last;
    }
}
