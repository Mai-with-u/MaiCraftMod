// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.advancedae;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.game.serverlink.ClientRequest;
import org.maiwithu.maicraft.game.serverlink.ServerReads;

/**
 * 客户端向服务端只读操作 {@code advanced_ae.device}（版本 1）询问单格反应仓权威读数的轻量缓存：
 * 仅在服务端协商开放该操作时发起请求，在短保质期内复用，避免查看机器时每刻重复发包；
 * 服务端未装 MaiCraft 或策略关闭时静默返回空，平滑退回客户端直读与开窗缓存。
 */
final class AdvancedAeDeviceReadings {

    /** 服务端设备只读操作名。 */
    static final String DEVICE_OPERATION = "advanced_ae.device";
    /** 服务端设备只读操作版本号。 */
    static final int DEVICE_OPERATION_VERSION = 1;
    /** 单格读数保质期（毫秒）。 */
    static final long FRESH_MILLIS = 5_000L;

    private record Entry(JsonObject reply, long receivedAt, UUID inFlight) {
    }

    private final ServerReads serverReads;
    private final LongSupplier clock;
    private final Map<BlockPos, Entry> cache = new LinkedHashMap<>();

    AdvancedAeDeviceReadings(ServerReads serverReads) {
        this(serverReads, System::currentTimeMillis);
    }

    AdvancedAeDeviceReadings(ServerReads serverReads, LongSupplier clock) {
        this.serverReads = Objects.requireNonNull(serverReads, "serverReads");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 服务端此刻是否协商开放了 advanced_ae.device 只读操作。 */
    boolean available() {
        return serverReads.available(DEVICE_OPERATION);
    }

    /** 主动失效单格缓存，供清罐等状态变更后立即向服务端拉取最新权威读数。 */
    void invalidate(BlockPos at) {
        if (at != null) cache.remove(at.immutable());
    }

    /** 读取指定格最近收到的服务端权威回答；缓存缺失或过期时异步发起一次询问。 */
    Optional<JsonObject> latest(BlockPos at) {
        if (!available() || at == null) return Optional.empty();
        BlockPos key = at.immutable();
        long now = clock.getAsLong();
        Entry current = settle(key, now);
        boolean needsAsk = current == null
                || (current.inFlight() == null && now - current.receivedAt() >= FRESH_MILLIS);
        if (needsAsk) {
            JsonObject req = new JsonObject();
            req.addProperty("x", key.getX());
            req.addProperty("y", key.getY());
            req.addProperty("z", key.getZ());
            Optional<UUID> sent = serverReads.ask(DEVICE_OPERATION, req);
            if (sent.isPresent()) {
                JsonObject prev = current == null ? null : current.reply();
                long prevAt = current == null ? 0L : current.receivedAt();
                current = new Entry(prev, prevAt, sent.get());
                cache.put(key, current);
                current = settle(key, now);
            }
        }
        if (current == null || current.reply() == null) return Optional.empty();
        JsonObject reply = current.reply();
        if (!reply.has("found") || !reply.get("found").getAsBoolean()) return Optional.empty();
        return Optional.of(reply);
    }

    static Optional<Boolean> readBoolean(JsonObject reply, String field) {
        JsonElement el = readingElement(reply, field);
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean()
                ? Optional.of(el.getAsBoolean()) : Optional.empty();
    }

    static Optional<Long> readLong(JsonObject reply, String field) {
        JsonElement el = readingElement(reply, field);
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()
                ? Optional.of(el.getAsLong()) : Optional.empty();
    }

    static Optional<Double> readDouble(JsonObject reply, String field) {
        JsonElement el = readingElement(reply, field);
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()
                ? Optional.of(el.getAsDouble()) : Optional.empty();
    }

    static Optional<String> readString(JsonObject reply, String field) {
        JsonElement el = readingElement(reply, field);
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()
                ? Optional.of(el.getAsString()) : Optional.empty();
    }

    static List<String> readStringList(JsonObject reply, String field) {
        JsonElement el = readingElement(reply, field);
        if (el == null || !el.isJsonArray()) return List.of();
        JsonArray array = el.getAsJsonArray();
        List<String> out = new ArrayList<>(array.size());
        for (JsonElement item : array) {
            if (item.isJsonPrimitive()) out.add(item.getAsString());
        }
        return List.copyOf(out);
    }

    static Set<String> readStringSet(JsonObject reply, String field) {
        return Set.copyOf(new LinkedHashSet<>(readStringList(reply, field)));
    }

    static List<String> readUpgrades(JsonObject reply) {
        if (reply == null || !reply.has("upgrades") || !reply.get("upgrades").isJsonArray()) return List.of();
        JsonArray array = reply.getAsJsonArray("upgrades");
        List<String> out = new ArrayList<>(array.size());
        for (JsonElement item : array) {
            if (item.isJsonPrimitive()) {
                String value = item.getAsString();
                out.add(value.isEmpty() ? "empty" : value);
            }
        }
        return List.copyOf(out);
    }

    static Optional<String> readSetting(JsonObject reply, String key) {
        if (reply == null || !reply.has("settings") || !reply.get("settings").isJsonObject()) {
            return Optional.empty();
        }
        JsonObject settings = reply.getAsJsonObject("settings");
        if (!settings.has(key) || !settings.get(key).isJsonPrimitive()) return Optional.empty();
        return Optional.of(settings.get(key).getAsString());
    }

    private static JsonElement readingElement(JsonObject reply, String field) {
        if (reply == null || !reply.has("readings") || !reply.get("readings").isJsonObject()) return null;
        JsonObject readings = reply.getAsJsonObject("readings");
        return readings.has(field) ? readings.get(field) : null;
    }

    private Entry settle(BlockPos key, long now) {
        Entry entry = cache.get(key);
        if (entry == null || entry.inFlight() == null) return entry;
        Optional<ClientRequest.Snapshot> snapshot = serverReads.answer(entry.inFlight());
        if (snapshot.isEmpty()) {
            Entry cleared = new Entry(entry.reply(), entry.receivedAt(), null);
            cache.put(key, cleared);
            return cleared;
        }
        ClientRequest.Snapshot state = snapshot.get();
        if (!state.settled()) return entry;
        JsonObject reply = state.status() == ClientRequest.Status.SUCCEEDED ? state.result() : entry.reply();
        Entry settled = new Entry(reply, now, null);
        cache.put(key, settled);
        return settled;
    }
}
