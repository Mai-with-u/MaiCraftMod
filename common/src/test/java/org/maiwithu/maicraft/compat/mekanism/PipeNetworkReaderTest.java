// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.mekanism;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.ability.machine.spi.NetworkSummary;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.hands.GearChanges;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.permission.GuessesPlayerMade;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.serverlink.ClientRequest;
import org.maiwithu.maicraft.game.serverlink.ServerReads;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 通用机械管网的存量读数：服务端没答这个操作时照写读不到存量并带原因，问话在路上时 summaryPending 为真，
 * 收回后把服务端的整网读数与存量配上客户端数的管道；物品管网不在网里存东西，不问。
 */
class PipeNetworkReaderTest {

    private static final BlockPos CABLE = new BlockPos(10, 64, -3);
    private static final String ENERGY_NET = "energy@10,64,-3";

    @Test
    void 服务器没装通用机械读数时照写读不到存量并带原因() {
        ScriptedServerReads reads = new ScriptedServerReads(false);
        PipeNetworkReader reader = reader("energy", reads);

        NetworkSummary summary = reader.withStock(ENERGY_NET, CABLE, counted(5, 2));
        assertTrue(summary.readable(), "客户端数出的管道照样算读到了");
        assertEquals(5L, summary.readings().get("pipes"));
        assertTrue(summary.note().startsWith("读不到存量"), summary.note());
        assertTrue(summary.note().contains("服务器没装 MaiCraft 的通用机械读数"), summary.note());
        assertTrue(reads.asked.isEmpty());
        assertFalse(reader.summaryPending(ENERGY_NET));
    }

    @Test
    void 问话在路上时说存量在要_收回后给整网读数与存量() {
        ScriptedServerReads reads = new ScriptedServerReads(true);
        PipeNetworkReader reader = reader("energy", reads);

        // 首读：问话刚交出去，先交客户端数的管道，说存量在要；交付方看 summaryPending 等它到账。
        NetworkSummary asking = reader.withStock(ENERGY_NET, CABLE, counted(5, 2));
        assertTrue(asking.note().contains("存量正在向服务端要"), asking.note());
        assertEquals(5L, asking.readings().get("pipes"));
        assertTrue(reader.summaryPending(ENERGY_NET));
        assertEquals(1, reads.asked.size());
        assertEquals(CABLE.getX(), reads.bodies.getFirst().get("x").getAsInt());

        // 回答到了：整网的管道段数盖过客户端只数到的那几段，存量照服务端给的整数。
        JsonObject reply = new JsonObject();
        reply.addProperty("found", true);
        reply.addProperty("network", "5f1c");
        reply.addProperty("medium", "energy");
        reply.addProperty("pipes", 14);
        reply.addProperty("machines", 3);
        reply.addProperty("energy_stored", 6_400_000_000L);
        reply.addProperty("energy_max", 24_000_000_000L);
        reply.addProperty("energy_unit", "FE");
        reads.replies.put(reads.asked.getFirst(), answered(reads.asked.getFirst(), reply));
        assertFalse(reader.summaryPending(ENERGY_NET));

        NetworkSummary fresh = reader.withStock(ENERGY_NET, CABLE, counted(5, 2));
        assertTrue(fresh.readable());
        assertEquals(14L, fresh.readings().get("pipes"));
        assertEquals(6_400_000_000L, fresh.readings().get("energy_stored"));
        assertEquals(24_000_000_000L, fresh.readings().get("energy_max"));
        assertEquals("FE", fresh.readings().get("energy_unit"));
        assertFalse(fresh.readings().containsKey("network"), "网络编号用客户端那一个，不另给服务端的");
        assertTrue(fresh.note().isEmpty(), fresh.note());
        assertEquals(1, reads.asked.size(), "保质期内不重问");
    }

    @Test
    void 物品管网没有存量_不问服务端() {
        ScriptedServerReads reads = new ScriptedServerReads(true);
        PipeNetworkReader reader = reader("item", reads);

        NetworkSummary summary = reader.withStock("item@0,64,0", BlockPos.ZERO.above(64), counted(8, 4));
        assertTrue(summary.readable());
        assertTrue(summary.note().contains("物品管网没有存量"), summary.note());
        assertTrue(reads.asked.isEmpty());
        assertFalse(reader.summaryPending("item@0,64,0"));
    }

