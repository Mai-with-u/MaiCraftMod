// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.advanced;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.NetworkKind;

/**
 * AE2 量子环（ae2:quantum_ring）与量子链接仓（ae2:quantum_link）的纯规则判断：
 * <ul>
 * <li>3×3×1 平面结构审阅：8 块量子环围住中心 1 块量子链接仓（支持 XY / YZ / XZ 任一平面）；</li>
 * <li>接线点约束：仅 4 条边中点环块可接外部 ME 线缆，四角环块与中心链接仓绝不暴露外部线缆接口；</li>
 * <li>状态读数合并：把客户端同步的成型/四角/供电/奇点标志，与服务端或开菜单缓存的频率及跨维连通态合为可读状态，
 * 并附带两端所在区块均须保持加载的提醒。</li>
 * </ul>
 */
public final class QuantumRing {

    /** 量子环方块注册 ID。 */
    public static final String RING_BLOCK = "ae2:quantum_ring";
    /** 量子链接仓方块注册 ID。 */
    public static final String LINK_BLOCK = "ae2:quantum_link";
    /** 量子缠绕奇点物品注册 ID。 */
    public static final String ENTANGLED_SINGULARITY = "ae2:quantum_entangled_singularity";
    /** 量子链接仓打开的菜单类型注册 ID。 */
    public static final String QNB_MENU = "ae2:qnb";
    /** 两端量子环互通的必备区块加载提示。 */
    static final String CHUNK_LOAD_REMINDER = "两端量子环所在区块都必须保持加载（可配合空间锚）才能跨维连通";

    private static final NetworkKind ME_NETWORK = new NetworkKind("me",
            "应用能源2 的 ME 网络：线缆把控制器、驱动器、接口、终端连起来的一张网");

    private QuantumRing() {
    }

    /**
     * 3×3×1 平面结构的审阅结果。
     *
     * @param valid        是否已完整建成（中心为量子链接仓且同一平面周围 8 格全为量子环）
     * @param plane        命中的平面名（xy / yz / xz）；未成型时为最接近的平面
     * @param missingRings 该平面上缺少量子环的坐标列表
     * @param note         给玩家和审阅看的中文提示（含两端区块加载要求）
     */
    public record StructureReview(boolean valid, String plane, List<BlockPos> missingRings, String note) {
        public StructureReview {
            missingRings = List.copyOf(missingRings);
        }
    }

    /**
     * 按中心量子链接仓位置校验 3×3×1 平面结构：分别核对 XY、YZ、XZ 三个平面，
     * 任一平面周围 8 格全是量子环且中心为量子链接仓即判定结构完整。
     */
    public static StructureReview reviewStructure(BlockPos center, Predicate<BlockPos> isRing,
                                                  Predicate<BlockPos> isLink) {
        if (!isLink.test(center)) {
            return new StructureReview(false, "", List.of(center.immutable()),
                    "中心格 " + center.toShortString() + " 需要放置量子链接仓（ae2:quantum_link），外围 8 格用量子环（ae2:quantum_ring）拼成 3×3×1 平面；"
                            + CHUNK_LOAD_REMINDER);
        }
        String bestPlane = "xy";
        List<BlockPos> bestMissing = null;
        for (Plane plane : Plane.values()) {
            List<BlockPos> missing = new ArrayList<>();
            for (BlockPos offset : plane.ringOffsets()) {
                BlockPos target = center.offset(offset);
                if (!isRing.test(target)) {
                    missing.add(target.immutable());
                }
            }
            if (missing.isEmpty()) {
                return new StructureReview(true, plane.label, List.of(),
                        "3×3×1 量子环（" + plane.label + " 平面，8 环 + 中心链接仓）结构完整；仅 4 条边中点可接外部线缆；"
                                + CHUNK_LOAD_REMINDER);
            }
            if (bestMissing == null || missing.size() < bestMissing.size()) {
                bestMissing = missing;
                bestPlane = plane.label;
            }
        }
        return new StructureReview(false, bestPlane, bestMissing,
                "3×3×1 量子环尚未成型（最接近 " + bestPlane + " 平面，还缺 " + bestMissing.size()
                        + " 块量子环）；" + CHUNK_LOAD_REMINDER);
    }

    /**
     * 计算量子环或链接仓对外暴露的 ME 线缆连接口：
     * 中心链接仓与四角量子环绝不暴露外部线缆接口，只有成型后的 4 条边中点环块暴露 6 向 ME 接线点。
     */
    public static List<ExchangePoint> exchangePoints(String blockId, BlockPos at,
                                                     Optional<Ae2AdvancedReads.QuantumCell> cell) {
        if (LINK_BLOCK.equals(blockId) || cell.map(Ae2AdvancedReads.QuantumCell::center).orElse(false)) {
            return List.of();
        }
        if (cell.isEmpty() || !cell.get().formed() || cell.get().corner()) {
            // 未成型或属于 3×3 四角环块：AE2 不允许从这些位置接入外部线缆。
            return List.of();
        }
        List<ExchangePoint> points = new ArrayList<>(Direction.values().length);
        for (Direction side : Direction.values()) {
            points.add(new ExchangePoint(at, side, null, ME_NETWORK, ExchangePoint.Flow.BOTH,
                    "量子环边中点：这一面可接外部 ME 线缆（四角不可连，整环载 32 频道）"));
        }
        return List.copyOf(points);
    }

