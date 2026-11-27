// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.compat.advancedae;

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
 * 高级 AE 服务端设备级只读操作 {@code advanced_ae.device}（版本 1）：
 * 按坐标查询反应仓的权威状态——双流体槽（槽 1 输入、槽 0 输出）、输入输出物品槽快照、
 * 加工进度、缓冲电量、自动输出开关、相对六面输出配置、升级卡与缺电/阻塞告警。
 *
 * <p>所有查询严格经过 {@link AskedCell#nearPlayer} 校验，仅允许同维度已加载且距离角色
 * 不超过 128 格范围内的只读读取，不修改世界状态。
 */
public final class AdvancedAeServerReads {

    /** 只读操作名：与客户端查询请求一致。 */
    public static final String OPERATION = "advanced_ae.device";
    /** 当前只读操作版本号。 */
    public static final int VERSION = 1;

    private final ServerCompatModule module;
    private final Reader reader;

    /**
     * 服务端读取单格高级 AE 设备权威状态的接缝；直接调模组公开接口的实现在 NeoForge 模块。
     */
    public interface Reader {

        /** 没接读写端时的空实现：任何格子均返回空。 */
        Reader NONE = (level, at) -> Optional.empty();

        /** 读取指定已加载格的高级 AE 设备快照；非高级 AE 设备返回空。 */
        Optional<DeviceSnapshot> read(ServerLevel level, BlockPos at);
    }

    /**
     * 一台高级 AE 设备的服务端权威只读快照。
     *
     * @param kind     设备分类标识（如 reaction_chamber）
     * @param blockId  方块注册 ID
     * @param settings 权威设置键值（如 auto_export）
     * @param upgrades 升级槽物品注册 ID 列表（空槽为空串）
     * @param readings 设备专项读数（流体槽、物品槽、进度、电量、允许输出面与告警）
     */
    public record DeviceSnapshot(String kind, String blockId,
                                 Map<String, String> settings, List<String> upgrades,
                                 Map<String, Object> readings) {
        public DeviceSnapshot {
            kind = Objects.requireNonNull(kind, "kind");
            blockId = Objects.requireNonNull(blockId, "blockId");
            settings = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(settings, "settings")));
            upgrades = List.copyOf(Objects.requireNonNull(upgrades, "upgrades"));
            readings = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(readings, "readings")));
        }
    }

    public AdvancedAeServerReads(ServerCompatModule module, Reader reader) {
        this.module = Objects.requireNonNull(module, "module");
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    /** 向服务端联动登记表注册 advanced_ae.device 只读操作。 */
    public static void register(ServerCompatModule module, ServerCompatRegistry registry, Reader reader) {
        Objects.requireNonNull(registry, "registry");
        AdvancedAeServerReads responder = new AdvancedAeServerReads(module, reader);
        registry.readOperation(module, OPERATION, VERSION, responder::answer);
    }

    /** 回答一次 advanced_ae.device 查询：先校验坐标在同维已加载 128 格内，再读设备权威快照。 */
    public JsonObject answer(ServerPlayer player, JsonObject request) {
        AskedCell cell = AskedCell.nearPlayer(player, request);
        Optional<DeviceSnapshot> snapshot = module.call("读高级 AE 设备权威状态",
                () -> reader.read(cell.level(), cell.at()));
        return snapshot.map(AdvancedAeServerReads::toJson).orElseGet(AdvancedAeServerReads::notFound);
    }

    /** 构造“该格不是高级 AE 设备”的标准回答。 */
    public static JsonObject notFound() {
        JsonObject out = new JsonObject();
        out.addProperty("found", false);
        return out;
    }

    /**
     * 离线校验与 {@link AskedCell#nearPlayer} 相同的同维已加载 128 格视野边界（用于离线单测覆盖）。
     */
    public static BlockPos validateNearCell(BlockPos playerAt, boolean sameDimension, boolean loaded,
                                            JsonObject request) {
        BlockPos at = new BlockPos(readInt(request, "x"), readInt(request, "y"), readInt(request, "z"));
        if (!sameDimension || !loaded) {
            throw ServerOperationException.notApplied("not_loaded", "那一格没加载或不在角色当前维度");
        }
        if (playerAt.distSqr(at) > (double) AskedCell.MAX_DISTANCE * AskedCell.MAX_DISTANCE) {
            throw ServerOperationException.notApplied("too_far", "那一格离角色太远，只答附近的设备");
        }
        return at;
    }

    /** 把设备快照序列化为稳定 JSON，供客户端解析。 */
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
        for (String upgrade : snapshot.upgrades()) {
            upgrades.add(upgrade);
        }
        out.add("upgrades", upgrades);
        JsonObject readings = new JsonObject();
        snapshot.readings().forEach((key, value) -> readings.add(key, toElement(value)));
        out.add("readings", readings);
        return out;
    }

    private static int readInt(JsonObject request, String key) {
        if (request == null || !request.has(key) || !request.get(key).isJsonPrimitive()) {
            throw ServerOperationException.notApplied("bad_request", "请求缺坐标 " + key);
        }
        return request.get(key).getAsInt();
    }

    private static JsonElement toElement(Object value) {
        if (value instanceof Boolean flag) return new JsonPrimitive(flag);
        if (value instanceof Number number) return new JsonPrimitive(number);
        if (value instanceof String text) return new JsonPrimitive(text);
        if (value instanceof List<?> list) {
            JsonArray array = new JsonArray();
            for (Object item : list) {
                array.add(toElement(item));
            }
            return array;
        }
        if (value instanceof Map<?, ?> map) {
            JsonObject obj = new JsonObject();
            map.forEach((k, v) -> obj.add(String.valueOf(k), toElement(v)));
            return obj;
        }
        return new JsonPrimitive(String.valueOf(value));
    }
}
