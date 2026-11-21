// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.part;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
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

    // 把服务端 ae2.device 的 JSON 字段翻译进设置缓存与额外读数表，同时兼容顶层扁平字段与嵌套 {settings, upgrades, readings} 结构。
    private static void mergeServerPayload(Entry entry, JsonObject payload, boolean overwriteSettings) {
        boolean isInterface = isInterfacePayload(payload);
        if (payload.has("settings") && payload.get("settings").isJsonObject()) {
            mergeSettingsObject(entry, payload.getAsJsonObject("settings"), overwriteSettings);
        }
        mergeArraysAndScalars(entry, payload, isInterface, overwriteSettings);
        if (payload.has("readings") && payload.get("readings").isJsonObject()) {
            mergeArraysAndScalars(entry, payload.getAsJsonObject("readings"), isInterface, overwriteSettings);
        }
    }

    // 反向映射 AE2 IConfigManager.exportSettings() 的注册键名（redstone_controlled/redstone_emitter/place_block）。
    private static void mergeSettingsObject(Entry entry, JsonObject settingsObj, boolean overwriteSettings) {
        for (Map.Entry<String, JsonElement> item : settingsObj.entrySet()) {
            if (!item.getValue().isJsonPrimitive()) continue;
            String rawKey = item.getKey().toLowerCase(Locale.ROOT);
            String rawVal = item.getValue().getAsString().toLowerCase(Locale.ROOT);
            switch (rawKey) {
                case "redstone_controlled", "redstone_emitter" ->
                        putSetting(entry, "redstone_mode", rawVal, overwriteSettings);
                case "place_block" -> {
                    String mapped = rawVal.equals("yes") ? "block" : rawVal.equals("no") ? "drop" : rawVal;
                    putSetting(entry, "place_mode", mapped, overwriteSettings);
                }
                default -> putSetting(entry, rawKey, rawVal, overwriteSettings);
            }
        }
    }

    private static void mergeArraysAndScalars(Entry entry, JsonObject source, boolean isInterface,
                                              boolean overwriteSettings) {
        mergeIndexedArray(entry, source, "filters", "filter.", overwriteSettings);
        mergeIndexedArray(entry, source, "upgrades", "upgrade.", overwriteSettings);
        mergeIndexedArray(entry, source, "stock", "stock.", overwriteSettings);
        if (source.has("config_slots") && source.get("config_slots").isJsonObject()) {
            JsonObject slotTypes = source.has("config_slot_types") && source.get("config_slot_types").isJsonObject()
                    ? source.getAsJsonObject("config_slot_types") : null;
            mergeConfigSlotsObject(entry, source.getAsJsonObject("config_slots"), slotTypes, isInterface,
                    overwriteSettings);
        }
        mergeScalars(entry, source, overwriteSettings);
    }

    // 服务端 readings.config_slots 形如 {"0": "minecraft:iron_ingot x16"}：ME 接口映射至 stock.N，其余映射至 filter.N；
    // 若同时携带 config_slot_types 且标明为 chemical，补上 chemical: 前缀避免与同名流体混淆。
    private static void mergeConfigSlotsObject(Entry entry, JsonObject slotsObj, JsonObject slotTypes,
                                               boolean isInterface, boolean overwriteSettings) {
        String prefix = isInterface ? "stock." : "filter.";
        for (Map.Entry<String, JsonElement> item : slotsObj.entrySet()) {
            if (!item.getValue().isJsonPrimitive()) continue;
            String raw = item.getValue().getAsString().trim();
            boolean isChemical = slotTypes != null && slotTypes.has(item.getKey())
                    && slotTypes.get(item.getKey()).isJsonPrimitive()
                    && "chemical".equalsIgnoreCase(slotTypes.get(item.getKey()).getAsString());
            String formatted = formatServerConfigSlot(raw, isInterface, isChemical);
            putSetting(entry, prefix + item.getKey(), formatted, overwriteSettings);
        }
    }

    private static String formatServerConfigSlot(String raw, boolean isInterface, boolean isChemical) {
        if (raw.isEmpty() || raw.equalsIgnoreCase(Ae2PartKeys.EMPTY)) return Ae2PartKeys.EMPTY;
        int sep = raw.lastIndexOf(" x");
        if (sep > 0 && sep + 2 < raw.length()) {
            String id = raw.substring(0, sep).trim();
            String countText = raw.substring(sep + 2).trim();
            String target = isChemical ? Ae2PartKeys.CHEMICAL_PREFIX + id : id;
            if (!isInterface) return target;
            return target + " " + countText;
        }
        return isChemical ? Ae2PartKeys.CHEMICAL_PREFIX + raw : raw;
    }

    private static boolean isInterfacePayload(JsonObject payload) {
        if (payload.has("block_id") && payload.get("block_id").isJsonPrimitive()
                && "ae2:interface".equals(payload.get("block_id").getAsString())) {
            return true;
        }
        return payload.has("part_id") && payload.get("part_id").isJsonPrimitive()
                && "ae2:cable_interface".equals(payload.get("part_id").getAsString());
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
        mergeP2pScalars(entry, payload, overwriteSettings);
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
        if (payload.has("copy_mode") && payload.get("copy_mode").isJsonPrimitive()) {
            String mode = payload.get("copy_mode").getAsString().toLowerCase(Locale.ROOT);
            putSetting(entry, "copy_mode", mode, overwriteSettings);
            entry.readings.put("copy_mode", mode);
        }
        if (payload.has("fuzzy_mode") && payload.get("fuzzy_mode").isJsonPrimitive()) {
            String mode = payload.get("fuzzy_mode").getAsString().toLowerCase(Locale.ROOT);
            putSetting(entry, "fuzzy_mode", mode, overwriteSettings);
            entry.readings.put("fuzzy_mode", mode);
        }
        mergeEnchantments(entry, payload);
        mergeCellItem(entry, payload, overwriteSettings);
    }

    private static void mergeP2pScalars(Entry entry, JsonObject payload, boolean overwriteSettings) {
        Boolean isOutput = null;
        if (payload.has("p2p_output") && payload.get("p2p_output").isJsonPrimitive()) {
            isOutput = payload.get("p2p_output").getAsBoolean();
        } else if (payload.has("is_output") && payload.get("is_output").isJsonPrimitive()) {
            isOutput = payload.get("is_output").getAsBoolean();
        } else if (payload.has("p2p_is_output") && payload.get("p2p_is_output").isJsonPrimitive()) {
            isOutput = payload.get("p2p_is_output").getAsBoolean();
        }
        if (isOutput != null) {
            putSetting(entry, "p2p_role", isOutput ? "output" : "input", overwriteSettings);
            entry.readings.put("p2p_is_output", isOutput);
        }
        if (payload.has("p2p_frequency") && payload.get("p2p_frequency").isJsonPrimitive()) {
            int freq = payload.get("p2p_frequency").getAsInt() & 0xFFFF;
            entry.readings.put("p2p_frequency", freq);
        }
    }

    // 破坏面板附魔同时兼容服务端对象格式 {"minecraft:silk_touch": 1} 与旧数组格式。
    private static void mergeEnchantments(Entry entry, JsonObject payload) {
        if (!payload.has("enchantments")) return;
        JsonElement raw = payload.get("enchantments");
        StringBuilder joined = new StringBuilder();
        if (raw.isJsonObject()) {
            for (Map.Entry<String, JsonElement> item : raw.getAsJsonObject().entrySet()) {
                if (!item.getValue().isJsonPrimitive()) continue;
                if (!joined.isEmpty()) joined.append(", ");
                joined.append(item.getKey()).append(' ').append(item.getValue().getAsString());
            }
            entry.readings.put("enchantments", joined.isEmpty() ? "none" : joined.toString());
        } else if (raw.isJsonArray()) {
            for (JsonElement element : raw.getAsJsonArray()) {
                if (!element.isJsonPrimitive()) continue;
                if (!joined.isEmpty()) joined.append(", ");
                joined.append(element.getAsString());
            }
            entry.readings.put("enchantments", joined.isEmpty() ? "none" : joined.toString());
        }
    }

    // 元件工作台元件槽同时兼容 readings.cell_item 与扁平 cell 字段。
    private static void mergeCellItem(Entry entry, JsonObject payload, boolean overwriteSettings) {
        String cell = null;
        if (payload.has("cell_item") && payload.get("cell_item").isJsonPrimitive()) {
            cell = payload.get("cell_item").getAsString();
        } else if (payload.has("cell") && payload.get("cell").isJsonPrimitive()) {
            cell = payload.get("cell").getAsString();
        }
        if (cell == null) return;
        boolean empty = cell.isEmpty() || cell.equalsIgnoreCase(Ae2PartKeys.EMPTY);
        putSetting(entry, "cell", empty ? "out" : "in:" + cell, overwriteSettings);
        entry.readings.put("cell", empty ? Ae2PartKeys.EMPTY : cell);
    }

    private static void putSetting(Entry entry, String key, String value, boolean overwrite) {
        if (overwrite || !entry.settings.containsKey(key)) {
            entry.settings.put(key, value);
        }
    }
}
