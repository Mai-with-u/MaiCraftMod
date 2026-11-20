// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.machine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.FeedPlan;
import org.maiwithu.maicraft.ability.machine.spi.FullRun;
import org.maiwithu.maicraft.ability.machine.spi.MachineFeeding;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.NetworkKind;
import org.maiwithu.maicraft.ability.machine.spi.RunRequest;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2DeviceOps;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 充能器（ae2:charger）与木质曲柄（ae2:crank）的设备操作委托：
 * 充能器读数同步槽 0 物品与带电工具电量，非正面五面暴露连网/受电/装曲柄口，
 * 运行委托给 {@link ChargerFullRun}；木质曲柄读出朝向、附着主机格与可视旋转角。
 */
final class ChargerDeviceOps implements Ae2DeviceOps {

    /** ME 网络种类标识，与 ME 网络读取器保持同一 ID。 */
    static final NetworkKind ME_NETWORK = new NetworkKind("me", "应用能源2 的 ME 网络");

    private final Ae2MachineReads reads;

    ChargerDeviceOps(Ae2MachineReads reads) {
        this.reads = Objects.requireNonNull(reads, "reads");
    }

    // 充能器正面开口用于放取物品，其余五面可接 ME 线缆、受电与安装木质曲柄。
    @Override public Optional<List<ExchangePoint>> exchangePoints(BlockState state, BlockPos at, ClientLevel level) {
        Direction front = level == null ? null : reads.chargerAt(level, at)
                .map(Ae2MachineReads.ChargerView::front).orElse(null);
        List<ExchangePoint> points = new ArrayList<>(5);
        for (Direction side : Direction.values()) {
            if (side == front) continue;
            points.add(new ExchangePoint(at, side, null, ME_NETWORK, ExchangePoint.Flow.BOTH,
                    "充能器非正面：可连 ME 网、受电或安装木质曲柄"));
        }
        return Optional.of(List.copyOf(points));
    }

    @Override public Optional<MachineState> state(BlockPos pos, ClientLevel level) {
        if (level == null) return Optional.of(MachineState.unknown("角色不在世界里，读不到充能器"));
        Optional<Ae2MachineReads.ChargerView> found = reads.chargerAt(level, pos);
        if (found.isEmpty()) return Optional.empty();
        return Optional.of(buildChargerState(found.get()));
    }

    /** 纯函数：将充能器客户端同步视图转为标准机器状态。 */
    static MachineState buildChargerState(Ae2MachineReads.ChargerView view) {
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("working", view.working());
        if (view.front() != null) readings.put("front", view.front().getSerializedName());
        List<MachineState.Shown> shown = new ArrayList<>();
        if (view.slotEmpty()) {
            readings.put("slot_0", "empty");
        } else {
            readings.put("slot_0", view.slotItemId());
            shown.add(new MachineState.Shown(view.slotItemId(), view.slotCount()));
            if (view.chargeable()) {
                readings.put("item_energy", view.itemEnergy());
                readings.put("item_energy_max", view.itemEnergyMax());
            }
        }
        MachineState.Activity activity = view.working() ? MachineState.Activity.RUNNING : MachineState.Activity.IDLE;
        String note = "非正面五面可连网受电或装木质曲柄；空手右键弹出物品会掉落在正面地面";
        return new MachineState(activity, null, null, List.copyOf(shown), readings, note);
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
                if (request == null || request.item() == null || request.item().isBlank()) {
                    return Optional.empty();
                }
                return Optional.of(new ChargerFullRun(at, request, permissions, reads, services));
            }
        });
    }

    /** 木质曲柄（ae2:crank）的设备操作委托：读朝向、附着主机格与可视旋转角。 */
    static final class CrankDeviceOps implements Ae2DeviceOps {

        private final Ae2MachineReads reads;

        CrankDeviceOps(Ae2MachineReads reads) {
            this.reads = Objects.requireNonNull(reads, "reads");
        }

        @Override public Optional<MachineState> state(BlockPos pos, ClientLevel level) {
            if (level == null) return Optional.of(MachineState.unknown("角色不在世界里，读不到木质曲柄"));
            Optional<Ae2MachineReads.CrankView> found = reads.crankAt(level, pos);
            if (found.isEmpty()) return Optional.of(MachineState.unknown("这里读不到木质曲柄"));
            return Optional.of(buildCrankState(found.get()));
        }

        /** 纯函数：将木质曲柄客户端视图转为标准机器状态。 */
        static MachineState buildCrankState(Ae2MachineReads.CrankView view) {
            Map<String, Object> readings = new LinkedHashMap<>();
            readings.put("facing", view.facing().getSerializedName());
            readings.put("attached_to", view.attachedTo().toShortString());
            readings.put("visible_rotation", (double) view.visibleRotation());
            MachineState.Activity activity = Math.abs(view.visibleRotation()) > 0.01f
                    ? MachineState.Activity.RUNNING : MachineState.Activity.IDLE;
            return new MachineState(activity, null, null, List.of(), readings,
                    "满电时不可空摇，避免连续空摇销毁曲柄");
        }
    }
}