    @Test
    void 服务端说这一格不是这种管道时照留客户端读数并说明() {
        JsonObject gone = new JsonObject();
        gone.addProperty("found", false);
        NetworkSummary notFound = PipeNetworkReader.PipeStocks.of(ENERGY_NET, "energy", gone, counted(5, 2));
        assertTrue(notFound.readable());
        assertEquals(5L, notFound.readings().get("pipes"));
        assertTrue(notFound.note().startsWith("读不到存量：服务端说这一格现在不是"), notFound.note());

        JsonObject other = new JsonObject();
        other.addProperty("found", true);
        other.addProperty("medium", "fluid");
        NetworkSummary swapped = PipeNetworkReader.PipeStocks.of(ENERGY_NET, "energy", other, counted(5, 2));
        assertTrue(swapped.note().contains("fluid"), swapped.note());

        JsonObject failed = new JsonObject();
        failed.addProperty("failed", "那一格离角色太远，只答附近的网");
        NetworkSummary unanswered = PipeNetworkReader.PipeStocks.of(ENERGY_NET, "energy", failed, counted(5, 2));
        assertTrue(unanswered.note().contains("离角色太远"), unanswered.note());
    }

    @Test
    void 流体管网空着时点出管道里是空的() {
        JsonObject reply = new JsonObject();
        reply.addProperty("found", true);
        reply.addProperty("medium", "fluid");
        reply.addProperty("fluid_stored", 0);
        reply.addProperty("fluid_max", 6_000);
        reply.addProperty("fluid_flow", 0);
        NetworkSummary empty = PipeNetworkReader.PipeStocks.of("fluid@1,2,3", "fluid", reply, counted(6, 2));
        assertEquals("管道里是空的", empty.note());
        assertEquals(6_000L, empty.readings().get("fluid_max"));
    }

    private static Map<String, Object> counted(long pipes, long machines) {
        Map<String, Object> counted = new LinkedHashMap<>();
        counted.put("pipes", pipes);
        counted.put("machines", machines);
        return counted;
    }

    private static PipeNetworkReader reader(String medium, ScriptedServerReads reads) {
        return new PipeNetworkReader(medium, new MekanismCompat(new MekanismCompatTest.StubReads()), services(reads),
                () -> 1_000_000L);
    }

    /** 记下问出去的请求、按编号给回答的替身：回答没登记的当"还在等"。 */
    private static final class ScriptedServerReads implements ServerReads {
        final boolean available;
        final List<UUID> asked = new ArrayList<>();
        final List<JsonObject> bodies = new ArrayList<>();
        final Map<UUID, ClientRequest.Snapshot> replies = new LinkedHashMap<>();

        ScriptedServerReads(boolean available) {
            this.available = available;
        }

        @Override public boolean available(String operationId) {
            return available && operationId.equals(MekanismCompat.NETWORK_OPERATION);
        }

        @Override public Optional<UUID> ask(String operationId, JsonObject body) {
            UUID id = UUID.randomUUID();
            asked.add(id);
            bodies.add(body);
            return Optional.of(id);
        }

        @Override public Optional<ClientRequest.Snapshot> answer(UUID request) {
            ClientRequest.Snapshot reply = replies.get(request);
            return Optional.of(reply != null ? reply : new ClientRequest.Snapshot(request,
                    MekanismCompat.NETWORK_OPERATION, 1, false, ClientRequest.Status.PENDING,
                    ClientRequest.Effect.NOT_APPLIED, false, "", "", -1, new JsonObject()));
        }
    }

    /** 答上的一次：服务端抄回管网汇总。 */
    private static ClientRequest.Snapshot answered(UUID id, JsonObject result) {
        return new ClientRequest.Snapshot(id, MekanismCompat.NETWORK_OPERATION, 1, false,
                ClientRequest.Status.SUCCEEDED, ClientRequest.Effect.NOT_APPLIED, false, "", "", -1, result);
    }

    /** 不在世界里的玩家行为：角色为空，读数链路用得上的只有问话那一头。 */
    private static PlayerServices services(ServerReads reads) {
        Protection nobodyOwns = new Protection((dimension, x, y, z) -> Optional.empty(), List::of,
                name -> Optional.empty(), GuessesPlayerMade.NOTHING, "self");
        return new PlayerServices(() -> null, (target, permissions) -> null, new Interactions(null),
                itemId -> Optional.empty(), new GearChanges() {
                    @Override public Optional<Action> wear(String itemId, GearSlotName slot) { return Optional.empty(); }
                    @Override public Optional<Action> takeOff(GearSlotName slot) { return Optional.empty(); }
                }, inner -> inner, (request, permissions) -> null, nobodyOwns, itemId -> Set.of(),
                MenuLayouts.VANILLA, new BlockScanService(), reads, new BackpackView() {
                    @Override public List<BackpackStack> stacks() { return List.of(); }
                    @Override public int usedSlots() { return 0; }
                    @Override public int totalSlots() { return 36; }
                });
    }
}
