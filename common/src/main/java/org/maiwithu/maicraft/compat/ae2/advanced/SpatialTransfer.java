// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.advanced;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.machine.spi.FeedPlan;
import org.maiwithu.maicraft.ability.machine.spi.FullRun;
import org.maiwithu.maicraft.ability.machine.spi.MachineFeeding;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.RunRequest;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2DeviceOps;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * AE2 空间传输站（游戏方块 {@code ae2:spatial_io_port}，菜单 {@code ae2:spatialioport}）的设备操作委托：
 * <ul>
 * <li>状态读数：合并客户端在线态、开菜单缓存及服务端 {@code ae2.device} 的区域尺寸、效率、当前/最大/所需电量，
 * 以及槽 0 与槽 1 的空间存储元件状态（含 {@code SPATIAL_PLOT_INFO} 绑定与锁定尺寸）；</li>
 * <li>整段运行（{@link FullRun}）：放空间元件进槽 0、核对槽 1 为空、有相邻开关时触发低→高红石上升沿脉冲、
 * 等元件移至槽 1 收回背包；未切换时如实回报实测读数，不预判拦截也不伪造成功。</li>
 * </ul>
 */
public final class SpatialTransfer implements Ae2DeviceOps {

    /** 空间传输站方块注册 ID。 */
    public static final String BLOCK_ID = "ae2:spatial_io_port";
    /** 空间传输站菜单类型注册 ID。 */
    public static final String MENU_ID = "ae2:spatialioport";
    /** AE2 内置三种空间存储元件注册 ID。 */
    public static final Set<String> SPATIAL_CELL_ITEMS = Set.of(
            "ae2:spatial_storage_cell_2",
            "ae2:spatial_storage_cell_16",
            "ae2:spatial_storage_cell_128");

    private final Ae2Compat compat;
    private final Ae2AdvancedReads reads;
    private final Ae2DeviceReadings serverReadings;
    /** 本会话打开过空间传输站菜单后记下的最新同步状态。 */
    private final Map<BlockPos, Ae2AdvancedReads.SpatialMenuStatus> seenStatus = new HashMap<>();

