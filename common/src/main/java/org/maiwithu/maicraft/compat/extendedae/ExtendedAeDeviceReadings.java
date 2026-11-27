// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.extendedae;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.game.serverlink.ClientRequest;
import org.maiwithu.maicraft.game.serverlink.ServerReads;

/**
 * 客户端向服务端只读操作 {@code extendedae.device}（版本 1）询问单格设备权威读数的缓存封装，
 * 同时维护开窗读回的本地菜单缓存。服务端未开放只读操作时安静返回空，不阻塞离线与纯客户端模式。
 */
public final class ExtendedAeDeviceReadings {

    public static final String DEVICE_OPERATION = "extendedae.device";
    public static final int DEVICE_OPERATION_VERSION = 1;
    /** 服务端设备权威读数的本地保质期（毫秒）。 */
    static final long FRESH_MILLIS = 5_000L;

    private record Entry(JsonObject reply, long receivedAt, UUID inFlight) {}

    private final ServerReads serverReads;
    private final LongSupplier clock;
    private final Map<BlockPos, Entry> remoteCache = new LinkedHashMap<>();
    private final Map<BlockPos, ExtendedAeReads.CrystalAssemblerMenuView> crystalAssemblerCache = new LinkedHashMap<>();
    private final Map<BlockPos, ExtendedAeReads.CircuitCutterMenuView> circuitCutterCache = new LinkedHashMap<>();
    private final Map<BlockPos, ExtendedAeReads.ExInscriberMenuView> exInscriberCache = new LinkedHashMap<>();
    private final Map<BlockPos, ExtendedAeReads.PatternProviderMenuView> providerCache = new LinkedHashMap<>();
    private final Map<BlockPos, Integer> priorityCache = new LinkedHashMap<>();
    private final Map<BlockPos, ExtendedAeReads.CellTransferMenuView> transferCache = new LinkedHashMap<>();
    private final Map<BlockPos, ExtendedAeReads.CanerMenuView> canerCache = new LinkedHashMap<>();
    private final Map<BlockPos, ExtendedAeReads.WirelessHubMenuView> wirelessHubCache = new LinkedHashMap<>();

    public ExtendedAeDeviceReadings(ServerReads serverReads) {
        this(serverReads, System::currentTimeMillis);
    }

