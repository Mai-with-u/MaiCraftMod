// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.extendedae;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.behavior.menu.MenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.game.serverlink.ClientRequest;
import org.maiwithu.maicraft.game.serverlink.ServerReads;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 高频扩展网络设备、批次 2 设施与组装矩阵多方块以及中文知识页的离线单元测试：
 * 覆盖 20 槽扩展驱动器、扩展样板供应器、扩展接口/超大接口、扩展分子组装室、扩展 IO 传输站、
 * 原料缓存器、装罐机（顶底两面与 mode 切换）、无线连接器与集线器（disconnect_port）、
 * 组装矩阵（cancel_all 与服务端读数合并）以及知识页索引与全部挂载条目。
 */
class ExtendedAeNetworkDevicesAndKnowledgeTest {

    private static final BlockPos POS = new BlockPos(20, 65, -10);

    @Test
    void exDrive20SlotsAndIngredientBufferReadingsWork() {
        List<ExtendedAeReads.DriveCellView> cells = new ArrayList<>(20);
        for (int i = 0; i < 20; i++) {
            cells.add(i < 3
                    ? new ExtendedAeReads.DriveCellView(i, "ae2:item_storage_cell_64k", "not_empty")
                    : new ExtendedAeReads.DriveCellView(i, "", "absent"));
        }
        MachineState driveState = ExtendedAeNetworkOps.DriveOps.buildDriveState(
                new ExtendedAeReads.ExDriveView(true, cells));
        assertEquals(MachineState.Activity.RUNNING, driveState.activity());
        assertEquals(3, driveState.readings().get("occupied_slots"));
        assertEquals(17, driveState.readings().get("empty_slots"));
        assertEquals(20, driveState.readings().get("total_slots"));
        assertEquals("empty", driveState.readings().get("slot_19"));

        // 原料缓存器读数合并服务端 36 格内容。
        JsonObject reply = new JsonObject();
        reply.addProperty("found", true);
        JsonObject r = new JsonObject();
        JsonObject contents = new JsonObject();
        contents.addProperty("0", "minecraft:water ×4000");
        r.add("contents", contents);
        reply.add("readings", r);
        ExtendedAeDeviceReadings readings = new ExtendedAeDeviceReadings(stubServerReads(reply), () -> 1000L);
        readings.latest(POS); // 触发第一次问询并缓存。
        ExtendedAeFacilityOps.IngredientBufferOps bufOps = new ExtendedAeFacilityOps.IngredientBufferOps(readings);
        MachineState bufState = bufOps.state(POS, null).orElseThrow();
        assertEquals(36, bufState.readings().get("total_slots"));
        assertTrue(bufState.readings().containsKey("contents"));
    }

