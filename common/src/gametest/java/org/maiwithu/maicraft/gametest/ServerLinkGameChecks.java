// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.gametest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

import org.maiwithu.maicraft.network.ProtocolJson;
import org.maiwithu.maicraft.server.BlockOwnershipRecord;
import org.maiwithu.maicraft.server.CharacterAccounts;
import org.maiwithu.maicraft.server.OwnershipQuery;
import org.maiwithu.maicraft.server.ServerConfirmations;
import org.maiwithu.maicraft.server.ServerLinkNetwork;
import org.maiwithu.maicraft.server.ServerLinkServices;
import org.maiwithu.maicraft.server.ServerOperationRegistry;

/**
 * 服务端链路的接线测试：放置与破坏的确认只推给有会话的角色；角色账号名单把名单外的人挡在
 * 握手与操作之外。按 ServerStartup 的做法组装注册表、链路与名单，从真实入口（放置钩子、
 * 协议信封）驱动；加载器发送通道之后的一段（payload 序列化、网络发送）不在游戏内测试的范围。
 */
public final class ServerLinkGameChecks {

    private ServerLinkGameChecks() {}

    /** 放置与破坏的确认要推给有会话的角色；没会话的玩家一个包都不多收。 */
    public static void confirmationsReachOnlyActiveSessions(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer active = GameTestPlayers.inside(helper);
        ServerPlayer idle = GameTestPlayers.at(helper, 7, 1, 7);
        // 保留全局归属记录原样，确认通道换成记录式的：推送列表是本测试唯一要观察的出口。
        // 换入与放置在同一个服务器刻里完成，不会撞上启动在刻末的重新登记。
        BlockOwnershipRecord ownership = ServerLinkServices.ownership(server);
        List<JsonObject> pushed = new ArrayList<>();
        ServerLinkServices.detach(server);
        ServerLinkServices.attachIfAbsent(server, () -> ownership,
                () -> new ServerConfirmations((player, envelope) -> pushed.add(envelope),
                        player -> player == active));
        // 有会话的角色放：推一条确认，信封写清动作、世界、格子、方块与服务器刻。
        helper.setBlock(4, 2, 4, Blocks.STONE);
        BlockPos placed = helper.absolutePos(new BlockPos(4, 3, 4));
        if (!GameTestPlayers.placeAs(active, Items.STONE, placed.below(), Direction.UP)) {
            helper.fail("原版放置没有成功，链路没走到确认那一步");
        }
        if (pushed.size() != 1) {
            helper.fail("有会话的角色放置后应推送一条确认，实际 " + pushed.size() + " 条");
        }
        assertEnvelopeIs(helper, pushed.get(0), "place", placed, "minecraft:stone");
        // 没会话的玩家放：不推。
        helper.setBlock(6, 2, 6, Blocks.STONE);
        BlockPos idlePlaced = helper.absolutePos(new BlockPos(6, 3, 6));
        if (!GameTestPlayers.placeAs(idle, Items.STONE, idlePlaced.below(), Direction.UP)) {
            helper.fail("原版放置没有成功，链路没走到确认那一步");
        }
        if (pushed.size() != 1) {
            helper.fail("没有会话的玩家放置不该推送确认，实际推了 " + (pushed.size() - 1) + " 条");
        }
        // 有会话的角色拆：推一条破坏确认。
        if (!active.gameMode.destroyBlock(placed)) {
            helper.fail("原版破坏没有成功，拆除钩子没被走到");
        }
        if (pushed.size() != 2) {
            helper.fail("有会话的角色破坏后应再推一条确认，实际 " + pushed.size() + " 条");
        }
        assertEnvelopeIs(helper, pushed.get(1), "break", placed, "minecraft:stone");
        helper.succeed();
    }