    ExtendedAeDeviceReadings(ServerReads serverReads, LongSupplier clock) {
        this.serverReads = Objects.requireNonNull(serverReads, "serverReads");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 服务端此刻是否协商开放了 extendedae.device 只读操作。 */
    public boolean available() {
        return serverReads.available(DEVICE_OPERATION);
    }

    /** 读取指定格最近一次收到的服务端权威回答；无缓存或过期时在服务端可用前提下异步重问。 */
    public Optional<JsonObject> latest(BlockPos at) {
        if (!available() || at == null) return Optional.empty();
        BlockPos key = at.immutable();
        long now = clock.getAsLong();
        Entry current = settle(key, now);
        boolean needsAsk = current == null
                || (current.inFlight() == null && now - current.receivedAt() >= FRESH_MILLIS);
        if (needsAsk) {
            Optional<UUID> sent = serverReads.ask(DEVICE_OPERATION, requestOf(key));
            if (sent.isPresent()) {
                JsonObject previous = current == null ? null : current.reply();
                long prevTime = current == null ? 0L : current.receivedAt();
                current = new Entry(previous, prevTime, sent.get());
                remoteCache.put(key, current);
            }
        }
        if (current == null || current.reply() == null) return Optional.empty();
        JsonObject reply = current.reply();
        if (!reply.has("found") || !reply.get("found").getAsBoolean()) return Optional.empty();
        return Optional.of(reply);
    }

    public void putCrystalAssembler(BlockPos at, ExtendedAeReads.CrystalAssemblerMenuView view) {
        crystalAssemblerCache.put(at.immutable(), Objects.requireNonNull(view, "view"));
    }

    public Optional<ExtendedAeReads.CrystalAssemblerMenuView> crystalAssembler(BlockPos at) {
        return Optional.ofNullable(crystalAssemblerCache.get(at.immutable()));
    }

    public void putCircuitCutter(BlockPos at, ExtendedAeReads.CircuitCutterMenuView view) {
        circuitCutterCache.put(at.immutable(), Objects.requireNonNull(view, "view"));
    }

    public Optional<ExtendedAeReads.CircuitCutterMenuView> circuitCutter(BlockPos at) {
        return Optional.ofNullable(circuitCutterCache.get(at.immutable()));
    }

    public void putExInscriber(BlockPos at, ExtendedAeReads.ExInscriberMenuView view) {
        exInscriberCache.put(at.immutable(), Objects.requireNonNull(view, "view"));
    }

    public Optional<ExtendedAeReads.ExInscriberMenuView> exInscriber(BlockPos at) {
        return Optional.ofNullable(exInscriberCache.get(at.immutable()));
    }

    public void putProvider(BlockPos at, ExtendedAeReads.PatternProviderMenuView view) {
        providerCache.put(at.immutable(), Objects.requireNonNull(view, "view"));
    }

    public Optional<ExtendedAeReads.PatternProviderMenuView> provider(BlockPos at) {
        return Optional.ofNullable(providerCache.get(at.immutable()));
    }

    public void putPriority(BlockPos at, int priority) {
        priorityCache.put(at.immutable(), priority);
    }

    public Optional<Integer> priority(BlockPos at) {
        return Optional.ofNullable(priorityCache.get(at.immutable()));
    }

    public void putCellTransfer(BlockPos at, ExtendedAeReads.CellTransferMenuView view) {
        transferCache.put(at.immutable(), Objects.requireNonNull(view, "view"));
    }

    public Optional<ExtendedAeReads.CellTransferMenuView> cellTransfer(BlockPos at) {
        return Optional.ofNullable(transferCache.get(at.immutable()));
    }

    public void putCaner(BlockPos at, ExtendedAeReads.CanerMenuView view) {
        canerCache.put(at.immutable(), Objects.requireNonNull(view, "view"));
    }

    public Optional<ExtendedAeReads.CanerMenuView> caner(BlockPos at) {
        return Optional.ofNullable(canerCache.get(at.immutable()));
    }

    public void putWirelessHub(BlockPos at, ExtendedAeReads.WirelessHubMenuView view) {
        wirelessHubCache.put(at.immutable(), Objects.requireNonNull(view, "view"));
    }

    public Optional<ExtendedAeReads.WirelessHubMenuView> wirelessHub(BlockPos at) {
        return Optional.ofNullable(wirelessHubCache.get(at.immutable()));
    }

    /** 将服务端回复里的 readings、settings 与 upgrades 合并进机器读数表。 */
    public static void mergeServerReply(Map<String, Object> target, JsonObject reply) {
        if (reply == null) return;
        if (reply.has("readings") && reply.get("readings").isJsonObject()) {
            JsonObject readings = reply.getAsJsonObject("readings");
            for (Map.Entry<String, JsonElement> entry : readings.entrySet()) {
                Object converted = fromJson(entry.getValue());
                if (converted != null) target.put(entry.getKey(), converted);
            }
        }
        if (reply.has("settings") && reply.get("settings").isJsonObject()) {
            JsonObject settings = reply.getAsJsonObject("settings");
            for (Map.Entry<String, JsonElement> entry : settings.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    target.putIfAbsent(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }
        if (reply.has("upgrades") && reply.get("upgrades").isJsonArray()) {
            JsonArray arr = reply.getAsJsonArray("upgrades");
            for (int i = 0; i < arr.size(); i++) {
                String card = arr.get(i).isJsonPrimitive() ? arr.get(i).getAsString() : "";
                target.put("upgrade." + i, card.isEmpty() ? "empty" : card);
            }
        }
    }

    public static Optional<String> readString(JsonObject reply, String field) {
        JsonElement el = readingElement(reply, field);
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()
                ? Optional.of(el.getAsString()) : Optional.empty();
    }

    public static Optional<Long> readLong(JsonObject reply, String field) {
        JsonElement el = readingElement(reply, field);
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()
                ? Optional.of(el.getAsLong()) : Optional.empty();
    }

    /** 从服务端设备读数的 readings 字段读取字符串列表（字段缺失或非数组时返回空）。 */
    public static Optional<List<String>> readStringList(JsonObject reply, String field) {
        JsonElement el = readingElement(reply, field);
        if (el == null || !el.isJsonArray()) return Optional.empty();
        JsonArray arr = el.getAsJsonArray();
        List<String> list = new ArrayList<>(arr.size());
        for (JsonElement child : arr) {
            if (child.isJsonPrimitive()) list.add(child.getAsString());
        }
        return Optional.of(List.copyOf(list));
    }

    public static Optional<String> readSetting(JsonObject reply, String key) {
        if (reply == null || !reply.has("settings") || !reply.get("settings").isJsonObject()) return Optional.empty();
        JsonObject settings = reply.getAsJsonObject("settings");
        if (!settings.has(key) || !settings.get(key).isJsonPrimitive()) return Optional.empty();
        return Optional.of(settings.get(key).getAsString());
    }

    public static List<String> readUpgrades(JsonObject reply) {
        if (reply == null || !reply.has("upgrades") || !reply.get("upgrades").isJsonArray()) return List.of();
        JsonArray arr = reply.getAsJsonArray("upgrades");
        List<String> list = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            String raw = el.isJsonPrimitive() ? el.getAsString() : "";
            list.add(raw.isEmpty() ? "empty" : raw);
        }
        return List.copyOf(list);
    }

    private static JsonElement readingElement(JsonObject reply, String field) {
        if (reply == null || !reply.has("readings") || !reply.get("readings").isJsonObject()) return null;
        JsonObject readings = reply.getAsJsonObject("readings");
        return readings.has(field) ? readings.get(field) : null;
    }

    private static Object fromJson(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonPrimitive()) {
            var p = el.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) {
                String raw = p.getAsString();
                if (raw.contains(".") || raw.contains("e") || raw.contains("E")) return p.getAsDouble();
                return p.getAsLong();
            }
            return p.getAsString();
        }
        if (el.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonElement child : el.getAsJsonArray()) list.add(fromJson(child));
            return list;
        }
        if (el.isJsonObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : el.getAsJsonObject().entrySet()) {
                map.put(entry.getKey(), fromJson(entry.getValue()));
            }
            return map;
        }
        return null;
    }

    private Entry settle(BlockPos key, long now) {
        Entry entry = remoteCache.get(key);
        if (entry == null || entry.inFlight() == null) return entry;
        Optional<ClientRequest.Snapshot> snapshot = serverReads.answer(entry.inFlight());
        if (snapshot.isEmpty()) {
            Entry cleared = new Entry(entry.reply(), entry.receivedAt(), null);
            remoteCache.put(key, cleared);
            return cleared;
        }
        ClientRequest.Snapshot state = snapshot.get();
        if (!state.settled()) return entry;
        JsonObject reply = state.status() == ClientRequest.Status.SUCCEEDED ? state.result() : entry.reply();
        Entry settled = new Entry(reply, now, null);
        remoteCache.put(key, settled);
        return settled;
    }

    private static JsonObject requestOf(BlockPos at) {
        JsonObject req = new JsonObject();
        req.addProperty("x", at.getX());
        req.addProperty("y", at.getY());
        req.addProperty("z", at.getZ());
        return req;
    }
}