    @Test
    void canerWirelessHubAndAssemblerMatrixConfigurationsSendExpectedPackets() {
        List<String> sent = new ArrayList<>();
        String[] canerMode = {"fill"};
        String[] link2Status = {"working"};
        ExtendedAeReads reads = new ExtendedAeReads() {
            @Override public Optional<CanerMenuView> openCanerMenu() {
                return Optional.of(new CanerMenuView(canerMode[0]));
            }
            @Override public Optional<WirelessHubMenuView> openWirelessHubMenu() {
                List<HubLinkView> links = new ArrayList<>();
                for (int i = 0; i < WIRELESS_HUB_SLOTS; i++) {
                    links.add(new HubLinkView(i, i == 2 ? link2Status[0] : "unconnected", "", 0));
                }
                return Optional.of(new WirelessHubMenuView(12.5, 4, 32, links));
            }
            @Override public boolean isAssemblerMatrixMenuOpen() { return true; }
            @Override public boolean sendGenericAction(String action, Object... args) {
                if ("set".equals(action)) {
                    int ord = (Integer) args[0];
                    sent.add("set:" + ord);
                    canerMode[0] = ord == 1 ? "empty" : "fill";
                    return true;
                }
                if ("disconnect".equals(action)) {
                    int idx = (Integer) args[0];
                    sent.add("disconnect:" + idx);
                    if (idx == 2) link2Status[0] = "unconnected";
                    return true;
                }
                if ("cancel".equals(action)) {
                    sent.add("cancel");
                    return true;
                }
                return false;
            }
        };
        ExtendedAeCompat compat = new ExtendedAeCompat(reads);
        ExtendedAeDeviceReadings readings = new ExtendedAeDeviceReadings(ServerReads.NONE);
        MenuOpening open = instantOpening(new MinimalMenu());

        // 1. 装罐机仅顶底两面连网，且 mode=empty 发送 CEAEGenericPacket("set", 1)。
        ExtendedAeFacilityOps.CanerOps canerOps = new ExtendedAeFacilityOps.CanerOps(compat, readings, (p, perm) -> open);
        List<ExchangePoint> canerPoints = canerOps.exchangePoints(null, POS, null).orElseThrow();
        assertEquals(2, canerPoints.size());
        assertTrue(canerPoints.stream().allMatch(p -> p.side() == Direction.UP || p.side() == Direction.DOWN));
        Action canerChange = canerOps.settings(POS, null).orElseThrow()
                .change(POS, "mode", "empty", Permissions.DEFAULT).orElseThrow();
        assertInstanceOf(ActionStatus.Done.class, runUntilDone(canerChange));

        // 2. 无线集线器 disconnect_port=2 发送 CEAEGenericPacket("disconnect", 2)。
        ExtendedAeFacilityOps.WirelessOps hubOps = new ExtendedAeFacilityOps.WirelessOps(
                compat, readings, true, (p, perm) -> open);
        Action disconnect = hubOps.settings(POS, null).orElseThrow()
                .change(POS, "disconnect_port", "2", Permissions.DEFAULT).orElseThrow();
        assertInstanceOf(ActionStatus.Done.class, runUntilDone(disconnect));

        // 3. 组装矩阵 cancel_all=true 发送 CEAEGenericPacket("cancel")，且状态读数包含服务端集群统计。
        ExtendedAeFacilityOps.AssemblerMatrixOps matrixOps = new ExtendedAeFacilityOps.AssemblerMatrixOps(
                compat, readings, (p, perm) -> open);
        Action cancel = matrixOps.settings(POS, null).orElseThrow()
                .change(POS, "cancel_all", "true", Permissions.DEFAULT).orElseThrow();
        assertInstanceOf(ActionStatus.Done.class, runUntilDone(cancel));
        assertEquals(List.of("set:1", "disconnect:2", "cancel"), sent);

        JsonObject matrixReply = new JsonObject();
        matrixReply.addProperty("found", true);
        JsonObject mr = new JsonObject();
        mr.addProperty("pattern_cores", 4);
        mr.addProperty("crafter_cores", 6);
        mr.addProperty("speed_cores", 2);
        mr.addProperty("busy_crafters", 1);
        mr.addProperty("pattern_count", 96);
        matrixReply.add("readings", mr);
        MachineState matrixState = ExtendedAeFacilityOps.AssemblerMatrixOps.buildMatrixState(
                new ExtendedAeReads.MatrixView(true, true, true), Optional.of(matrixReply));
        assertEquals(MachineState.Activity.RUNNING, matrixState.activity());
        assertEquals(4L, matrixState.readings().get("pattern_cores"));
        assertEquals(96L, matrixState.readings().get("pattern_count"));
    }

    @Test
    void patternProviderTransferAndKnowledgePageWork() {
        JsonObject reply = new JsonObject();
        reply.addProperty("found", true);
        JsonObject settings = new JsonObject();
        settings.addProperty("blocking_mode", "yes");
        settings.addProperty("lock_crafting_mode", "until_result");
        settings.addProperty("pattern_access_terminal", "yes");
        reply.add("settings", settings);
        JsonArray upgrades = new JsonArray();
        upgrades.add("ae2:speed_card");
        reply.add("upgrades", upgrades);
        JsonObject r = new JsonObject();
        r.addProperty("priority", 50);
        reply.add("readings", r);

        var providerSettings = ExtendedAeNetworkOps.PatternProviderOps.providerSettings(
                Optional.empty(), Optional.empty(), Optional.of(reply));
        assertTrue(providerSettings.stream().anyMatch(s -> s.key().equals("blocking_mode") && s.value().equals("yes")));
        assertTrue(providerSettings.stream().anyMatch(s -> s.key().equals("priority") && s.value().equals("50")));

        // 知识页索引、正文要点与方块/核心物品挂载核对。
        ExtendedAeKnowledge knowledge = new ExtendedAeKnowledge();
        assertEquals(1, knowledge.entries().size());
        assertEquals(ExtendedAeKnowledge.INDEX, knowledge.entries().getFirst().uri());
        KnowledgeDocument doc = knowledge.read(ExtendedAeKnowledge.INDEX);
        assertNotNull(doc);
        assertTrue(doc.text().contains("不能做"));
        assertTrue(doc.text().contains("恩特罗水晶"));
        assertTrue(doc.text().contains("仅相对背面一面"));
        assertTrue(doc.text().contains("extendedae:wireless_tool"));
        assertNull(knowledge.read("maicraft://knowledge/machine/extendedae/unknown"));

        assertFalse(knowledge.entriesAbout("extendedae:crystal_assembler").isEmpty());
        assertFalse(knowledge.entriesAbout("extendedae:assembler_matrix_crafter").isEmpty());
        assertFalse(knowledge.entriesAbout("extendedae:entro_seed").isEmpty());
        assertFalse(knowledge.entriesAbout("extendedae:wireless_tool").isEmpty());
        assertFalse(knowledge.entriesAbout("extendedae:pattern_provider_upgrade").isEmpty());
        assertTrue(knowledge.entriesAbout("minecraft:stone").isEmpty());
    }

