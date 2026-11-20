// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.compat.ae2;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import org.maiwithu.maicraft.network.ServerOperationException;
import org.maiwithu.maicraft.server.compat.AskedCell;
import org.maiwithu.maicraft.server.compat.ServerCompatModule;
import org.maiwithu.maicraft.server.compat.ServerCompatRegistry;

/**
 * AE2 服务端设备级只读操作 {@code ae2.device}（版本 1）：
 * 按格坐标及可选面名 {@code side} 回答客户端方块实体流不同步的权威字段——
 * 线缆部件（升级卡、配置项、过滤/备料槽、优先级、发信阈值与触发态、P2P 出入端与频率、破坏面板附魔）、
 * 无线访问点（增幅卡数量、最大范围与耗电）、振动室/物质聚合器/压印器/分子组装室/元件工作台/ME IO 传输站
 * （非同步配置、升级卡、内部进度与缓冲电量）、量子环与链接仓（奇点频率与对端跨维连通）、
 * 空间塔/空间传输站/空间锚（区域尺寸、塔块数、效率、切换能耗、已加载区块与多维统计）。
 *
 * <p>请求 JSON 约定：{@code { "x": int, "y": int, "z": int, "side": "north|south|east|west|up|down"(可选) }}。
 * 响应 JSON 约定：{@code { "found": boolean, "kind": string, "block_id": string, "side": string,
 * "part_id": string, "settings": { key: value }, "upgrades": [ itemId... ], "readings": { ... } }}。
 * 严格经过 {@link AskedCell#nearPlayer} 限制在角色所在维度、已加载且距离不超过 128 格范围内。
 */
public final class Ae2DeviceServerReads {

    /** 只读操作名：与客户端按格查询设备权威读数的请求名一致。 */
    public static final String OPERATION = "ae2.device";
    /** 当前只读操作版本号。 */
    public static final int VERSION = 1;

    private final ServerCompatModule module;
    private final Reader reader;

    /**
     * 服务端读取单格 AE2 设备或线缆部件权威状态的接缝；直接调 AE2 公开接口的实现在 NeoForge 模块。
     */
    public interface Reader {

        /** 没接读写端时的空实现：任何格子都返回空。 */
        Reader NONE = (level, at, side) -> Optional.empty();

        /**
         * 读取指定已加载格的 AE2 设备或某面部件的权威快照；非 AE2 设备或指定面无部件时返回空。
         *
         * @param level 服务端世界
         * @param at    目标坐标
         * @param side  可选面方向；非空时优先读线缆宿主该面上的部件
         */
        Optional<DeviceSnapshot> read(ServerLevel level, BlockPos at, Direction side);
    }

    /**
     * 一台 AE2 设备或一面线缆部件的权威只读快照。
     *
     * @param kind     设备分类标识（如 cable_part、wireless_access_point、quantum_link、spatial_anchor）
     * @param blockId  方块注册 ID
     * @param side     查询的是线缆某面部件时为小写面名，否则为空串
     * @param partId   线缆部件注册 ID，否则为空串
     * @param settings 由配置项导出的权威设置键值
     * @param upgrades 已安装的升级卡物品注册 ID 列表
     * @param readings 该设备或部件特有的权威标量、列表或嵌套映射字段
     */
    public record DeviceSnapshot(String kind, String blockId, String side, String partId,
                                 Map<String, String> settings, List<String> upgrades,
                                 Map<String, Object> readings) {
        public DeviceSnapshot {
            kind = Objects.requireNonNull(kind, "kind");
            blockId = Objects.requireNonNull(blockId, "blockId");
            side = side == null ? "" : side;
            partId = partId == null ? "" : partId;
            settings = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(settings, "settings")));
            upgrades = List.copyOf(Objects.requireNonNull(upgrades, "upgrades"));
            readings = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(readings, "readings")));
        }
    }

    public Ae2DeviceServerReads(ServerCompatModule module, Reader reader) {
        this.module = Objects.requireNonNull(module, "module");
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    /** 向服务端联动登记表注册 ae2.device 只读操作（供服务端入口汇总接线调用）。 */
    public static void register(ServerCompatModule module, ServerCompatRegistry registry, Reader reader) {
        Objects.requireNonNull(registry, "registry");
        Ae2DeviceServerReads handler = new Ae2DeviceServerReads(module, reader);
        registry.readOperation(module, OPERATION, VERSION, handler::answer);
    }

    /**
     * 回答一次 ae2.device 查询：先校验坐标在角色同维已加载 128 格内，再解析可选面名并读取设备快照。
     */
    public JsonObject answer(ServerPlayer player, JsonObject request) {
        AskedCell cell = AskedCell.nearPlayer(player, request);
        Direction side = parseSide(request);
        Optional<DeviceSnapshot> snapshot = module.call("读 AE2 设备权威状态",
                () -> reader.read(cell.level(), cell.at(), side));
        return snapshot.map(Ae2DeviceServerReads::toJson).orElseGet(Ae2DeviceServerReads::notFound);
    }

    /** 构造“该格不是 AE2 设备或该面无部件”的标准回答。 */
    public static JsonObject notFound() {
        JsonObject out = new JsonObject();
        out.addProperty("found", false);
        return out;
    }

    /**
     * 离线复用与 {@link AskedCell#nearPlayer} 相同的 128 格同维已加载约束（用于单元测试覆盖边界语义）。
     */
    static BlockPos validateNearCell(BlockPos playerAt, boolean sameDimension, boolean loaded, JsonObject request) {
        BlockPos at = new BlockPos(readInt(request, "x"), readInt(request, "y"), readInt(request, "z"));
        if (!sameDimension || !loaded) {
            throw ServerOperationException.notApplied("not_loaded", "那一格没加载或不在角色当前维度");
        }
        if (playerAt.distSqr(at) > (double) AskedCell.MAX_DISTANCE * AskedCell.MAX_DISTANCE) {
            throw ServerOperationException.notApplied("too_far", "那一格离角色太远，只答附近的设备");
        }
        return at;
    }

    private static int readInt(JsonObject request, String key) {
        if (!request.has(key) || !request.get(key).isJsonPrimitive()) {
            throw ServerOperationException.notApplied("bad_request", "请求缺坐标 " + key);
        }
        return request.get(key).getAsInt();
    }

    /** 把设备快照序列化为稳定 JSON：顶层保留统一元信息，特有字段写入 readings。 */
    public static JsonObject toJson(DeviceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        JsonObject out = new JsonObject();
        out.addProperty("found", true);
        out.addProperty("kind", snapshot.kind());
        out.addProperty("block_id", snapshot.blockId());
        if (!snapshot.side().isEmpty()) {
            out.addProperty("side", snapshot.side());
        }
        if (!snapshot.partId().isEmpty()) {
            out.addProperty("part_id", snapshot.partId());
        }
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

    /** 从请求体解析可选面方向；缺省返回 null，给了非法方向按 bad_request 拒绝。 */
    static Direction parseSide(JsonObject request) {
        if (!request.has("side") || request.get("side").isJsonNull()) return null;
        if (!request.get("side").isJsonPrimitive()) {
            throw ServerOperationException.notApplied("bad_request", "side 必须是方向字符串");
        }
        String raw = request.get("side").getAsString().trim().toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) return null;
        Direction side = Direction.byName(raw);
        if (side == null) {
            throw ServerOperationException.notApplied("bad_request", "不认识的方向 side：" + raw);
        }
        return side;
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