    /** 角色账号闸门：名单内的角色握手建会话、协商到归属查询并查询成功；名单外的握手被拒，请求连会话都挂不上。 */
    public static void characterAccountsGateHandshakeAndOperations(GameTestHelper helper) {
        ServerPlayer member = GameTestPlayers.inside(helper);
        ServerPlayer outsider = GameTestPlayers.at(helper, 7, 1, 7);
        // 名单写在测试自己的临时配置目录里：mock 玩家重名，靠 UUID 认人。
        Path configDirectory = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT)
                .resolve("maicraft-gametest-config");
        CharacterAccounts accounts = CharacterAccounts.read(configDirectory);
        accounts.add(member.getGameProfile().getId().toString());
        // 与 ServerStartup 同样的接线：注册表挂归属查询，准入交给账号名单。
        ServerOperationRegistry operations = new ServerOperationRegistry();
        operations.register(OwnershipQuery.OPERATION, OwnershipQuery.VERSION, false, new OwnershipQuery());
        operations.setPolicy((player, operation) -> accounts.admits(player));
        ServerLinkNetwork network = new ServerLinkNetwork(operations, accounts::admits);
        // 名单内的角色：握手建会话，协商清单里有归属查询。
        List<JsonObject> memberReplies = new ArrayList<>();
        network.receive(member, helloEnvelope(), memberReplies::add);
        JsonObject welcome = memberReplies.get(0);
        if (!"succeeded".equals(textOf(welcome, "status")) || welcome.get("sessionId") == null) {
            helper.fail("名单内的角色握手应建会话，实际：" + welcome);
        }
        if (welcome.getAsJsonObject("features") == null
                || welcome.getAsJsonObject("features").get(OwnershipQuery.OPERATION) == null) {
            helper.fail("归属查询应在协商清单里：" + welcome);
        }
        // 名单内的角色：真实请求信封走完整执行路径，答出完整的分页结构。
        List<JsonObject> queryReplies = new ArrayList<>();
        network.receive(member, requestEnvelope(welcome, "query-own-chunk",
                chunkBody(member.getBlockX() >> 4, member.getBlockZ() >> 4)), queryReplies::add);
        JsonObject receipt = queryReplies.get(0);
        if (!"succeeded".equals(textOf(receipt, "status"))
                || receipt.getAsJsonObject("result") == null
                || receipt.getAsJsonObject("result").get("owners") == null
                || receipt.getAsJsonObject("result").get("cells") == null
                || receipt.getAsJsonObject("result").get("more") == null) {
            helper.fail("名单内的角色查询应成功并答出分页结构，实际：" + receipt);
        }
        // 名单外的玩家：握手被拒，建不了会话；随后的请求按会话失效回，什么也做不了。
        List<JsonObject> outsiderReplies = new ArrayList<>();
        network.receive(outsider, helloEnvelope(), outsiderReplies::add);
        if (!"not_character_account".equals(textOf(outsiderReplies.get(0), "code"))) {
            helper.fail("名单外玩家握手应按 not_character_account 拒绝，实际：" + outsiderReplies.get(0));
        }
        List<JsonObject> outsiderRequests = new ArrayList<>();
        JsonObject fakeSession = new JsonObject();
        fakeSession.addProperty("kind", "request");
        fakeSession.addProperty("sessionId", "no-such-session");
        fakeSession.addProperty("dimension", "minecraft:overworld");
        fakeSession.addProperty("requestId", "outsider-tries");
        network.receive(outsider, fakeSession, outsiderRequests::add);
        if (!"session_expired".equals(textOf(outsiderRequests.get(0), "code"))) {
            helper.fail("名单外玩家的请求应按 session_expired 拒绝，实际：" + outsiderRequests.get(0));
        }
        helper.succeed();
    }

    /** 客户端首封信封的形状：协议版本、一次性随机数、控制代初值、自报支持的操作版本。 */
    private static JsonObject helloEnvelope() {
        JsonObject hello = new JsonObject();
        hello.addProperty("kind", "hello");
        hello.addProperty("bootstrap", ProtocolJson.BOOTSTRAP);
        hello.addProperty("clientNonce", UUID.randomUUID().toString());
        hello.addProperty("controlGeneration", 0);
        JsonObject features = new JsonObject();
        features.addProperty(OwnershipQuery.OPERATION, OwnershipQuery.VERSION);
        hello.add("features", features);
        return hello;
    }

    /** 握手后请求信封的形状：会话、世界、请求编号、操作与版本、控制代，操作参数收在 body 里。 */
    private static JsonObject requestEnvelope(JsonObject welcome, String requestId, JsonObject body) {
        JsonObject request = new JsonObject();
        request.addProperty("kind", "request");
        request.addProperty("sessionId", welcome.get("sessionId").getAsString());
        request.addProperty("dimension", welcome.get("dimension").getAsString());
        request.addProperty("requestId", requestId);
        request.addProperty("operationId", OwnershipQuery.OPERATION);
        request.addProperty("version", OwnershipQuery.VERSION);
        request.addProperty("controlGeneration", welcome.get("controlGeneration").getAsLong());
        request.add("body", body);
        return request;
    }

    private static JsonObject chunkBody(int chunkX, int chunkZ) {
        JsonObject body = new JsonObject();
        body.addProperty("chunk_x", chunkX);
        body.addProperty("chunk_z", chunkZ);
        return body;
    }

    private static void assertEnvelopeIs(GameTestHelper helper, JsonObject envelope, String action,
                                         BlockPos position, String blockId) {
        if (!"confirmation".equals(textOf(envelope, "kind"))
                || !textOf(envelope, "action").equals(action)
                || !textOf(envelope, "block").equals(blockId)
                || envelope.getAsJsonObject("position").get("x").getAsInt() != position.getX()
                || envelope.getAsJsonObject("position").get("y").getAsInt() != position.getY()
                || envelope.getAsJsonObject("position").get("z").getAsInt() != position.getZ()
                || !textOf(envelope, "dimension").equals("minecraft:overworld")
                || envelope.get("serverTick") == null) {
            helper.fail("确认信封内容对不上（期望 " + action + " " + position + " " + blockId + "）：" + envelope);
        }
    }

    private static String textOf(JsonObject object, String key) {
        return object.get(key) == null ? "" : object.get(key).getAsString();
    }
}