    private static ServerReads stubServerReads(JsonObject reply) {
        UUID id = UUID.randomUUID();
        return new ServerReads() {
            @Override public boolean available(String operation) {
                return ExtendedAeDeviceReadings.DEVICE_OPERATION.equals(operation);
            }
            @Override public Optional<UUID> ask(String operation, JsonObject body) {
                return Optional.of(id);
            }
            @Override public Optional<ClientRequest.Snapshot> answer(UUID requestId) {
                if (!id.equals(requestId)) return Optional.empty();
                return Optional.of(new ClientRequest.Snapshot(id, ExtendedAeDeviceReadings.DEVICE_OPERATION,
                        1, false, ClientRequest.Status.SUCCEEDED, ClientRequest.Effect.APPLIED, false,
                        "ok", "", 1L, reply));
            }
        };
    }

    private static ActionStatus runUntilDone(Action action) {
        TickContext tick = TickContext.of(1L, null);
        for (int i = 0; i < 60; i++) {
            ActionStatus status = action.tick(tick);
            if (!(status instanceof ActionStatus.Running)) return status;
        }
        return ActionStatus.running();
    }

    private static MenuOpening instantOpening(OpenedMenu menu) {
        return new MenuOpening() {
            @Override public Optional<OpenedMenu> opened() { return Optional.of(menu); }
            @Override public ActionStatus tick(TickContext tick) { return ActionStatus.done(); }
            @Override public String describe() { return "假开窗"; }
        };
    }

    private static final class MinimalMenu implements OpenedMenu {
        boolean closed;
        @Override public Optional<MenuContent.Reading> reading() {
            MenuChannel ch = new MenuChannel() {
                @Override public boolean stillOpen() { return !closed; }
                @Override public boolean cursorCarrying() { return false; }
                @Override public SlotSnapshot cursor() { return SlotSnapshot.empty(); }
                @Override public boolean click(int slot, int button) { return true; }
                @Override public boolean quickMove(int slot) { return true; }
                @Override public boolean selectTradeOffer(int offerIndex) { return false; }
                @Override public void requestClose() { closed = true; }
            };
            MenuSlots slots = new MenuSlots() {
                @Override public String menuTypeId() { return "extendedae:caner"; }
                @Override public int slotCount() { return 0; }
                @Override public boolean playerBacked(int slot) { return false; }
                @Override public int playerInventoryIndex(int slot) { return 0; }
            };
            return Optional.of(new MenuContent.Reading(ch, slots, List.of(), List.of(), List.of(),
                    List.of(), List.of()));
        }
        @Override public boolean busy() { return false; }
        @Override public boolean cursorEmpty() { return true; }
        @Override public SlotSnapshot cursor() { return SlotSnapshot.empty(); }
        @Override public boolean quickMove(int slotId) { return true; }
        @Override public boolean click(int slotId, int button) { return true; }
        @Override public void noteCursorTakenFrom(int slotId) {}
        @Override public Action closing() {
            return new Action() {
                @Override public ActionStatus tick(TickContext tick) { closed = true; return ActionStatus.done(); }
                @Override public String describe() { return "假关窗"; }
            };
        }
        @Override public void abandon() { closed = true; }
    }
}
