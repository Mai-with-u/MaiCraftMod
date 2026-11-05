// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.serverlink;

import org.maiwithu.maicraft.network.ProtocolJson;
import org.maiwithu.maicraft.network.ServerFeature;
import org.maiwithu.maicraft.network.ServerProtocolDispatcher;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 客户端请求路由的离线场景：握手、只读请求的分发与结果、服务端未确认时不下发。
 * 发送通道用列表捕获，应答信封按服务端协议手工构造。
 */
class RequestRouterTest {

    /** 捕获发出的信封；available 可切换，模拟通道协商前后的状态。 */
    private static final class CapturingTransport implements LinkTransport {
        boolean available;
        final List<JsonObject> sent = new ArrayList<>();

        @Override public boolean available() { return available; }
        @Override public void send(JsonObject envelope) { sent.add(envelope.deepCopy()); }

        JsonObject last() { return sent.get(sent.size() - 1); }
    }

    private static JsonObject welcome(String clientNonce, String sessionId) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("kind", "welcome");
        envelope.addProperty("bootstrap", 1);
        envelope.addProperty("status", "succeeded");
        envelope.addProperty("clientNonce", clientNonce);
        envelope.addProperty("sessionId", sessionId);
        envelope.addProperty("dimension", "minecraft:overworld");
        envelope.addProperty("controlGeneration", 0);
        envelope.addProperty("allowed", true);
        envelope.addProperty("serverTick", 100);
        JsonObject features = new JsonObject();
        JsonObject query = new JsonObject();
        query.addProperty("version", 1);
        query.addProperty("mutating", false);
        query.addProperty("enabled", true);
        query.add("limits", new JsonObject());
        features.add("ownership.query", query);
        envelope.add("features", features);
        JsonObject limits = new JsonObject();
        limits.addProperty("retentionTicks", 6000);
        limits.addProperty("maxRequests", 512);
        limits.addProperty("maxRequestsPerTick", 8);
        envelope.add("limits", limits);
        return envelope;
    }

    private static JsonObject receipt(String sessionId, String requestId) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("kind", "receipt");
        envelope.addProperty("bootstrap", 1);
        envelope.addProperty("sessionId", sessionId);
        envelope.addProperty("dimension", "minecraft:overworld");
        envelope.addProperty("requestId", requestId);
        envelope.addProperty("operationId", "ownership.query");
        envelope.addProperty("version", 1);
        envelope.addProperty("status", "succeeded");
        envelope.addProperty("effect", "not_applied");
        envelope.addProperty("serverTick", 101);
        envelope.add("result", new JsonObject());
        return envelope;
    }

    @Test
    void 服务端确认之前请求留在队列里确认之后才发出() {
        var transport = new CapturingTransport();
        transport.available = true;
                var router = new RequestRouter(transport::available, envelope -> { transport.send(envelope); return true; },
                () -> { }, Runnable::run, (ClientRequest request, Runnable send) -> true);
        var session = router.session();
        router.register(new ClientOperation("ownership.query", 1, false));
        router.bind(1, 1, "minecraft:overworld", 0, true, 0);
        router.observe(0);
        // 绑定后立即发出 hello。
        assertEquals("hello", transport.last().get("kind").getAsString());

        var request = router.submit("ownership.query", new JsonObject(), false);
        router.dispatch(true);
        // 服务器还没确认支持，连只读请求也不下发。
        assertEquals(ClientRequest.Status.QUEUED, router.poll(request.id()).orElseThrow().status());

        // 用捕获到的 nonce 回一个欢迎包；随后确认生效，请求可以发出。
        router.receive(welcome(session.nonce, "session-1"), 1);
        assertTrue(router.serverConfirmed());
        router.dispatch(true);
        assertEquals("request", transport.last().get("kind").getAsString());
        assertEquals("ownership.query", transport.last().get("operationId").getAsString());
        assertEquals("session-1", transport.last().get("sessionId").getAsString());

        // 服务端按请求身份回结果：请求结算成功，只读操作的效果是"未应用"。
        router.receive(receipt("session-1", request.id().toString()), 1);
        var snapshot = router.poll(request.id()).orElseThrow();
        assertEquals(ClientRequest.Status.SUCCEEDED, snapshot.status());
        assertEquals(ClientRequest.Effect.NOT_APPLIED, snapshot.effect());
        assertFalse(snapshot.unresolvedMutation());
    }

    @Test
    void 欢迎包里的世界编号记下来_没报或不像样时为空_换维度重新握手前清掉() {
        var transport = new CapturingTransport();
        transport.available = true;
        var router = new RequestRouter(transport::available, envelope -> { transport.send(envelope); return true; },
                () -> { }, Runnable::run, (ClientRequest request, Runnable send) -> true);
        var session = router.session();
        router.register(new ClientOperation("ownership.query", 1, false));
        router.bind(1, 1, "minecraft:overworld", 0, true, 0);
        router.observe(0);
        // 还没收到欢迎包：没有作用域，也没有编号，认世界的一方继续等。
        assertNull(session.capabilities.scope());
        assertNull(session.capabilities.worldId());

        // 服务器报了存档的世界编号：原样记下。
        JsonObject welcome = welcome(session.nonce, "session-1");
        welcome.addProperty("worldId", "ab".repeat(32));
        router.receive(welcome, 1);
        assertEquals("ab".repeat(32), session.capabilities.worldId());

        // 换维度重新握手：欢迎包回来之前作用域与编号都清掉，不拿上一次握手的当这一次的。
        router.bind(1, 2, "minecraft:the_nether", 0, true, 1);
        assertNull(session.capabilities.scope());
        assertNull(session.capabilities.worldId());

        // 旧版本服务端不报编号、或报来的不是 64 位十六进制：握手照样生效，编号按没报算。
        router.bind(2, 3, "minecraft:overworld", 0, true, 2);
        JsonObject odd = welcome(session.nonce, "session-2");
        odd.addProperty("worldId", "../saves/新的世界");
        router.receive(odd, 2);
        assertNotNull(session.capabilities.scope());
        assertNull(session.capabilities.worldId());
    }

    @Test
    void 欢迎包带的随机数不对时握手不生效() {
        var transport = new CapturingTransport();
        transport.available = true;
                var router = new RequestRouter(transport::available, envelope -> { transport.send(envelope); return true; },
                () -> { }, Runnable::run, (ClientRequest request, Runnable send) -> true);
        var session = router.session();
        router.register(new ClientOperation("ownership.query", 1, false));
        router.bind(1, 1, "minecraft:overworld", 0, true, 0);
        router.observe(0);
        router.receive(welcome("not-our-nonce", "session-1"), 1);
        assertFalse(router.serverConfirmed());
    }

    @Test
    void 通道不可用时请求不能发出() {
        var transport = new CapturingTransport();
        transport.available = false;
                var router = new RequestRouter(transport::available, envelope -> { transport.send(envelope); return true; },
                () -> { }, Runnable::run, (ClientRequest request, Runnable send) -> true);
        var session = router.session();
        router.register(new ClientOperation("ownership.query", 1, false));
        router.bind(1, 1, "minecraft:overworld", 0, true, 0);
        router.observe(0);
        // 通道恢复后才收到欢迎包：握手生效，但发送仍走不可用的通道。
        transport.available = true;
        router.observe(0);
        router.receive(welcome(session.nonce, "session-1"), 1);
        assertTrue(router.serverConfirmed());
        // 通道再次断开时，请求发不出去，仍留在队列里等下一刻。
        transport.available = false;
        int before = transport.sent.size();
        var request = router.submit("ownership.query", new JsonObject(), false);
        router.dispatch(true);
        assertEquals(before, transport.sent.size());
        assertEquals(ClientRequest.Status.QUEUED, router.poll(request.id()).orElseThrow().status());
    }

    @Test
    void 未登记的操作提交直接拒绝() {
        var router = new RequestRouter(() -> true, envelope -> true,
                () -> { }, Runnable::run, (ClientRequest request, Runnable send) -> true);
        assertThrows(IllegalArgumentException.class,
                () -> router.submit("unknown.op", new JsonObject(), false));
    }

    private static JsonObject refused(String clientNonce) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("kind", "welcome");
        envelope.addProperty("bootstrap", 1);
        envelope.addProperty("status", "rejected");
        envelope.addProperty("code", "not_character_account");
        envelope.addProperty("clientNonce", clientNonce);
        return envelope;
    }

    /** 服务端那一侧的假对端：只为握手，死亡不掉落按字段报。 */
    private static final class ServerSide implements ServerProtocolDispatcher.Peer {
        Boolean keepInventory;
        List<String> generatedTerrain;

        @Override public String dimension() { return "minecraft:overworld"; }
        @Override public long tick() { return 100; }
        @Override public boolean admitted() { return true; }
        @Override public String worldId() { return null; }
        @Override public Boolean keepInventory() { return keepInventory; }
        @Override public List<String> generatedTerrain() { return generatedTerrain; }
        @Override public boolean mayMutate() { return true; }
        @Override public List<ServerFeature> features() { return List.of(); }
        @Override public JsonObject execute(String operationId, JsonObject body) { return new JsonObject(); }
    }

    // 客户端发出的握手原样交给服务端协议引擎，回的欢迎包按线上的样子编码再解码交回客户端。
    private static void handshake(RequestRouter router, CapturingTransport transport, ServerSide server, long connection) {
        JsonObject hello = ProtocolJson.decode(ProtocolJson.encodeRequest(transport.last()));
        JsonObject welcome = new ServerProtocolDispatcher().receive(hello, server);
        router.receive(ProtocolJson.decode(ProtocolJson.encode(welcome)), connection);
    }

    @Test
    void 服务端报的死亡不掉落_握手往返记下_改了照新值_旧服务端不报() {
        var transport = new CapturingTransport();
        transport.available = true;
        var router = new RequestRouter(transport::available, envelope -> { transport.send(envelope); return true; },
                () -> { }, Runnable::run, (ClientRequest request, Runnable send) -> true);
        var session = router.session();
        router.bind(1, 1, "minecraft:overworld", 0, true, 0);
        router.observe(0);
        assertNull(session.capabilities.keepInventory(), "还没握好手：读不到");

        var server = new ServerSide();
        server.keepInventory = true;
        handshake(router, transport, server, 1);
        assertTrue(router.serverConfirmed());
        assertEquals(Boolean.TRUE, session.capabilities.keepInventory());

        // 服主中途关了：服务端推一条"规则变了"，照新值记，不重新握手。
        router.receive(ServerProtocolDispatcher.rulesNotice(false), 1);
        assertEquals(Boolean.FALSE, session.capabilities.keepInventory());
        assertTrue(router.serverConfirmed());

        // 换到旧版服务端（不报这一项）：重新握手后按没报算。
        router.bind(2, 2, "minecraft:overworld", 0, true, 1);
        router.observe(1);
        handshake(router, transport, new ServerSide(), 2);
        assertTrue(router.serverConfirmed());
        assertNull(session.capabilities.keepInventory());
    }

    @Test
    void 新服务端报世界生成铺的地形方块_握手往返记下_旧服务端不报时读不到() {
        var transport = new CapturingTransport();
        transport.available = true;
        var router = new RequestRouter(transport::available, envelope -> { transport.send(envelope); return true; },
                () -> { }, Runnable::run, (ClientRequest request, Runnable send) -> true);
        var session = router.session();
        router.bind(1, 1, "minecraft:overworld", 0, true, 0);
        router.observe(0);
        assertNull(session.capabilities.generatedTerrain(), "还没握好手：读不到");

        // 新客户端配新服务端：清单原样记下，不像方块 ID 的条目丢掉，别的照收。
        var server = new ServerSide();
        server.keepInventory = true;
        server.generatedTerrain = List.of("biomesoplenty:black_sandstone", "Not A Block", "create:asurine");
        handshake(router, transport, server, 1);
        assertTrue(router.serverConfirmed());
        assertEquals(List.of("biomesoplenty:black_sandstone", "create:asurine"), session.capabilities.generatedTerrain());
        assertEquals(Boolean.TRUE, session.capabilities.keepInventory(), "多了清单，旧字段照读");

        // 新客户端配旧服务端（不报这一项）：握手照常成功，清单读不到，客户端只按方块标签认。
        router.bind(2, 2, "minecraft:overworld", 0, true, 1);
        router.observe(1);
        handshake(router, transport, new ServerSide(), 2);
        assertTrue(router.serverConfirmed());
        assertNull(session.capabilities.generatedTerrain());
    }

    @Test
    void 新服务端的欢迎包_只认旧字段的客户端照旧握手() {
        // 旧客户端读欢迎包只取能力协商、会话、世界编号、死亡不掉落和限额，多出来的字段不读；
        // 这里核的是新服务端发出的欢迎包在线上解码得回来（旧客户端用同一套解码上限），旧字段一个不少。
        var server = new ServerSide();
        server.keepInventory = false;
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 600; i++) many.add("somemod:generated_rock_" + i);
        server.generatedTerrain = many;
        JsonObject hello = new JsonObject();
        hello.addProperty("kind", "hello");
        hello.addProperty("bootstrap", 1);
        hello.addProperty("clientNonce", "old-client");
        hello.addProperty("controlGeneration", 0);
        hello.add("features", new JsonObject());
        JsonObject welcome = ProtocolJson.decode(ProtocolJson.encode(new ServerProtocolDispatcher().receive(hello, server)));
        assertEquals("succeeded", welcome.get("status").getAsString());
        assertEquals("old-client", welcome.get("clientNonce").getAsString());
        assertNotNull(welcome.get("sessionId"));
        assertFalse(welcome.get("keepInventory").getAsBoolean());
        assertTrue(welcome.has("features") && welcome.has("limits"));
        assertEquals(600, welcome.getAsJsonArray("generatedTerrain").size());
    }

    @Test
    void 还没握好手时收到规则变了_不记() {
        var transport = new CapturingTransport();
        transport.available = true;
        var router = new RequestRouter(transport::available, envelope -> { transport.send(envelope); return true; },
                () -> { }, Runnable::run, (ClientRequest request, Runnable send) -> true);
        router.bind(1, 1, "minecraft:overworld", 0, true, 0);
        router.observe(0);
        router.receive(ServerProtocolDispatcher.rulesNotice(true), 1);
        assertNull(router.session().capabilities.keepInventory(), "欢迎包会带当时的值，握手前的推送不算");
    }

    private static JsonObject admission(boolean admitted) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("kind", "admission");
        envelope.addProperty("bootstrap", 1);
        envelope.addProperty("admitted", admitted);
        return envelope;
    }

    @Test
    void 服主用命令加进名单后当场重新握手_撤掉后当场按不是角色账号收场() {
        var transport = new CapturingTransport();
        transport.available = true;
        var router = new RequestRouter(transport::available, envelope -> { transport.send(envelope); return true; },
                () -> { }, Runnable::run, (ClientRequest request, Runnable send) -> true);
        var session = router.session();
        router.register(new ClientOperation("ownership.query", 1, false));
        router.bind(1, 1, "minecraft:overworld", 0, true, 0);
        router.observe(0);
        // 先是不在名单里：握手被拒。
        router.receive(refused(session.nonce), 1);
        assertEquals(ServerCapabilityState.State.DENIED, session.capabilities.state());
        assertFalse(router.serverConfirmed());
        String firstNonce = session.nonce;
        int sentBefore = transport.sent.size();

        // 服主 /mai 加进名单：服务端推一条"准入了"，下一刻换随机数重新握手，欢迎包到了就开放。
        router.receive(admission(true), 1);
        router.observe(5);
        assertEquals(sentBefore + 1, transport.sent.size());
        assertEquals("hello", transport.last().get("kind").getAsString());
        assertFalse(firstNonce.equals(session.nonce), "重新握手换一个随机数");
        router.receive(welcome(session.nonce, "session-2"), 1);
        assertTrue(router.serverConfirmed());

        // 服主 /mai remove：服务端关掉会话并推一条"不准入了"，这边当场收场，调试面板说是名单的事。
        router.receive(admission(false), 1);
        assertFalse(router.serverConfirmed());
        assertEquals(ServerCapabilityState.State.DENIED, session.capabilities.state());
        assertEquals(ServerCapabilityState.NOT_CHARACTER_ACCOUNT, session.capabilities.reason());
    }

    @Test
    void 没被拒过的连接收到准入了不重新握手() {
        var transport = new CapturingTransport();
        transport.available = true;
        var router = new RequestRouter(transport::available, envelope -> { transport.send(envelope); return true; },
                () -> { }, Runnable::run, (ClientRequest request, Runnable send) -> true);
        var session = router.session();
        router.register(new ClientOperation("ownership.query", 1, false));
        router.bind(1, 1, "minecraft:overworld", 0, true, 0);
        router.observe(0);
        router.receive(welcome(session.nonce, "session-1"), 1);
        int sentBefore = transport.sent.size();
        router.receive(admission(true), 1);
        router.observe(5);
        assertTrue(router.serverConfirmed());
        assertTrue(transport.sent.size() >= sentBefore);
        assertTrue(transport.sent.subList(sentBefore, transport.sent.size()).stream()
                .noneMatch(envelope -> envelope.get("kind").getAsString().equals("hello")), "已经握好手的不重来");
    }
}
