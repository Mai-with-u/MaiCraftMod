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
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.compat.ae2.Ae2DeviceOps;

/**
 * 陨石储罐（ae2:sky_stone_tank）与水晶催生器（ae2:growth_accelerator）的设备操作委托：
 * 陨石储罐客户端直读 16,000 mB 流体储罐；水晶催生器读 powered 并仅向前后两面暴露连网/受电/曲柄口。
 */
final class SkyStoneTankOps implements Ae2DeviceOps {

    /** 陨石储罐标准容量（16 桶 = 16,000 mB）。F 游戏事实。 */
    static final int DEFAULT_CAPACITY_MB = 16_000;

    private final Ae2MachineReads reads;

    SkyStoneTankOps(Ae2MachineReads reads) {
        this.reads = Objects.requireNonNull(reads, "reads");
    }

    @Override public Optional<MachineState> state(BlockPos pos, ClientLevel level) {
        if (level == null) return Optional.of(MachineState.unknown("角色不在世界里，读不到陨石储罐"));
        Optional<Ae2MachineReads.TankView> found = reads.skyStoneTankAt(level, pos);
        if (found.isEmpty()) return Optional.of(MachineState.unknown("这里读不到陨石储罐"));
        return Optional.of(buildTankState(found.get()));
    }

    /** 纯函数：将陨石储罐 FluidTank 客户端视图转为标准机器状态。 */
    static MachineState buildTankState(Ae2MachineReads.TankView view) {
        Map<String, Object> readings = new LinkedHashMap<>();
        int capacity = view.capacity() > 0 ? view.capacity() : DEFAULT_CAPACITY_MB;
        String fluid = view.amount() > 0 && view.fluidId() != null && !view.fluidId().isEmpty()
                ? view.fluidId() : "empty";
        readings.put("fluid", fluid);
        readings.put("fluid_amount", (long) Math.max(0, view.amount()));
        readings.put("fluid_capacity", (long) capacity);
        Double progress = capacity > 0 ? Math.min(1.0, Math.max(0.0, (double) view.amount() / capacity)) : 0.0;
        MachineState.Activity activity = view.amount() > 0
                ? MachineState.Activity.RUNNING : MachineState.Activity.IDLE;
        return new MachineState(activity, null, progress, List.of(), readings,
                "可用流体桶或流体容器右键双向存取（容量 " + capacity + " mB）");
    }

    /** 水晶催生器（ae2:growth_accelerator）委托：客户端读 powered，且仅 FRONT/BACK 两面连网与受电。 */
    static final class GrowthAcceleratorOps implements Ae2DeviceOps {

        private final Ae2MachineReads reads;

        GrowthAcceleratorOps(Ae2MachineReads reads) {
            this.reads = Objects.requireNonNull(reads, "reads");
        }

        @Override public Optional<List<ExchangePoint>> exchangePoints(BlockState state, BlockPos at, ClientLevel level) {
            Optional<Ae2MachineReads.GrowthView> found = level == null ? Optional.empty()
                    : reads.growthAcceleratorAt(level, at);
            if (found.isEmpty()) return Optional.empty();
            return Optional.of(exchangePointsFor(at, found.get()));
        }

        /** 纯函数：水晶催生器仅在 FRONT 与 BACK 两面暴露 ME 连网/受电/装曲柄口。 */
        static List<ExchangePoint> exchangePointsFor(BlockPos at, Ae2MachineReads.GrowthView view) {
            List<ExchangePoint> points = new ArrayList<>(2);
            if (view.front() != null) {
                points.add(new ExchangePoint(at, view.front(), null, ChargerDeviceOps.ME_NETWORK,
                        ExchangePoint.Flow.BOTH, "水晶催生器正面：可连 ME 线缆、受电或装木质曲柄"));
            }
            if (view.back() != null && view.back() != view.front()) {
                points.add(new ExchangePoint(at, view.back(), null, ChargerDeviceOps.ME_NETWORK,
                        ExchangePoint.Flow.BOTH, "水晶催生器背面：可连 ME 线缆、受电或装木质曲柄"));
            }
            return List.copyOf(points);
        }

        @Override public Optional<MachineState> state(BlockPos pos, ClientLevel level) {
            if (level == null) return Optional.of(MachineState.unknown("角色不在世界里，读不到水晶催生器"));
            Optional<Ae2MachineReads.GrowthView> found = reads.growthAcceleratorAt(level, pos);
            if (found.isEmpty()) return Optional.empty();
            return Optional.of(buildGrowthState(found.get()));
        }

        static MachineState buildGrowthState(Ae2MachineReads.GrowthView view) {
            Map<String, Object> readings = new LinkedHashMap<>();
            readings.put("powered", view.powered());
            if (view.front() != null) readings.put("front", view.front().getSerializedName());
            if (view.back() != null) readings.put("back", view.back().getSerializedName());
            MachineState.Activity activity = view.powered()
                    ? MachineState.Activity.RUNNING : MachineState.Activity.OFFLINE;
            return new MachineState(activity, null, null, List.of(), readings,
                    "仅前后两面可连线缆/受电/装曲柄，六个相邻面均可催生 #ae2:growth_acceleratable 方块");
        }
    }
}
