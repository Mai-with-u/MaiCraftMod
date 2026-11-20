// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.part;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.LongSupplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.maiwithu.maicraft.game.serverlink.ClientRequest;
import org.maiwithu.maicraft.game.serverlink.ServerReads;

/**
 * 线缆部件与磁盘/无线设备的会话级读数缓存与服务端 ae2.device 读数合并器。
 *
 * <p>客户端在不打开菜单时拿不到大多数 AE2 设备的内部配置，因此遵循两路互补：
 * 1. 配置动作关窗前立刻把菜单里刚读回的最新值写入本地缓存，保证紧接其后的同步读回命中；
 * 2. 若服务端协商开启了 {@code ae2.device} 只读操作（或由外部注入了服务端读数），
 *    自动按坐标（与可选方位）拉取并合并权威读数，但短暂保护刚在本地写入的更新不被过期回包冲掉。
 */
public final class Ae2PartCache {

    /** 服务端只读操作名（与服务端 AE2 设备读数对齐）。 */
    public static final String DEVICE_OPERATION = "ae2.device";
    /** 服务端读数保质期（毫秒）：期内直接复用，过了才重新发问。 */
    private static final long SERVER_FRESH_MILLIS = 3_000L;
    /** 刚在本地改完配置后的写保护窗口（毫秒）：避免在途旧服务端回复盖掉刚改好的新值。 */
    private static final long LOCAL_GUARD_MILLIS = 1_500L;

    /** 按格坐标与可选面唯一标识一台方块设备（side == null）或一面线缆部件。 */
    public record TargetKey(BlockPos pos, Direction side) {
        public TargetKey {
            pos = Objects.requireNonNull(pos, "pos").immutable();
        }
    }

    private static final class Entry {
        final Map<String, String> settings = new LinkedHashMap<>();
        final Map<String, Object> readings = new LinkedHashMap<>();
        /** 一次性动作键（如 partition/clear）刚执行完后的单次读回确认值，被读回一次后自动复位。 */
        String pendingActionValue;
        long localWrittenAt;
        long serverReceivedAt;
        UUID inFlightRequest;
    }

    private final LongSupplier clock;
    private final Map<TargetKey, Entry> entries = new HashMap<>();

    public Ae2PartCache() {
        this(System::currentTimeMillis);
    }

