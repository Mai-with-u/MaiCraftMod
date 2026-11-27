// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.compat.extendedae;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import org.maiwithu.maicraft.network.ServerOperationException;
import org.maiwithu.maicraft.server.compat.AskedCell;
import org.maiwithu.maicraft.server.compat.ServerCompatModule;
import org.maiwithu.maicraft.server.compat.ServerCompatRegistry;

/**
 * ExtendedAE 服务端只读操作 {@code extendedae.device}（版本 1）：
 * 按格坐标回答水晶组装器（储罐、进度、输出面、升级卡）、电路切片器、扩展压印器（4 页进度与配置）、
 * 扩展样板供应器（36 槽样板与优先级）、扩展接口与超大接口（36 槽备料与存量）、扩展分子组装室（8 线程进度）、
 * 扩展 IO 传输站、原料缓存器、装罐机、无线连接器与无线集线器（频率、对端坐标、能耗）以及组装矩阵多方块统计。
 *
 * <p>入口严格经 {@link AskedCell#nearPlayer} 校验：仅答角色同维度、已加载且距离不超过 128 格的格子。
 */
public final class ExtendedAeDeviceQuery {

    public static final String OPERATION = "extendedae.device";
    public static final int VERSION = 1;

    private final ServerCompatModule module;
    private final Reader reader;

    /** 服务端单格 ExtendedAE 设备权威读数接缝；直接调模组公开接口的实现位于 NeoForge 模块。 */
    public interface Reader {

        Reader NONE = (level, at) -> Optional.empty();

        /** 读取指定已加载格的 ExtendedAE 设备权威快照；非目标设备时返回空。 */
        Optional<DeviceSnapshot> read(ServerLevel level, BlockPos at);
    }

    /**
     * 一台 ExtendedAE 设备的权威只读快照。
     *
     * @param kind     设备分类标识（如 crystal_assembler、wireless_hub、assembler_matrix）
     * @param blockId  方块注册 ID
     * @param settings 权威配置键值
     * @param upgrades 升级槽物品 ID 列表（空槽写空串）
     * @param readings 设备特有的标量、列表或嵌套映射字段
     */
    public record DeviceSnapshot(String kind, String blockId, Map<String, String> settings,
            List<String> upgrades, Map<String, Object> readings) {
        public DeviceSnapshot {
            kind = Objects.requireNonNull(kind, "kind");
            blockId = Objects.requireNonNull(blockId, "blockId");
            settings = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(settings, "settings")));
            upgrades = List.copyOf(Objects.requireNonNull(upgrades, "upgrades"));
            readings = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(readings, "readings")));
        }
    }

    public ExtendedAeDeviceQuery(ServerCompatModule module, Reader reader) {
        this.module = Objects.requireNonNull(module, "module");
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    /** 向服务端联动登记表注册 {@code extendedae.device} 只读操作。 */
    public static void register(ServerCompatModule module, ServerCompatRegistry registry, Reader reader) {
        Objects.requireNonNull(registry, "registry");
        ExtendedAeDeviceQuery query = new ExtendedAeDeviceQuery(module, reader);
        registry.readOperation(module, OPERATION, VERSION, query::answer);
    }

    /** 回答一次 {@code extendedae.device} 请求：先经 {@link AskedCell#nearPlayer} 校验 128 格同维已加载边界。 */
    public JsonObject answer(ServerPlayer player, JsonObject request) {
        AskedCell cell = AskedCell.nearPlayer(player, request);
        Optional<DeviceSnapshot> snapshot = module.call("读 ExtendedAE 设备权威状态",
                () -> reader.read(cell.level(), cell.at()));
        return snapshot.map(ExtendedAeDeviceQuery::toJson).orElseGet(ExtendedAeDeviceQuery::notFound);
    }

    /** 构造“该格不是 ExtendedAE 设备”的标准回答。 */
    public static JsonObject notFound() {
        JsonObject out = new JsonObject();
        out.addProperty("found", false);
        return out;
    }

    /** 离线复用与 {@link AskedCell#nearPlayer} 一致的 128 格同维已加载边界校验（供单元测试核对）。 */
    public static BlockPos validateNearCell(BlockPos playerAt, boolean sameDimension, boolean loaded,
            JsonObject request) {
        BlockPos at = new BlockPos(readInt(request, "x"), readInt(request, "y"), readInt(request, "z"));
        if (!sameDimension || !loaded) {
            throw ServerOperationException.notApplied("not_loaded", "那一格没加载或不在角色当前维度");
        }
        if (playerAt.distSqr(at) > (double) AskedCell.MAX_DISTANCE * AskedCell.MAX_DISTANCE) {
            throw ServerOperationException.notApplied("too_far", "那一格离角色太远，只答 128 格以内的设备");
        }
        return at;
    }

    private static int readInt(JsonObject request, String key) {
        if (request == null || !request.has(key) || !request.get(key).isJsonPrimitive()) {
            throw ServerOperationException.notApplied("bad_request", "请求缺坐标 " + key);
        }
        return request.get(key).getAsInt();
    }

    /** 将设备快照序列化为稳定 JSON。 */
    public static JsonObject toJson(DeviceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        JsonObject out = new JsonObject();
        out.addProperty("found", true);
        out.addProperty("kind", snapshot.kind());
        out.addProperty("block_id", snapshot.blockId());
        JsonObject settings = new JsonObject();
        snapshot.settings().forEach(settings::addProperty);
        out.add("settings", settings);
        JsonArray upgrades = new JsonArray();
        for (String upgrade : snapshot.upgrades()) upgrades.add(upgrade);
        out.add("upgrades", upgrades);
        JsonObject readings = new JsonObject();
        snapshot.readings().forEach((k, v) -> readings.add(k, toElement(v)));
        out.add("readings", readings);
        return out;
    }

    private static JsonElement toElement(Object value) {
        if (value instanceof Boolean flag) return new JsonPrimitive(flag);
        if (value instanceof Number number) return new JsonPrimitive(number);
        if (value instanceof String text) return new JsonPrimitive(text);
        if (value instanceof List<?> list) {
            JsonArray arr = new JsonArray();
            for (Object item : list) arr.add(toElement(item));
            return arr;
        }
        if (value instanceof Map<?, ?> map) {
            JsonObject obj = new JsonObject();
            map.forEach((k, v) -> obj.add(String.valueOf(k), toElement(v)));
            return obj;
        }
        return new JsonPrimitive(String.valueOf(value));
    }
}
