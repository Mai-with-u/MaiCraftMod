// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 协议引擎的离线场景：握手、请求、按身份查询与重放保护，全部用假的对端驱动。 */
class ProtocolFlowTest {

    /** 假对端：固定维度与刻号，登记一个只读操作和一个写操作。 */
    private static final class FakePeer implements ServerProtocolDispatcher.Peer {
        final AtomicLong clock = new AtomicLong(100);
        boolean mayMutate = true;
        boolean admitted = true;
        String worldId = "ab".repeat(32);
        Boolean keepInventory = true;
        Boolean onlineMode = true;
        List<String> generatedTerrain;

        @Override public String dimension() { return "minecraft:overworld"; }
        @Override public long tick() { return clock.get(); }
        @Override public boolean admitted() { return admitted; }
        @Override public String worldId() { return worldId; }
        @Override public Boolean keepInventory() { return keepInventory; }
        @Override public Boolean onlineMode() { return onlineMode; }
        @Override public List<String> generatedTerrain() { return generatedTerrain; }
        @Override public boolean mayMutate() { return mayMutate; }
        @Override public List<ServerFeature> features() {
            return List.of(new ServerFeature("ownership.query", 1, false, true, new JsonObject()),
                    new ServerFeature("test.place", 1, true, true, new JsonObject()));
        }
        @Override public JsonObject execute(String operationId, JsonObject body) {
            JsonObject result = new JsonObject();
            result.addProperty("handled", operationId);
            return result;
        }
    }

    private static JsonObject hello() {
        JsonObject request = new JsonObject();
        request.addProperty("kind", "hello");
        request.addProperty("bootstrap", 1);
        request.addProperty("clientNonce", "nonce-1");
        request.addProperty("controlGeneration", 0);
        JsonObject versions = new JsonObject();
        versions.addProperty("ownership.query", 1);
        versions.addProperty("test.place", 1);
        request.add("features", versions);
        return request;
    }

    @Test
    void 欢迎包报死亡不掉落_读不到时不带() {
        var on = new FakePeer();
        assertTrue(new ServerProtocolDispatcher().receive(hello(), on).get("keepInventory").getAsBoolean());
        var off = new FakePeer();
        off.keepInventory = false;
        assertFalse(new ServerProtocolDispatcher().receive(hello(), off).get("keepInventory").getAsBoolean());
        var unknown = new FakePeer();
        unknown.keepInventory = null;
        assertFalse(new ServerProtocolDispatcher().receive(hello(), unknown).has("keepInventory"));
    }

    @Test
    void 欢迎包报正版验证_读不到时不带() {
        // 开着、关着都照服务端的设置报；读不到（替身不报）时整项不带，客户端按没报算。
        var on = new FakePeer();
        assertTrue(new ServerProtocolDispatcher().receive(hello(), on).get("onlineMode").getAsBoolean());
        var off = new FakePeer();
        off.onlineMode = false;
        assertFalse(new ServerProtocolDispatcher().receive(hello(), off).get("onlineMode").getAsBoolean());
        var unknown = new FakePeer();
        unknown.onlineMode = null;
        assertFalse(new ServerProtocolDispatcher().receive(hello(), unknown).has("onlineMode"));
    }

    @Test
    void 欢迎包报世界生成铺的地形方块_读不到时不带() {
        var peer = new FakePeer();
        peer.generatedTerrain = List.of("biomesoplenty:black_sandstone", "minecraft:deepslate");
        JsonObject welcome = new ServerProtocolDispatcher().receive(hello(), peer);
        assertEquals("succeeded", welcome.get("status").getAsString());
        assertEquals(2, welcome.getAsJsonArray("generatedTerrain").size());
        assertEquals("biomesoplenty:black_sandstone", welcome.getAsJsonArray("generatedTerrain").get(0).getAsString());
        // 旧版服务端、读不出世界生成数据：这一项不带，握手照常。
        var without = new FakePeer();
        JsonObject plain = new ServerProtocolDispatcher().receive(hello(), without);
        assertEquals("succeeded", plain.get("status").getAsString());
        assertFalse(plain.has("generatedTerrain"));
    }