    public Ae2PartCache(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 读取指定目标当前已知的配置键值对；若有刚执行完的一次性 action，消费并交回本次读回值。 */
    public Map<String, String> settingsFor(BlockPos pos, Direction side, ServerReads serverReads) {
        TargetKey key = new TargetKey(pos, side);
        pollServer(key, serverReads);
        Entry entry = entries.get(key);
        if (entry == null) return Map.of();
        Map<String, String> copy = new LinkedHashMap<>(entry.settings);
        if (entry.pendingActionValue != null) {
            copy.put("action", entry.pendingActionValue);
            entry.pendingActionValue = null;
        }
        return Map.copyOf(copy);
    }

    /** 只读窥视配置键值对（不消费一次性 action），供额外读数与状态展示使用。 */
    public Map<String, String> peekSettings(BlockPos pos, Direction side, ServerReads serverReads) {
        TargetKey key = new TargetKey(pos, side);
        pollServer(key, serverReads);
        Entry entry = entries.get(key);
        return entry == null ? Map.of() : Map.copyOf(entry.settings);
    }

    /** 读取指定目标已知的额外读数（如 connected_to、enchantments、p2p_is_output、boosters 等）。 */
    public Map<String, Object> readingsFor(BlockPos pos, Direction side, ServerReads serverReads) {
        TargetKey key = new TargetKey(pos, side);
        pollServer(key, serverReads);
        Entry entry = entries.get(key);
        return entry == null ? Map.of() : Map.copyOf(entry.readings);
    }

    /** 读出已同步的无线增幅卡张数；未同步过（既没开过窗也没收到服务端读数）时返回空。 */
    public OptionalInt syncedBoosters(BlockPos pos, ServerReads serverReads) {
        Map<String, Object> readings = readingsFor(pos, null, serverReads);
        Object raw = readings.get("boosters");
        if (raw instanceof Number number) return OptionalInt.of(number.intValue());
        return OptionalInt.empty();
    }

    /** 动作关窗前或直接交互完成后写入本地最新读回值与补充读数。 */
    public void recordLocal(BlockPos pos, Direction side, Map<String, String> settings, Map<String, Object> readings) {
        TargetKey key = new TargetKey(pos, side);
        Entry entry = entries.computeIfAbsent(key, ignored -> new Entry());
        if (settings != null) entry.settings.putAll(settings);
        if (readings != null) entry.readings.putAll(readings);
        entry.localWrittenAt = clock.getAsLong();
    }

    /** 写入单个本地配置键的新值（如直接交互改完 P2P 角色、备料槽或阈值）。 */
    public void recordSetting(BlockPos pos, Direction side, String settingKey, String value) {
        if (settingKey == null || value == null) return;
        recordLocal(pos, side, Map.of(settingKey, value), Map.of());
    }

    /** 记录一次刚执行完成的一次性动作（partition / clear），供配置任务紧接着的一次读回核对命中。 */
    public void recordActionApplied(BlockPos pos, Direction side, String actionValue) {
        TargetKey key = new TargetKey(pos, side);
        Entry entry = entries.computeIfAbsent(key, ignored -> new Entry());
        entry.pendingActionValue = actionValue;
        entry.localWrittenAt = clock.getAsLong();
    }

    /** 接纳服务端 ae2.device 返回的权威读数（可由异步轮询触发，也可由外部直接注入）。 */
    public void acceptServerDevice(BlockPos pos, Direction side, JsonObject payload) {
        if (payload == null) return;
        if (payload.has("found") && !payload.get("found").getAsBoolean()) return;
        TargetKey key = new TargetKey(pos, side);
        Entry entry = entries.computeIfAbsent(key, ignored -> new Entry());
        long now = clock.getAsLong();
        entry.serverReceivedAt = now;
        // 刚在本地改完配置的一小段保护期内，不让在途旧回复覆盖刚写入的新值。
        boolean localFresh = entry.localWrittenAt > 0 && now - entry.localWrittenAt < LOCAL_GUARD_MILLIS;
        mergeServerPayload(entry, payload, !localFresh);
    }

    // 检查在途服务端查询是否已回包；过期且服务端支持 ae2.device 时自动发出下一次异步只读查询。
    private void pollServer(TargetKey key, ServerReads serverReads) {
        if (serverReads == null || !serverReads.available(DEVICE_OPERATION)) return;
        Entry entry = entries.computeIfAbsent(key, ignored -> new Entry());
        long now = clock.getAsLong();
        if (entry.inFlightRequest != null) {
            Optional<ClientRequest.Snapshot> snapshot = serverReads.answer(entry.inFlightRequest);
            if (snapshot.isEmpty()) {
                entry.inFlightRequest = null;
            } else if (snapshot.get().status() == ClientRequest.Status.SUCCEEDED) {
                entry.inFlightRequest = null;
                JsonObject reply = snapshot.get().result();
                if (reply != null) acceptServerDevice(key.pos(), key.side(), reply);
                return;
            } else if (snapshot.get().settled()) {
                entry.inFlightRequest = null;
            } else {
                return;
            }
        }
        if (entry.serverReceivedAt > 0 && now - entry.serverReceivedAt < SERVER_FRESH_MILLIS) return;
        JsonObject body = new JsonObject();
        body.addProperty("x", key.pos().getX());
        body.addProperty("y", key.pos().getY());
        body.addProperty("z", key.pos().getZ());
        if (key.side() != null) body.addProperty("side", Ae2PartKeys.sideName(key.side()));
        serverReads.ask(DEVICE_OPERATION, body).ifPresent(id -> entry.inFlightRequest = id);
    }

    // 把服务端 ae2.device 的 JSON 字段翻译进设置缓存与额外读数表，兼容扁平字段与嵌套 settings 对象。
    private static void mergeServerPayload(Entry entry, JsonObject payload, boolean overwriteSettings) {
        if (payload.has("settings") && payload.get("settings").isJsonObject()) {
            JsonObject settingsObj = payload.getAsJsonObject("settings");
            for (Map.Entry<String, JsonElement> item : settingsObj.entrySet()) {
                if (item.getValue().isJsonPrimitive()) {
                    putSetting(entry, item.getKey(), item.getValue().getAsString(), overwriteSettings);
                }
            }
        }
        mergeIndexedArray(entry, payload, "filters", "filter.", overwriteSettings);
        mergeIndexedArray(entry, payload, "upgrades", "upgrade.", overwriteSettings);
        mergeIndexedArray(entry, payload, "stock", "stock.", overwriteSettings);
        mergeScalars(entry, payload, overwriteSettings);
    }

    private static void mergeIndexedArray(Entry entry, JsonObject payload, String field, String prefix,
                                          boolean overwriteSettings) {
        if (!payload.has(field) || !payload.get(field).isJsonArray()) return;
        JsonArray array = payload.getAsJsonArray(field);
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            String value = element.isJsonNull() ? Ae2PartKeys.EMPTY
                    : element.isJsonPrimitive() ? element.getAsString() : Ae2PartKeys.EMPTY;
            putSetting(entry, prefix + i, value.isEmpty() ? Ae2PartKeys.EMPTY : value, overwriteSettings);
        }
    }

