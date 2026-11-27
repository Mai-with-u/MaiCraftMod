// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.extendedae;

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
import org.maiwithu.maicraft.ability.machine.spi.RunRequest;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 扩展充能器（{@code extendedae:ex_charger}）设备委托：
 * 无界面直点交互，除相对正面外的五面可连 ME 网、受电或装木质曲柄；
 * 客户端直接同步 {@code working} 与 4 槽物品及带电工具电量，运行交由 {@link ExChargerFullRun} 并发处理。
 */
final class ExChargerOps implements ExtendedAeDeviceOps {

    private final ExtendedAeCompat compat;

    ExChargerOps(ExtendedAeCompat compat) {
        this.compat = Objects.requireNonNull(compat, "compat");
    }

    // 扩展充能器除正面外的五面可连 ME 网、受电与安装木质曲柄。
    @Override public Optional<List<ExchangePoint>> exchangePoints(BlockState state, BlockPos at, ClientLevel level) {
        Direction front = level == null ? Direction.NORTH : compat.exChargerAt(level, at)
                .map(ExtendedAeReads.ExChargerView::front).orElse(Direction.NORTH);
        List<ExchangePoint> points = new ArrayList<>(5);
        for (Direction side : Direction.values()) {
            if (side == front) continue;
            points.add(new ExchangePoint(at, side, null, ME_NETWORK, ExchangePoint.Flow.BOTH,
                    "扩展充能器非正面：可连 ME 网、受电或安装木质曲柄"));
        }
        return Optional.of(List.copyOf(points));
    }

    @Override public Optional<MachineState> state(BlockPos at, ClientLevel level) {
        if (level == null) return Optional.of(MachineState.unknown("角色不在世界里，读不到扩展充能器"));
        Optional<ExtendedAeReads.ExChargerView> found = compat.exChargerAt(level, at);
        if (found.isEmpty()) return Optional.empty();
        return Optional.of(buildState(found.get()));
    }

    /** 纯函数：将扩展充能器 4 槽客户端同步视图转为标准机器状态。 */
    static MachineState buildState(ExtendedAeReads.ExChargerView view) {
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("working", view.working());
        readings.put("powered", view.powered());
        if (view.front() != null) readings.put("front", view.front().getSerializedName());
        List<MachineState.Shown> shown = new ArrayList<>();
        for (int i = 0; i < ExChargerFullRun.MAX_SLOTS; i++) {
            ExtendedAeReads.ChargerSlotView slot = i < view.slots().size() ? view.slots().get(i) : null;
            if (slot == null || slot.empty()) {
                readings.put("slot_" + i, "empty");
            } else {
                readings.put("slot_" + i, slot.itemId());
                shown.add(new MachineState.Shown(slot.itemId(), slot.count()));
                if (slot.chargeable()) {
                    readings.put("slot_" + i + "_energy", slot.energy());
                    readings.put("slot_" + i + "_energy_max", slot.maxEnergy());
                }
            }
        }
        MachineState.Activity activity = !view.powered() ? MachineState.Activity.OFFLINE
                : view.working() ? MachineState.Activity.RUNNING : MachineState.Activity.IDLE;
        return new MachineState(activity, null, null, List.copyOf(shown), readings,
                "无界面机器，最多 4 槽并发充能；主手持可充能物品右键放入，空手右键依次弹出");
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
                if (request == null || request.item() == null || request.item().isBlank()) return Optional.empty();
                return Optional.of(new ExChargerFullRun(at, request, permissions, compat, services));
            }
        });
    }
}