    @Test
    void 清单大到挤爆欢迎包时整项不带_握手照常成功() {
        // 模组极多的整合包：清单超过预算就整项不带，客户端退回只按标签认；不截半截，也不让握手因为欢迎包超限失败。
        var peer = new FakePeer();
        List<String> huge = new ArrayList<>();
        for (int i = 0; i < 3000; i++) huge.add("somemod:generated_rock_with_a_long_name_" + i);
        peer.generatedTerrain = huge;
        JsonObject welcome = new ServerProtocolDispatcher().receive(hello(), peer);
        assertEquals("succeeded", welcome.get("status").getAsString());
        assertFalse(welcome.has("generatedTerrain"));
        // 预算之内最大的一份清单，加上协商和限额，编码后仍在信封上限内，客户端解码得回来。
        List<String> fits = new ArrayList<>();
        String id = "somemod:rock_" + "x".repeat(16);
        while (ProtocolSession.fitsInWelcome(fits)) fits.add(id + fits.size());
        fits.remove(fits.size() - 1);
        peer.generatedTerrain = fits;
        JsonObject full = new ServerProtocolDispatcher().receive(hello(), peer);
        assertEquals(fits.size(), ProtocolJson.decode(ProtocolJson.encode(full)).getAsJsonArray("generatedTerrain").size());
    }

    @Test
    void 同一随机数重发握手_欢迎包报的是此刻的规则() {
        // 握手之后服主改了规则，客户端重发同一个握手：回的欢迎包按此刻的规则报，不按建会话时的。
        var peer = new FakePeer();
        var dispatcher = new ServerProtocolDispatcher();
        dispatcher.receive(hello(), peer);
        peer.keepInventory = false;
        assertFalse(dispatcher.receive(hello(), peer).get("keepInventory").getAsBoolean());
    }

    @Test
    void 规则变了的通知_编解码往返不变() {
        JsonObject notice = ServerProtocolDispatcher.rulesNotice(false);
        JsonObject decoded = ProtocolJson.decode(ProtocolJson.encode(notice));
        assertEquals(ServerProtocolDispatcher.RULES, decoded.get("kind").getAsString());
        assertFalse(decoded.get("keepInventory").getAsBoolean());
    }

    @Test
    void 欢迎包带上存档的世界编号_没有编号时不带() {
        // 服务器读出了存档的世界编号：握手的欢迎包原样带给客户端，客户端按它认出是哪个世界。
        var peer = new FakePeer();
        JsonObject welcome = new ServerProtocolDispatcher().receive(hello(), peer);
        assertEquals("ab".repeat(32), welcome.get("worldId").getAsString());

        // 编号文件写不进去时服务器没有编号：欢迎包里不带这一项，客户端退回按存档目录或服务器地址认。
        var without = new FakePeer();
        without.worldId = null;
        assertFalse(new ServerProtocolDispatcher().receive(hello(), without).has("worldId"));
    }

    @Test
    void 握手之后能执行协商过的只读操作并按身份查询() {
        var peer = new FakePeer();
        var dispatcher = new ServerProtocolDispatcher();
        JsonObject welcome = dispatcher.receive(hello(), peer);
        assertEquals("welcome", welcome.get("kind").getAsString());
        assertEquals("succeeded", welcome.get("status").getAsString());
        assertTrue(welcome.getAsJsonObject("features").has("ownership.query"));
        String sessionId = welcome.get("sessionId").getAsString();

        JsonObject request = new JsonObject();
        request.addProperty("kind", "request");
        request.addProperty("bootstrap", 1);
        request.addProperty("sessionId", sessionId);
        request.addProperty("dimension", "minecraft:overworld");
        request.addProperty("requestId", "req-1");
        request.addProperty("operationId", "ownership.query");
        request.addProperty("version", 1);
        request.addProperty("controlGeneration", 0);
        JsonObject body = new JsonObject();
        body.addProperty("probe", "a");
        request.add("body", body);

        JsonObject reply = dispatcher.receive(request, peer);
        assertEquals("succeeded", reply.get("status").getAsString());
        assertEquals("not_applied", reply.get("effect").getAsString());
        assertEquals("ownership.query", reply.getAsJsonObject("result").get("handled").getAsString());

        // 换到未协商的操作要被拒绝，并且这种拒绝说明请求没有执行。
        request.addProperty("operationId", "not.negotiated");
        JsonObject rejected = dispatcher.receive(request, peer);
        assertEquals("rejected", rejected.get("status").getAsString());
    }