    private static void mergeScalars(Entry entry, JsonObject payload, boolean overwriteSettings) {
        if (payload.has("priority") && payload.get("priority").isJsonPrimitive()) {
            String value = Long.toString(payload.get("priority").getAsLong());
            putSetting(entry, "priority", value, overwriteSettings);
            entry.readings.put("priority", payload.get("priority").getAsLong());
        }
        if (payload.has("reporting_value") && payload.get("reporting_value").isJsonPrimitive()) {
            String value = Long.toString(payload.get("reporting_value").getAsLong());
            putSetting(entry, "reporting_value", value, overwriteSettings);
            entry.readings.put("reporting_value", payload.get("reporting_value").getAsLong());
        }
        if (payload.has("is_output") && payload.get("is_output").isJsonPrimitive()) {
            boolean isOutput = payload.get("is_output").getAsBoolean();
            putSetting(entry, "p2p_role", isOutput ? "output" : "input", overwriteSettings);
            entry.readings.put("p2p_is_output", isOutput);
        }
        if (payload.has("boosters") && payload.get("boosters").isJsonPrimitive()) {
            int boosters = payload.get("boosters").getAsInt();
            putSetting(entry, "boosters", Integer.toString(boosters), overwriteSettings);
            entry.readings.put("boosters", boosters);
        }
        if (payload.has("connected_to") && payload.get("connected_to").isJsonPrimitive()) {
            entry.readings.put("connected_to", payload.get("connected_to").getAsString());
        }
        if (payload.has("has_redstone_card") && payload.get("has_redstone_card").isJsonPrimitive()) {
            entry.readings.put("has_redstone_card", payload.get("has_redstone_card").getAsBoolean());
        }
        if (payload.has("enchantments") && payload.get("enchantments").isJsonArray()) {
            StringBuilder joined = new StringBuilder();
            for (JsonElement element : payload.getAsJsonArray("enchantments")) {
                if (!element.isJsonPrimitive()) continue;
                if (!joined.isEmpty()) joined.append(", ");
                joined.append(element.getAsString());
            }
            entry.readings.put("enchantments", joined.isEmpty() ? "none" : joined.toString());
        }
        if (payload.has("cell") && payload.get("cell").isJsonPrimitive()) {
            String cell = payload.get("cell").getAsString();
            putSetting(entry, "cell", cell.isEmpty() || cell.equals(Ae2PartKeys.EMPTY) ? "out" : "in:" + cell,
                    overwriteSettings);
            entry.readings.put("cell", cell);
        }
    }

    private static void putSetting(Entry entry, String key, String value, boolean overwrite) {
        if (overwrite || !entry.settings.containsKey(key)) {
            entry.settings.put(key, value);
        }
    }
}
