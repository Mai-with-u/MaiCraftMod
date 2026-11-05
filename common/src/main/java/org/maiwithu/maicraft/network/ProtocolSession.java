// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 一个经过协商、属于单一已认证连接的世界作用域；只能在其游戏线程访问。 */
final class ProtocolSession {
    static final long RETENTION_TICKS = 6000;
    /**
     * 世界生成铺的地形方块清单在欢迎包里最多占多少字、多少条：信封上限的一半、节点上限的一半，
     * 剩下的留给能力协商与限额，欢迎包怎么都不会因为这份清单超出上限。
     */
    static final int MAX_GENERATED_TERRAIN_CHARS = ProtocolJson.MAX_ENVELOPE_CHARS / 2;
    static final int MAX_GENERATED_TERRAIN_BLOCKS = 4096;
    final String id = UUID.randomUUID().toString();
    final String nonce;
    final String dimension;
    /** 握手时这台服务器存档的世界编号；没有时为 null。 */
    final String worldId;
    final Map<String, ServerFeature> features = new LinkedHashMap<>();
    final ServerRequestLedger ledger = new ServerRequestLedger();
    final long createdTick;
    long lastTick;
    long controlGeneration;
    boolean allowed = true;
    boolean closed;

    ProtocolSession(JsonObject hello, ServerProtocolDispatcher.Peer peer) {
        nonce = ProtocolJson.string(hello, "clientNonce");
        dimension = peer.dimension();
        worldId = peer.worldId();
        controlGeneration = ProtocolJson.number(hello, "controlGeneration");
        if (controlGeneration < 0) throw new IllegalArgumentException("Negative control generation");
        createdTick = lastTick = peer.tick();
        if (!hello.has("features") || !hello.get("features").isJsonObject())
            throw new IllegalArgumentException("Missing feature versions");
        JsonObject offered = hello.getAsJsonObject("features");
        for (ServerFeature feature : peer.features()) {
            // 一个处理器只实现一个具体版本；新增版本不得改变引导程序。
            if (offered.has(feature.operationId()) && ProtocolJson.number(offered, feature.operationId()) >= feature.version())
                features.put(feature.operationId(), feature);
        }
    }

    boolean expired(long tick) { return tick - lastTick > RETENTION_TICKS; }

    /**
     * @param keepInventory    此刻的死亡不掉落规则；读不到为 null，欢迎包里不带
     * @param generatedTerrain 世界生成往地上铺的实心方块 ID；读不到为 null，欢迎包里不带
     */
    JsonObject welcome(long tick, Boolean keepInventory, List<String> generatedTerrain) {
        JsonObject response = new JsonObject();
        response.addProperty("bootstrap", ProtocolJson.BOOTSTRAP);
        response.addProperty("kind", "welcome");
        response.addProperty("status", "succeeded");
        response.addProperty("clientNonce", nonce);
        response.addProperty("sessionId", id);
        response.addProperty("dimension", dimension);
        // 存档的世界编号：客户端按它认出是哪个世界（删档后同名重建也认得出是新的）；旧版本客户端不读这一项。
        if (worldId != null) response.addProperty("worldId", worldId);
        // 死亡不掉落：客户端连别人的服务器时读不到这条规则，由服务端报；旧版本客户端不读这一项。
        if (keepInventory != null) response.addProperty("keepInventory", keepInventory);
        // 世界生成铺的地形方块：客户端读不到世界生成数据，由服务端报，客户端在方块标签之外也当天然地形认；旧版本客户端不读这一项。
        // 清单大到挤得欢迎包超出信封上限时整项不带（客户端退回只按标签认），不截半截，也不让握手因此失败。
        if (generatedTerrain != null && fitsInWelcome(generatedTerrain)) {
            JsonArray blocks = new JsonArray();
            generatedTerrain.forEach(blocks::add);
            response.add("generatedTerrain", blocks);
        }
        response.addProperty("controlGeneration", controlGeneration);
        response.addProperty("allowed", allowed && !closed);
        response.addProperty("serverTick", tick);
        response.addProperty("remainingRequests", ledger.remainingRequests());
        JsonObject supported = new JsonObject();
        features.forEach((operation, feature) -> supported.add(operation, feature.json()));
        response.add("features", supported);
        JsonObject limits = new JsonObject();
        limits.addProperty("maxEnvelopeChars", ProtocolJson.MAX_ENVELOPE_CHARS);
        limits.addProperty("maxRequestChars", ProtocolJson.MAX_REQUEST_CHARS);
        limits.addProperty("maxRequests", ServerRequestLedger.MAX_REQUESTS);
        limits.addProperty("recentReadResults", ServerRequestLedger.RECENT_READ_RESULTS);
        limits.addProperty("readOnlyScopeMayExpire", true);
        limits.addProperty("retentionTicks", RETENTION_TICKS);
        limits.addProperty("maxRequestsPerTick", ServerProtocolDispatcher.MAX_REQUESTS_PER_TICK);
        limits.addProperty("maxSessions", ServerProtocolDispatcher.MAX_SESSIONS);
        response.add("limits", limits);
        return response;
    }

    // 清单编码后的长度按每条 ID 加引号、逗号算；超了预算整项不带，由调用方退回只按标签认。
    static boolean fitsInWelcome(List<String> generatedTerrain) {
        if (generatedTerrain.size() > MAX_GENERATED_TERRAIN_BLOCKS) return false;
        long chars = 2;
        for (String id : generatedTerrain) chars += id.length() + 3;
        return chars <= MAX_GENERATED_TERRAIN_CHARS;
    }
}
