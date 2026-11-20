// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.advanced;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.maiwithu.maicraft.game.serverlink.ClientRequest;
import org.maiwithu.maicraft.game.serverlink.ServerReads;

/**
 * 客户端向服务端只读操作 {@code ae2.device}（版本 1）询问单格设备或部件权威读数的轻量缓存封装：
 * <ul>
 * <li>请求 JSON：{@code { "x": int, "y": int, "z": int, "side": "north|south|east|west|up|down"(可选) }}</li>
 * <li>响应 JSON：{@code { "found": boolean, "kind": string, "block_id": string, "side": string,
 * "part_id": string, "settings": { key: value }, "upgrades": [ itemId... ], "readings": { ... } }}</li>
 * </ul>
 * 仅在服务端协商支持 {@code ae2.device} 时发出请求；每格读数在短保质期内复用，过期后再异步重问，
 * 避免查看同一台机器时每刻重复发包。服务端未装 MaiCraft 或策略关闭时安静返回空，让客户端平滑降级。
 */
public final class Ae2DeviceReadings {

    /** 服务端只读设备操作名：与服务端登记的 ae2.device 保持一致。 */
    public static final String DEVICE_OPERATION = "ae2.device";
    /** 服务端只读设备操作版本号。 */
    public static final int DEVICE_OPERATION_VERSION = 1;

    /** 单格设备权威读数的保质期（毫秒）：超过后在下次查看时异步刷新。 */
    static final long FRESH_MILLIS = 5_000L;

    private record Key(BlockPos at, Direction side) {
    }

    private record Entry(JsonObject reply, long receivedAt, UUID inFlight) {
    }

    private final ServerReads serverReads;
    private final LongSupplier clock;
    private final Map<Key, Entry> cache = new LinkedHashMap<>();

    public Ae2DeviceReadings(ServerReads serverReads) {
        this(serverReads, System::currentTimeMillis);
    }

    Ae2DeviceReadings(ServerReads serverReads, LongSupplier clock) {
        this.serverReads = Objects.requireNonNull(serverReads, "serverReads");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 服务端此刻是否协商开放了 ae2.device 只读操作。 */
    public boolean available() {
        return serverReads.available(DEVICE_OPERATION);
    }

    /**
     * 获取指定格（及可选面）最近一次收到的服务端设备回答；若无缓存或已过保质期，会在服务端可用时异步发起一次询问。
     * 只返回 {@code found == true} 的有效回答，未命中或失败时返回空。
     */
    public Optional<JsonObject> latest(BlockPos at, Direction side) {
        if (!available() || at == null) return Optional.empty();
        Key key = new Key(at.immutable(), side);
        long now = clock.getAsLong();
        Entry current = settle(key, now);
        boolean needsAsk = current == null
                || (current.inFlight() == null && now - current.receivedAt() >= FRESH_MILLIS);
        if (needsAsk) {
            Optional<UUID> sent = serverReads.ask(DEVICE_OPERATION, requestOf(key.at(), key.side()));
            if (sent.isPresent()) {
                JsonObject previous = current == null ? null : current.reply();
                long prevTime = current == null ? 0L : current.receivedAt();
                current = new Entry(previous, prevTime, sent.get());
                cache.put(key, current);
            }
        }
        if (current == null || current.reply() == null) return Optional.empty();
        JsonObject reply = current.reply();
        if (!reply.has("found") || !reply.get("found").getAsBoolean()) return Optional.empty();
        return Optional.of(reply);
    }

    /** 从服务端设备回答的 readings 子对象读取布尔字段。 */
    public static Optional<Boolean> readBoolean(JsonObject reply, String field) {
        JsonElement element = readingElement(reply, field);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()
                ? Optional.of(element.getAsBoolean()) : Optional.empty();
    }

    /** 从服务端设备回答的 readings 子对象读取长整型字段。 */
    public static Optional<Long> readLong(JsonObject reply, String field) {
        JsonElement element = readingElement(reply, field);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
                ? Optional.of(element.getAsLong()) : Optional.empty();
    }

    /** 从服务端设备回答的 readings 子对象读取浮点字段。 */
    public static Optional<Double> readDouble(JsonObject reply, String field) {
        JsonElement element = readingElement(reply, field);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
                ? Optional.of(element.getAsDouble()) : Optional.empty();
    }

    /** 从服务端设备回答的 readings 子对象读取字符串字段。 */
    public static Optional<String> readString(JsonObject reply, String field) {
        JsonElement element = readingElement(reply, field);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? Optional.of(element.getAsString()) : Optional.empty();
    }

    private static JsonElement readingElement(JsonObject reply, String field) {
        if (reply == null || !reply.has("readings") || !reply.get("readings").isJsonObject()) return null;
        JsonObject readings = reply.getAsJsonObject("readings");
        return readings.has(field) ? readings.get(field) : null;
    }

    private Entry settle(Key key, long now) {
        Entry entry = cache.get(key);
        if (entry == null || entry.inFlight() == null) return entry;
        Optional<ClientRequest.Snapshot> snapshot = serverReads.answer(entry.inFlight());
        if (snapshot.isEmpty()) {
            // 请求被轮换清场：清掉在途编号，下次查看立即重问。
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

    private static JsonObject requestOf(BlockPos at, Direction side) {
        JsonObject req = new JsonObject();
        req.addProperty("x", at.getX());
        req.addProperty("y", at.getY());
        req.addProperty("z", at.getZ());
        if (side != null) {
            req.addProperty("side", side.getSerializedName());
        }
        return req;
    }
}