    public SpatialTransfer(Ae2Compat compat, Ae2AdvancedReads reads, Ae2DeviceReadings serverReadings) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.serverReadings = Objects.requireNonNull(serverReadings, "serverReadings");
    }

    @Override public Optional<MachineState> state(BlockPos pos, ClientLevel level) {
        if (level == null) return Optional.empty();
        Optional<Ae2AdvancedReads.SpatialTransferCell> cell = reads.spatialTransfer(level, pos);
        if (cell.isEmpty()) return Optional.empty();
        return Optional.of(composeState(cell.get(),
                Optional.ofNullable(seenStatus.get(pos.immutable())),
                serverReadings.latest(pos, null)));
    }

    @Override public Optional<MachineFeeding> feeding(BlockPos pos, PlayerServices services) {
        return Optional.of(new MachineFeeding() {
            @Override public Optional<Action> feed(BlockPos at, FeedPlan.Entry item, Permissions permissions) {
                return Optional.empty();
            }

            @Override public Optional<Action> take(BlockPos at, String itemId, int count, Permissions permissions) {
                return Optional.empty();
            }

            @Override public Optional<FullRun> fullRun(BlockPos at, RunRequest request, Permissions permissions) {
                return Optional.of(new SpatialTransferRun(at.immutable(), request, permissions, compat, reads,
                        services, status -> seenStatus.put(at.immutable(), status)));
            }
        });
    }

    /** 合并客户端在线态、开窗缓存与服务端权威读数为统一状态（纯函数，便于离线单测）。 */
    public static MachineState composeState(Ae2AdvancedReads.SpatialTransferCell cell,
                                            Optional<Ae2AdvancedReads.SpatialMenuStatus> cachedMenu,
                                            Optional<JsonObject> serverReply) {
        Map<String, Object> readings = new LinkedHashMap<>();
        List<MachineState.Shown> shown = new ArrayList<>();
        readings.put("active", cell.active());

        if (serverReply.isPresent()) {
            applyServerReadings(readings, shown, serverReply.get());
        } else if (cachedMenu.isPresent()) {
            applyMenuReadings(readings, shown, cachedMenu.get());
        } else {
            readings.put("region_size", "unknown");
            readings.put("required_power", "unknown");
        }

        String note = buildStateNote(cell.active(), readings);
        MachineState.Activity activity = !cell.active() ? MachineState.Activity.OFFLINE : MachineState.Activity.IDLE;
        return new MachineState(activity, null, null, List.copyOf(shown), readings, note);
    }

    /**
     * 触发红石上升沿脉冲后，若空间元件仍停在槽 0 未移至槽 1，根据实测读数总结游戏未切换的客观原因（纯函数）。
     * 绝不提前拦截，只在真实脉冲结算未切换后用来向 LLM 如实回报现场读数。
     */
    public static List<String> diagnoseUnchanged(Optional<Ae2AdvancedReads.SpatialMenuStatus> menuStatus,
                                                 Optional<Ae2AdvancedReads.SpatialCellSpec> cellSpec,
                                                 boolean online) {
        List<String> notes = new ArrayList<>();
        if (!online) {
            notes.add("空间传输站当前离线（缺电或未分到 ME 频道）");
        }
        if (menuStatus.isEmpty()) {
            notes.add("脉冲后元件仍在槽 0，未读到区域与电量明细");
            return List.copyOf(notes);
        }
        Ae2AdvancedReads.SpatialMenuStatus status = menuStatus.get();
        if (status.outputCell().isPresent()) {
            notes.add("槽 1 已有元件（" + status.outputCell().get().itemId() + "）占用，必须腾空槽 1");
        }
        if (status.xSize() <= 0 || status.ySize() <= 0 || status.zSize() <= 0) {
            notes.add("空间塔尚未合围出有效区域（实测区域尺寸 0×0×0）");
        } else {
            String regionText = status.xSize() + "x" + status.ySize() + "x" + status.zSize();
            notes.add("实测区域尺寸 " + regionText + "，效率 " + String.format("%.1f%%", status.efficiency())
                    + "，电网电量 " + (long) status.currentPower() + " / 所需 " + (long) status.requiredPower() + " AE");
            if (status.requiredPower() > status.currentPower()) {
                notes.add("电网电量不足以完成本次空间切换（需补充能源元件存量）");
            }
            if (cellSpec.isPresent()) {
                Ae2AdvancedReads.SpatialCellSpec spec = cellSpec.get();
                int maxAxis = Math.max(status.xSize(), Math.max(status.ySize(), status.zSize()));
                if (spec.maxDim() > 0 && maxAxis > spec.maxDim()) {
                    notes.add("空间区域最大边 " + maxAxis + " 超过元件 " + spec.itemId() + " 上限 " + spec.maxDim());
                }
                if (spec.allocated() && !spec.lockedSizeText().isEmpty()
                        && !spec.lockedSizeText().equals(regionText)) {
                    notes.add("元件已绑定地块 #" + spec.allocatedPlotId() + "，锁定尺寸 " + spec.lockedSizeText()
                            + " 与当前区域 " + regionText + " 不一致");
                }
            }
        }
        return List.copyOf(notes);
    }

    private static void applyServerReadings(Map<String, Object> readings, List<MachineState.Shown> shown,
                                            JsonObject reply) {
        long x = Ae2DeviceReadings.readLong(reply, "x_size").orElse(0L);
        long y = Ae2DeviceReadings.readLong(reply, "y_size").orElse(0L);
        long z = Ae2DeviceReadings.readLong(reply, "z_size").orElse(0L);
        readings.put("x_size", x);
        readings.put("y_size", y);
        readings.put("z_size", z);
        readings.put("region_size", x + "x" + y + "x" + z);
        Ae2DeviceReadings.readDouble(reply, "efficiency").ifPresent(v -> readings.put("efficiency", v));
        Ae2DeviceReadings.readDouble(reply, "current_power").ifPresent(v -> readings.put("current_power", v));
        Ae2DeviceReadings.readDouble(reply, "max_power").ifPresent(v -> readings.put("max_power", v));
        Ae2DeviceReadings.readLong(reply, "required_power").ifPresent(v -> readings.put("required_power", v));
        Ae2DeviceReadings.readBoolean(reply, "redstone_powered").ifPresent(v -> readings.put("redstone_powered", v));
        copyCellFromServer(readings, shown, reply, "input");
        copyCellFromServer(readings, shown, reply, "output");
    }

    private static void copyCellFromServer(Map<String, Object> readings, List<MachineState.Shown> shown,
                                           JsonObject reply, String prefix) {
        String cellId = Ae2DeviceReadings.readString(reply, prefix + "_cell").orElse("");
        readings.put(prefix + "_cell", cellId.isEmpty() ? "empty" : cellId);
        if (!cellId.isEmpty()) {
            shown.add(new MachineState.Shown(cellId, 1));
            Ae2DeviceReadings.readLong(reply, prefix + "_cell_max_dim")
                    .ifPresent(v -> readings.put(prefix + "_cell_max_dim", v));
            Ae2DeviceReadings.readLong(reply, prefix + "_plot_id")
                    .ifPresent(v -> readings.put(prefix + "_plot_id", v));
            Ae2DeviceReadings.readString(reply, prefix + "_plot_size")
                    .filter(s -> !s.isEmpty())
                    .ifPresent(v -> readings.put(prefix + "_plot_size", v));
        }
    }

    private static void applyMenuReadings(Map<String, Object> readings, List<MachineState.Shown> shown,
                                          Ae2AdvancedReads.SpatialMenuStatus status) {
        readings.put("x_size", (long) status.xSize());
        readings.put("y_size", (long) status.ySize());
        readings.put("z_size", (long) status.zSize());
        readings.put("region_size", status.xSize() + "x" + status.ySize() + "x" + status.zSize());
        readings.put("efficiency", status.efficiency());
        readings.put("current_power", status.currentPower());
        readings.put("max_power", status.maxPower());
        readings.put("required_power", status.requiredPower());
        writeCellSpec(readings, shown, "input", status.inputCell());
        writeCellSpec(readings, shown, "output", status.outputCell());
    }

    private static void writeCellSpec(Map<String, Object> readings, List<MachineState.Shown> shown, String prefix,
                                      Optional<Ae2AdvancedReads.SpatialCellSpec> cellOpt) {
        if (cellOpt.isEmpty()) {
            readings.put(prefix + "_cell", "empty");
            return;
        }
        Ae2AdvancedReads.SpatialCellSpec spec = cellOpt.get();
        readings.put(prefix + "_cell", spec.itemId());
        readings.put(prefix + "_cell_max_dim", (long) spec.maxDim());
        readings.put(prefix + "_plot_id", (long) spec.allocatedPlotId());
        if (!spec.lockedSizeText().isEmpty()) {
            readings.put(prefix + "_plot_size", spec.lockedSizeText());
        }
        shown.add(new MachineState.Shown(spec.itemId(), 1));
    }

    private static String buildStateNote(boolean active, Map<String, Object> readings) {
        if (!active) return "空间传输站离线（缺电或未分到频道）";
        if ("unknown".equals(readings.get("region_size"))) {
            return "区域尺寸、电量与元件槽位需打开界面或启用服务端 ae2.device 读取；切换需对本方块给一次红石低→高上升沿";
        }
        return "区域 " + readings.get("region_size") + "，切换需槽 0 放空间元件、槽 1 为空并给一次红石低→高上升沿";
    }
}
