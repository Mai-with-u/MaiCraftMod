// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.advanced;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.compat.ae2.Ae2DeviceOps;

/**
 * AE2 空间塔（{@code ae2:spatial_pylon}）的设备操作委托与公式计算：
 * 客户端同步供电、在线、轴向与塔列位置；结合服务端只读操作 {@code ae2.device}
 * 补全合围内腔尺寸（{@code x_size × y_size × z_size}）、当前塔块数、所需塔块数、效率与切换总能耗。
 */
public final class SpatialPylons implements Ae2DeviceOps {

    /** 空间塔方块注册 ID。 */
    public static final String BLOCK_ID = "ae2:spatial_pylon";
    /** AE2 默认空间能耗系数。F 游戏事实。 */
    public static final double DEFAULT_POWER_MULTIPLIER = 1250.0;
    /** AE2 默认空间能耗指数。F 游戏事实。 */
    public static final double DEFAULT_POWER_EXPONENT = 1.35;

    private final Ae2AdvancedReads reads;
    private final Ae2DeviceReadings serverReadings;

    public SpatialPylons(Ae2AdvancedReads reads, Ae2DeviceReadings serverReadings) {
        this.reads = Objects.requireNonNull(reads, "reads");
        this.serverReadings = Objects.requireNonNull(serverReadings, "serverReadings");
    }

    /**
     * 按 AE2 规则计算合围外包盒跨度（{@code span = max - min = inner + 1}）所需的最少空间塔块数：
     * {@code max(6, (spanX*spanZ + spanX*spanY + spanY*spanZ) * 3 / 8)}。
     */
    public static int requiredPylonBlocks(int spanX, int spanY, int spanZ) {
        if (spanX <= 1 || spanY <= 1 || spanZ <= 1) return 0;
        long surface = (long) spanX * spanZ + (long) spanX * spanY + (long) spanY * spanZ;
        return (int) Math.max(6L, (surface * 3L) / 8L);
    }

    /**
     * 按内腔尺寸（{@code innerX × innerY × innerZ}）换算外包盒跨度后计算所需空间塔块数。
     */
    public static int requiredPylonBlocksForInner(int innerX, int innerY, int innerZ) {
        if (innerX <= 0 || innerY <= 0 || innerZ <= 0) return 0;
        return requiredPylonBlocks(innerX + 1, innerY + 1, innerZ + 1);
    }

    /**
     * 计算空间塔效率（范围 0.0..1.0）：{@code min(1.0, pylonBlocks / requiredBlocks)}。
     */
    public static double efficiency(int pylonBlocks, int requiredBlocks) {
        if (requiredBlocks <= 0 || pylonBlocks <= 0) return 0.0;
        return Math.min(1.0, (double) pylonBlocks / requiredBlocks);
    }

    /**
     * 按 AE2 默认配置计算空间切换总能耗（AE）：
     * {@code (spanX * spanY * spanZ * 1250) ^ (1 + 0.35 * (1 - efficiency))}。
     */
    public static long requiredPower(int spanX, int spanY, int spanZ, double efficiency) {
        if (spanX <= 1 || spanY <= 1 || spanZ <= 1) return 0L;
        double clamped = Math.max(0.0, Math.min(1.0, efficiency));
        double base = (double) spanX * (double) spanY * (double) spanZ * DEFAULT_POWER_MULTIPLIER;
        double exponent = 1.0 + (DEFAULT_POWER_EXPONENT - 1.0) * (1.0 - clamped);
        return (long) Math.pow(base, exponent);
    }

    /** 按内腔尺寸与效率计算空间切换总能耗（AE）。 */
    public static long requiredPowerForInner(int innerX, int innerY, int innerZ, double efficiency) {
        if (innerX <= 0 || innerY <= 0 || innerZ <= 0) return 0L;
        return requiredPower(innerX + 1, innerY + 1, innerZ + 1, efficiency);
    }

    @Override public Optional<MachineState> state(BlockPos pos, ClientLevel level) {
        if (level == null) return Optional.empty();
        Optional<Ae2AdvancedReads.SpatialPylonCell> cell = reads.spatialPylon(level, pos);
        if (cell.isEmpty()) return Optional.empty();
        return Optional.of(composeState(cell.get(), serverReadings.latest(pos, null)));
    }

    /** 把空间塔客户端读数与服务端区域统计合成为统一状态（纯函数，便于离线单测）。 */
    public static MachineState composeState(Ae2AdvancedReads.SpatialPylonCell cell, Optional<JsonObject> serverReply) {
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("powered", cell.powered());
        readings.put("online", cell.online());
        readings.put("axis", cell.axis());
        readings.put("axis_position", cell.axisPosition());

        StringBuilder note = new StringBuilder();
        if (serverReply.isPresent()) {
            JsonObject reply = serverReply.get();
            Ae2DeviceReadings.readBoolean(reply, "valid_region").ifPresent(v -> readings.put("valid_region", v));
            long xSize = Ae2DeviceReadings.readLong(reply, "x_size").orElse(0L);
            long ySize = Ae2DeviceReadings.readLong(reply, "y_size").orElse(0L);
            long zSize = Ae2DeviceReadings.readLong(reply, "z_size").orElse(0L);
            readings.put("x_size", xSize);
            readings.put("y_size", ySize);
            readings.put("z_size", zSize);
            Ae2DeviceReadings.readLong(reply, "pylon_blocks").ifPresent(v -> readings.put("pylon_blocks", v));
            Ae2DeviceReadings.readLong(reply, "required_pylon_blocks")
                    .ifPresent(v -> readings.put("required_pylon_blocks", v));
            Ae2DeviceReadings.readDouble(reply, "efficiency").ifPresent(v -> readings.put("efficiency", v));
            Ae2DeviceReadings.readLong(reply, "required_power").ifPresent(v -> readings.put("required_power", v));
            if (xSize > 0 && ySize > 0 && zSize > 0) {
                note.append("有效内腔 ").append(xSize).append("×").append(ySize).append("×").append(zSize);
            } else {
                note.append("尚未合围出有效空间区域（各轴需连续至少 2 格成塔且围出内腔）");
            }
        } else {
            note.append("合围尺寸、塔块数与切换总能耗需经服务端 ae2.device 或空间传输站界面读取");
        }
        MachineState.Activity activity = !cell.powered() ? MachineState.Activity.OFFLINE
                : cell.online() ? MachineState.Activity.RUNNING : MachineState.Activity.IDLE;
        return new MachineState(activity, null, null, List.of(), readings, note.toString());
    }
}