    /**
     * 汇总量子环/量子链接仓的运行状态与读数。
     */
    public static MachineState state(String blockId, Ae2AdvancedReads.QuantumCell cell,
                                     Optional<JsonObject> serverReply, Optional<Long> cachedFrequency,
                                     Optional<StructureReview> review) {
        Map<String, Object> readings = new LinkedHashMap<>();
        boolean isCenter = cell.center() || LINK_BLOCK.equals(blockId);
        readings.put("role", isCenter ? "quantum_link" : "quantum_ring");
        readings.put("formed", cell.formed());
        readings.put("corner", cell.corner());
        readings.put("powered", cell.powered());
        readings.put("has_singularity", cell.hasSingularity());
        long frequency = resolveFrequency(cell, serverReply, cachedFrequency);
        if (frequency != 0L) {
            readings.put("frequency", frequency);
        } else if (cell.hasSingularity()) {
            readings.put("frequency", "unknown");
        }
        serverReply.flatMap(reply -> Ae2DeviceReadings.readBoolean(reply, "peer_connected"))
                .ifPresent(connected -> readings.put("peer_connected", connected));
        serverReply.flatMap(reply -> Ae2DeviceReadings.readBoolean(reply, "peer_cross_dimension"))
                .ifPresent(cross -> readings.put("peer_cross_dimension", cross));
        serverReply.flatMap(reply -> Ae2DeviceReadings.readString(reply, "peer_dimension"))
                .filter(text -> !text.isEmpty())
                .ifPresent(dim -> readings.put("peer_dimension", dim));

        List<String> notes = new ArrayList<>();
        if (!cell.formed()) {
            notes.add(review.map(StructureReview::note)
                    .orElse("未成型：需 8 块量子环围住中心 1 块量子链接仓组成 3×3×1 平面；" + CHUNK_LOAD_REMINDER));
            return new MachineState(MachineState.Activity.OFFLINE, null, null, List.of(), readings,
                    String.join("；", notes));
        }
        if (cell.corner()) {
            notes.add("四角环块不接外部线缆，请把 ME 线缆接到 4 条边中点");
        }
        if (!cell.hasSingularity()) {
            notes.add("中心链接仓尚未放入量子缠绕奇点");
        } else if (frequency == 0L) {
            notes.add("客户端不同步奇点频率：打开中心链接仓或启用服务端 ae2.device 可读出频率与对端连通态");
        }
        Boolean peerConnected = serverReply.flatMap(reply -> Ae2DeviceReadings.readBoolean(reply, "peer_connected"))
                .orElse(null);
        if (Boolean.TRUE.equals(peerConnected)) {
            notes.add("已与对端量子环建立跨区连通");
        } else if (cell.hasSingularity()) {
            notes.add(CHUNK_LOAD_REMINDER);
        }
        MachineState.Activity activity = !cell.powered() ? MachineState.Activity.OFFLINE
                : cell.hasSingularity() ? MachineState.Activity.RUNNING : MachineState.Activity.IDLE;
        return new MachineState(activity, null, null, List.of(), readings, String.join("；", notes));
    }

    private static long resolveFrequency(Ae2AdvancedReads.QuantumCell cell, Optional<JsonObject> serverReply,
                                         Optional<Long> cachedFrequency) {
        if (serverReply.isPresent()) {
            long fromServer = Ae2DeviceReadings.readLong(serverReply.get(), "frequency").orElse(0L);
            if (fromServer != 0L) return fromServer;
        }
        if (cell.frequency() != 0L) return cell.frequency();
        return cachedFrequency.orElse(0L);
    }

    /** 3×3×1 量子环支持的三个坐标平面及其外围 8 格偏移量。 */
    private enum Plane {
        XY("xy"),
        YZ("yz"),
        XZ("xz");

        private final String label;

        Plane(String label) {
            this.label = label;
        }

        private List<BlockPos> ringOffsets() {
            List<BlockPos> out = new ArrayList<>(8);
            for (int a = -1; a <= 1; a++) {
                for (int b = -1; b <= 1; b++) {
                    if (a == 0 && b == 0) continue;
                    out.add(switch (this) {
                        case XY -> new BlockPos(a, b, 0);
                        case YZ -> new BlockPos(0, a, b);
                        case XZ -> new BlockPos(a, 0, b);
                    });
                }
            }
            return out;
        }
    }
}
