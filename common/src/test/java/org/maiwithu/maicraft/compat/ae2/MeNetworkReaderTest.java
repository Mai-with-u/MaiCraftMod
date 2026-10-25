// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.AbstractContainerMenu;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ME 网络读取器的读数节奏：问出去没收回时读数算在路上、收回的读数如实给，
 * 一答没答上（请求退场）在限额内马上重问，不让调用方读到滞后的旧值还不自知。
 */
class MeNetworkReaderTest {

    private static final BlockPos DRIVE = new BlockPos(58, 65, -48);
    private static final String NETWORK_ID = "me@58,65,-48";

    @Test
    void 首次读数在问话收回前如实说正在问_收回后给新值() {
        ScriptedServerReads reads = new ScriptedServerReads();
        MeNetworkReader reader = new MeNetworkReader(compat(), services(reads));
        reader.seen(NETWORK_ID, DRIVE);

        // 首读：问话刚交出去，服务端还没答，如实说在路上。
        NetworkSummary asking = reader.summary(NETWORK_ID);
        assertFalse(asking.readable());
        assertTrue(asking.note().contains("正在向服务端要"));
        assertTrue(reader.summaryPending(NETWORK_ID));
        assertEquals(1, reads.asked.size());

        // 回答到了：再读就是服务端刚答的数。
        reads.replies.put(reads.asked.getFirst(), answered(reads.asked.getFirst(), 67, 2));
        assertFalse(reader.summaryPending(NETWORK_ID));
        NetworkSummary fresh = reader.summary(NETWORK_ID);
        assertTrue(fresh.readable());
        assertEquals(67.0, fresh.readings().get("stored_amount"));
        assertEquals(2.0, fresh.readings().get("stored_types"));
    }

    @Test
    void 新鲜期内复用回答不重问() {
        ScriptedServerReads reads = new ScriptedServerReads();
        MeNetworkReader reader = new MeNetworkReader(compat(), services(reads));
        reader.seen(NETWORK_ID, DRIVE);

        reader.summary(NETWORK_ID);
        reads.replies.put(reads.asked.getFirst(), answered(reads.asked.getFirst(), 67, 2));
        reader.summary(NETWORK_ID);

        // 保质期内再读直接复用这一答，不再向服务端问第二遍。
        int before = reads.asked.size();
        NetworkSummary served = reader.summary(NETWORK_ID);
        assertEquals(before, reads.asked.size());
        assertTrue(served.note().isEmpty());
    }

    @Test
    void 一答没答上时马上重问_重问答上了给新值() {
        ScriptedServerReads reads = new ScriptedServerReads();
        MeNetworkReader reader = new MeNetworkReader(compat(), services(reads));
        reader.seen(NETWORK_ID, DRIVE);

        // 第一问被会话轮换退场：等回答的检查会先收到失败，随即自动重问。
        reader.summary(NETWORK_ID);
        reads.replies.put(reads.asked.getFirst(), expired(reads.asked.getFirst()));
        assertTrue(reader.summaryPending(NETWORK_ID));
        assertEquals(2, reads.asked.size());

        // 重问答上了：读数就是新值，不再卡在"读不到"。
        reads.replies.put(reads.asked.getLast(), answered(reads.asked.getLast(), 104, 3));
        assertFalse(reader.summaryPending(NETWORK_ID));
        NetworkSummary fresh = reader.summary(NETWORK_ID);
        assertTrue(fresh.readable());
        assertEquals(104.0, fresh.readings().get("stored_amount"));
        assertFalse(fresh.note().contains("正在重问"));
    }

    /** 记下问出去的请求、按编号给回答的替身：回答没登记的当"还在等"。 */
    private static final class ScriptedServerReads implements ServerReads {
        final List<UUID> asked = new ArrayList<>();
        final Map<UUID, ClientRequest.Snapshot> replies = new LinkedHashMap<>();

        @Override public boolean available(String operationId) {
            return true;
        }

        @Override public Optional<UUID> ask(String operationId, JsonObject body) {
            UUID id = UUID.randomUUID();
            asked.add(id);
            return Optional.of(id);
        }

