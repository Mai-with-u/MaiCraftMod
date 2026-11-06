// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.serverlink;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Pattern;
import org.maiwithu.maicraft.network.ServerProtocolDispatcher;

/** 协商出的服务端支持、客户端已登记能力和每次执行条件分别判断。 */
public final class ServerCapabilityState {
    public enum State { DISCONNECTED, NEGOTIATING, READY, UNCONFIRMED, DENIED, LOST }
    /** 服务端拒绝握手的原因之一：这个号不是服务器认的角色账号，服主要把它写进服务端配置才开放。 */
    public static final String NOT_CHARACTER_ACCOUNT = ServerProtocolDispatcher.NOT_CHARACTER_ACCOUNT;
    public record Scope(long connection, String sessionId, String dimension) {}
    /** 方块 ID 的样子：命名空间加路径，都是小写；欢迎包里别的样子的条目不收。 */
    private static final Pattern BLOCK_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    public record Feature(int version, boolean mutating, boolean enabled, JsonObject limits) {
        public Feature { limits = limits.deepCopy(); }
        @Override public JsonObject limits() { return limits.deepCopy(); }
    }

    State state = State.DISCONNECTED;
    String reason = "no_connection";
    Scope scope;
    final Map<String, Feature> features = new LinkedHashMap<>();
    JsonObject limits = new JsonObject();
    /** 欢迎包里服务器报的存档世界编号；还没收到欢迎包，或服务端版本太旧、没报时为 null。 */
    String worldId;
    /** 服务端报的死亡不掉落；还没收到欢迎包，或服务端版本太旧、没报时为 null。 */
    Boolean keepInventory;
    /** 服务端报的正版验证开没开；还没收到欢迎包，或服务端版本太旧、没报时为 null。 */
    Boolean onlineMode;
    /** 服务端报的世界生成往地上铺的实心方块 ID；还没收到欢迎包，或服务端版本太旧、没报时为 null。 */
    List<String> generatedTerrain;

    public State state() { return state; }
    public String reason() { return reason; }
    /** 当前绑定收到的有效欢迎包对应的作用域；还没收到（握手中、被拒、换维度重新握手）时为 null。 */
    public Scope scope() { return scope; }
    /** 握手时服务器报的存档世界编号；欢迎包里没有或不像样时为 null。只在 {@link #scope()} 不为 null 时有意义。 */
    public String worldId() { return worldId; }
    /** 服务端报的死亡不掉落（握手时报、改了再推）；没报为 null。只在 {@link #scope()} 不为 null 时有意义。 */
    public Boolean keepInventory() { return keepInventory; }
    /** 服务端报的正版验证开没开（握手时报）；没报为 null。只在 {@link #scope()} 不为 null 时有意义。 */
    public Boolean onlineMode() { return onlineMode; }
    /** 服务端报的世界生成铺的地形方块 ID（握手时报）；没报为 null。只在 {@link #scope()} 不为 null 时有意义。 */
    public List<String> generatedTerrain() { return generatedTerrain; }
    public Map<String, Feature> features() { return Map.copyOf(features); }
    public JsonObject limits() { return limits.deepCopy(); }

    public boolean supports(ClientOperation operation) {
        Feature feature = features.get(operation.id());
        return state == State.READY && feature != null && feature.enabled()
                && feature.version() == operation.version() && feature.mutating() == operation.mutating();
    }

    public boolean policyDenied(ClientOperation operation) {
        Feature feature = features.get(operation.id());
        return state == State.DENIED || (feature != null && !feature.enabled());
    }

    void reset(State next, String detail) {
        state = next;
        reason = detail;
        scope = null;
        worldId = null;
        keepInventory = null;
        onlineMode = null;
        generatedTerrain = null;
        features.clear();
        limits = new JsonObject();
    }

    // 服务端推来"规则变了"：握好手的才照新值记；还在握手的等欢迎包带当时的值。
    void rulesChanged(JsonObject envelope) {
        if (scope == null) return;
        Boolean reported = bool(envelope, "keepInventory");
        if (reported != null) keepInventory = reported;
    }

    void welcome(JsonObject envelope, long connection, String dimension) {
        var discovered = new LinkedHashMap<String, Feature>();
        JsonObject advertised = object(envelope, "features");
        if (advertised.size() > 256) throw new IllegalArgumentException("too many advertised operations");
        for (var entry : advertised.entrySet()) {
            JsonObject item = entry.getValue().getAsJsonObject();
            discovered.put(entry.getKey(), new Feature(item.get("version").getAsInt(),
                    item.get("mutating").getAsBoolean(), item.get("enabled").getAsBoolean(),
                    object(item, "limits")));
        }
        String sessionId = text(envelope, "sessionId");
        if (sessionId.isBlank()) throw new IllegalArgumentException("missing server session identity");
        scope = new Scope(connection, sessionId, dimension);
        // 世界编号只收 64 位小写十六进制：服务器报来别的样子就当没报，客户端退回按服务器地址认，不让一串外来文字当存档文档库的键。
        String reported = text(envelope, "worldId");
        worldId = reported.matches("[0-9a-f]{64}") ? reported : null;
        // 死亡不掉落只收布尔值：旧版本服务端不报、报来别的样子都按没报，客户端照旧读不到。
        keepInventory = bool(envelope, "keepInventory");
        // 正版验证同样只收布尔值：旧版本服务端不报就按没报，宿主看到的是"不知道"，不猜成开着或关着。
        onlineMode = bool(envelope, "onlineMode");
        // 世界生成铺的地形方块：旧版本服务端不报就按没报，客户端只按方块标签认；不像方块 ID 的条目丢掉，别的照收。
        generatedTerrain = blockIds(envelope, "generatedTerrain");
        features.clear();
        features.putAll(discovered);
        limits = object(envelope, "limits").deepCopy();
        state = State.READY;
        reason = "";
    }

    int limit(String name, int defaultValue, int maximum) {
        try { return Math.max(1, Math.min(maximum, limits.get(name).getAsInt())); }
        catch (RuntimeException missing) { return defaultValue; }
    }

    JsonObject report() {
        JsonObject report = new JsonObject();
        report.addProperty("state", state.name().toLowerCase(Locale.ROOT));
        report.addProperty("reason", reason);
        if (scope != null) {
            report.addProperty("session_id", scope.sessionId());
            report.addProperty("dimension", scope.dimension());
        }
        report.add("limits", limits.deepCopy());
        JsonObject operations = new JsonObject();
        features.forEach((id, feature) -> {
            JsonObject value = new JsonObject();
            value.addProperty("registered", true);
            value.addProperty("version", feature.version());
            value.addProperty("mutating", feature.mutating());
            value.addProperty("enabled", feature.enabled());
            value.add("limits", feature.limits());
            operations.add(id, value);
        });
        report.add("server_operations", operations);
        return report;
    }

    static String text(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : "";
    }
    static Boolean bool(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonPrimitive() && object.getAsJsonPrimitive(name).isBoolean()
                ? object.get(name).getAsBoolean() : null;
    }
    static List<String> blockIds(JsonObject object, String name) {
        if (!object.has(name) || !object.get(name).isJsonArray()) return null;
        List<String> ids = new ArrayList<>();
        for (JsonElement item : object.getAsJsonArray(name)) {
            if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()
                    && BLOCK_ID.matcher(item.getAsString()).matches()) {
                ids.add(item.getAsString());
            }
        }
        return List.copyOf(ids);
    }
    static JsonObject object(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonObject()
                ? object.getAsJsonObject(name) : new JsonObject();
    }
}