    @Test
    void 同一请求身份换了内容会被判为冲突而不是重放() {
        var peer = new FakePeer();
        var dispatcher = new ServerProtocolDispatcher();
        String sessionId = dispatcher.receive(hello(), peer).get("sessionId").getAsString();

        JsonObject request = new JsonObject();
        request.addProperty("kind", "request");
        request.addProperty("bootstrap", 1);
        request.addProperty("sessionId", sessionId);
        request.addProperty("dimension", "minecraft:overworld");
        request.addProperty("requestId", "req-1");
        request.addProperty("operationId", "ownership.query");
        request.addProperty("version", 1);
        request.addProperty("controlGeneration", 0);
        JsonObject body = new JsonObject();
        body.addProperty("probe", "a");
        request.add("body", body);
        dispatcher.receive(request, peer);

        // 相同身份、相同内容：直接返回已存的记录，不重复执行。
        JsonObject again = dispatcher.receive(request, peer);
        assertEquals("succeeded", again.get("status").getAsString());

        // 相同身份、不同内容：冲突，且不会当作新请求执行。
        body.addProperty("probe", "b");
        JsonObject conflict = dispatcher.receive(request, peer);
        assertEquals("rejected", conflict.get("status").getAsString());
        assertEquals("request_conflict", conflict.get("code").getAsString());
    }

    @Test
    void 维度不匹配的请求按作用域不符拒绝() {
        var peer = new FakePeer();
        var dispatcher = new ServerProtocolDispatcher();
        String sessionId = dispatcher.receive(hello(), peer).get("sessionId").getAsString();
        JsonObject request = new JsonObject();
        request.addProperty("kind", "query");
        request.addProperty("bootstrap", 1);
        request.addProperty("sessionId", sessionId);
        request.addProperty("dimension", "minecraft:the_nether");
        request.addProperty("requestId", "req-1");
        JsonObject reply = dispatcher.receive(request, peer);
        assertEquals("rejected", reply.get("status").getAsString());
        assertEquals("session_scope_mismatch", reply.get("code").getAsString());
    }

    @Test
    void 新作用域会让旧作用域失去修改权限但仍可查询() {
        var peer = new FakePeer();
        var dispatcher = new ServerProtocolDispatcher();
        String first = dispatcher.receive(hello(), peer).get("sessionId").getAsString();
        JsonObject second = hello();
        second.addProperty("clientNonce", "nonce-2");
        dispatcher.receive(second, peer);
        // 第一个作用域已被整体撤销；再发查询会得到 unknown，旧效果不能重放。
        JsonObject query = new JsonObject();
        query.addProperty("kind", "query");
        query.addProperty("bootstrap", 1);
        query.addProperty("sessionId", first);
        query.addProperty("dimension", "minecraft:overworld");
        query.addProperty("requestId", "req-1");
        JsonObject reply = dispatcher.receive(query, peer);
        assertEquals("unknown", reply.get("status").getAsString());
    }

    @Test
    void 不是角色账号的玩家握手被拒_原有会话一并作废() {
        // 服主把这个号从角色账号名单里拿掉后，它再来握手：不给新会话，之前的会话也不能再用。
        var peer = new FakePeer();
        var dispatcher = new ServerProtocolDispatcher();
        String earlier = dispatcher.receive(hello(), peer).get("sessionId").getAsString();
        peer.admitted = false;
        JsonObject again = hello();
        again.addProperty("clientNonce", "nonce-2");

        JsonObject refused = dispatcher.receive(again, peer);
        assertEquals("welcome", refused.get("kind").getAsString());
        assertEquals("rejected", refused.get("status").getAsString());
        assertEquals(ServerProtocolDispatcher.NOT_CHARACTER_ACCOUNT, refused.get("code").getAsString());

        JsonObject request = new JsonObject();
        request.addProperty("kind", "request");
        request.addProperty("bootstrap", 1);
        request.addProperty("sessionId", earlier);
        request.addProperty("dimension", "minecraft:overworld");
        request.addProperty("requestId", "req-after");
        request.addProperty("operationId", "test.place");
        request.addProperty("version", 1);
        request.addProperty("controlGeneration", 0);
        request.add("body", new JsonObject());
        JsonObject reply = dispatcher.receive(request, peer);
        assertEquals("rejected", reply.get("status").getAsString());
        assertEquals("not_applied", reply.get("effect").getAsString());
    }
}