        @Override public Optional<ClientRequest.Snapshot> answer(UUID request) {
            ClientRequest.Snapshot reply = replies.get(request);
            // 没登记回答的一次按"还在等"回：约定里给空是"请求已经退场"。
            return Optional.ofNullable(reply != null ? reply : waiting(request));
        }

        /** 还在等的一次。 */
        private static ClientRequest.Snapshot waiting(UUID id) {
            return new ClientRequest.Snapshot(id, Ae2Compat.NETWORK_OPERATION, 1, false,
                    ClientRequest.Status.PENDING, ClientRequest.Effect.NOT_APPLIED, false, "", "", -1,
                    new JsonObject());
        }
    }

    /** 不在世界里的玩家行为：角色为空，读数链路用得上的只有问话那一头。 */
    private static PlayerServices services(ScriptedServerReads reads) {
        Protection nobodyOwns = new Protection((dimension, x, y, z) -> Optional.empty(), List::of,
                name -> Optional.empty(), GuessesPlayerMade.NOTHING, "self");
        return new PlayerServices(() -> null, (target, permissions) -> null, new Interactions(null),
                itemId -> Optional.empty(), new NoGear(), inner -> inner, (request, permissions) -> null, nobodyOwns,
                itemId -> Set.of(), MenuLayouts.VANILLA, new BlockScanService(), reads, emptyBackpack());
    }

    /** 空的联动读写端：读数链路碰不到终端界面。 */
    private static Ae2Compat compat() {
        return new Ae2Compat(new Ae2Terminals() {
            @Override public List<Ae2Terminals.Terminal> near(ClientLevel level, BlockPos center, int radius) {
                return List.of();
            }

            @Override public boolean stillThere(ClientLevel level, BlockPos block, Direction side) {
                return false;
            }
        }, new Ae2TerminalMenu() {
            @Override public boolean belongsTo(AbstractContainerMenu menu, ClientLevel level, BlockPos block,
                    Direction side) {
                return false;
            }

            @Override public Optional<Boolean> linked(AbstractContainerMenu menu) {
                return Optional.empty();
            }

            @Override public Optional<List<StockEntry>> stock(AbstractContainerMenu menu) {
                return Optional.empty();
            }

            @Override public void takeStack(AbstractContainerMenu menu, long serial) {}

            @Override public void takeOne(AbstractContainerMenu menu, long serial) {}

            @Override public void putBack(AbstractContainerMenu menu) {}
        }, new NoGrid());
    }

    private static BackpackView emptyBackpack() {
        return new BackpackView() {
            @Override public List<BackpackStack> stacks() { return List.of(); }
            @Override public int usedSlots() { return 0; }
            @Override public int totalSlots() { return 36; }
        };
    }

    private static final class NoGear implements GearChanges {
        @Override public Optional<Action> wear(String itemId, GearSlotName slot) {
            return Optional.empty();
        }

        @Override public Optional<Action> takeOff(GearSlotName slot) {
            return Optional.empty();
        }
    }

    /** 答上的一次：服务端抄回网络汇总。 */
    private static ClientRequest.Snapshot answered(UUID id, long storedAmount, int storedTypes) {
        JsonObject result = new JsonObject();
        result.addProperty("found", true);
        result.addProperty("stored_amount", storedAmount);
        result.addProperty("stored_types", storedTypes);
        return new ClientRequest.Snapshot(id, Ae2Compat.NETWORK_OPERATION, 1, false,
                ClientRequest.Status.SUCCEEDED, ClientRequest.Effect.NOT_APPLIED, false, "", "", -1, result);
    }

    /** 没答上的一次：只读请求被会话轮换退场。 */
    private static ClientRequest.Snapshot expired(UUID id) {
        return new ClientRequest.Snapshot(id, Ae2Compat.NETWORK_OPERATION, 1, false,
                ClientRequest.Status.FAILED, ClientRequest.Effect.NOT_APPLIED, true, "session_expired", "会话已经轮换",
                -1, new JsonObject());
    }
}
