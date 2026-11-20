// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.ItemSettings;
import org.maiwithu.maicraft.ability.machine.spi.MachineFeeding;
import org.maiwithu.maicraft.ability.machine.spi.MachineInstallations;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineSettings;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.advanced.Ae2Advanced;
import org.maiwithu.maicraft.compat.ae2.advanced.Ae2AdvancedReads;
import org.maiwithu.maicraft.compat.ae2.machine.Ae2MachineReads;
import org.maiwithu.maicraft.compat.ae2.machine.Ae2Machines;
import org.maiwithu.maicraft.compat.ae2.part.Ae2PartControl;
import org.maiwithu.maicraft.compat.ae2.part.Ae2Parts;
import org.maiwithu.maicraft.compat.ae2.tool.Ae2Tools;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 应用能源2四路子模块的统一汇总路由器：集中合并设备目录、语义界面布局规则、
 * 按进世界作用域隔离的设备委托映射（含线缆格双路部件委托联合路由）以及多把工具的物品设置集合。
 */
final class Ae2DeviceOpsRouter {

    private final Ae2Compat compat;
    private final Ae2MachineReads machineReads;
    private final Ae2PartControl partControl;
    private final Ae2AdvancedReads advancedReads;

    /** 按最近一次进世界的 PlayerServices 缓存整张设备委托表，同世界共享缓存、换世界自动重建。 */
    private PlayerServices cachedFor;
    private Map<String, Ae2DeviceOps> cachedOps;

    Ae2DeviceOpsRouter(Ae2Compat compat, Ae2MachineReads machineReads, Ae2PartControl partControl,
            Ae2AdvancedReads advancedReads) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.machineReads = Objects.requireNonNull(machineReads, "machineReads");
        this.partControl = Objects.requireNonNull(partControl, "partControl");
        this.advancedReads = Objects.requireNonNull(advancedReads, "advancedReads");
    }

    /** 合并全部基础与增补 AE2 设备种类（含将 ae2:io_port 显示名统一为「ME IO 传输站」）。 */
    static List<Ae2MachineCatalog.Kind> allKinds(Ae2Machines machines) {
        List<Ae2MachineCatalog.Kind> extra = new ArrayList<>();
        extra.addAll(machines.extraKinds());
        extra.addAll(Ae2Parts.extraKinds());
        extra.addAll(Ae2Advanced.extraKinds());
        return Ae2MachineCatalog.kinds(extra);
    }

    /** 合并四路子模块的语义界面布局规则，重复声明同一菜单 ID 时立即失败，避免静默覆盖。 */
    static Map<String, Ae2SemanticsLayout.Rule> allLayoutRules(Ae2Machines machines, Ae2Tools tools) {
        Map<String, Ae2SemanticsLayout.Rule> merged = new LinkedHashMap<>();
        putUnique(merged, machines.layoutRules(), "工作方块与自动合成");
        putUnique(merged, Ae2Parts.layoutRules(), "线缆部件与磁盘无线");
        putUnique(merged, tools.layoutRules(), "便携元件与手持工具");
        putUnique(merged, Ae2Advanced.layoutRules(), "量子环与空间存储");
        return Map.copyOf(merged);
    }

    private static <V> void putUnique(Map<String, V> target, Map<String, V> source, String group) {
        for (Map.Entry<String, V> entry : source.entrySet()) {
            if (target.put(entry.getKey(), entry.getValue()) != null) {
                throw new IllegalStateException("AE2 汇总接线发现重复键（" + group + "）：" + entry.getKey());
            }
        }
    }

    /**
     * 获取当前世界作用域的完整设备委托映射：工作方块与自动合成、线缆部件与磁盘无线、
     * 量子环与空间存储合为一张表；线缆格（ae2:cable_bus）联合路由两路部件委托。
     */
    synchronized Map<String, Ae2DeviceOps> deviceOpsFor(PlayerServices services) {
        Objects.requireNonNull(services, "services");
        if (cachedFor == services && cachedOps != null) {
            return cachedOps;
        }
        // 每次进入新世界重建委托与会话缓存，避免跨世界残留旧读数；进世界即注入实时服务端只读通道。
        Ae2Machines machines = new Ae2Machines(compat, machineReads);
        Ae2Parts parts = new Ae2Parts(compat, partControl, services::serverReads);
        Map<String, Ae2DeviceOps> merged = new LinkedHashMap<>();
        putUnique(merged, machines.deviceOps(), "工作方块与自动合成");
        putUnique(merged, parts.deviceOps(), "线缆部件与磁盘无线");
        putUnique(merged, Ae2Advanced.deviceOps(compat, advancedReads, services), "量子环与空间存储");
        merged.put(Ae2MachineCatalog.CABLE_BUS,
                composeCableOps(parts.cablePartOps(), machines.cablePartOps()));
        cachedFor = services;
        cachedOps = Map.copyOf(merged);
        return cachedOps;
    }

    /** 把线缆部件配置委托与自动合成线缆部件委托合并为一份 ae2:cable_bus 组合委托。 */
    static Ae2DeviceOps composeCableOps(Ae2DeviceOps primary, Ae2DeviceOps secondary) {
        return new CompositeCableOps(Objects.requireNonNull(primary, "primary"),
                Objects.requireNonNull(secondary, "secondary"));
    }

    /** 将多把 AE2 手持工具的物品设置聚合为单份委托，按物品认领关系逐项转发。 */
    static ItemSettings composeItemSettings(List<ItemSettings> delegates) {
        return new CompositeItemSettings(List.copyOf(Objects.requireNonNull(delegates, "delegates")));
    }

    /** 线缆格双路部件组合委托：合并读数、展示与按面设置，按 {@code <side>.<key>} 联合路由修改动作。 */
    private static final class CompositeCableOps implements Ae2DeviceOps {

        private final Ae2DeviceOps primary;
        private final Ae2DeviceOps secondary;

        CompositeCableOps(Ae2DeviceOps primary, Ae2DeviceOps secondary) {
            this.primary = primary;
            this.secondary = secondary;
        }

        @Override public Optional<List<ExchangePoint>> exchangePoints(BlockState state, BlockPos at,
                ClientLevel level) {
            Optional<List<ExchangePoint>> first = primary.exchangePoints(state, at, level);
            return first.isPresent() ? first : secondary.exchangePoints(state, at, level);
        }

        @Override public Optional<MachineState> state(BlockPos pos, ClientLevel level) {
            Optional<MachineState> first = primary.state(pos, level);
            return first.isPresent() ? first : secondary.state(pos, level);
        }

        @Override public Map<String, Object> extraReadings(BlockPos pos, ClientLevel level) {
            Map<String, Object> merged = new LinkedHashMap<>(primary.extraReadings(pos, level));
            merged.putAll(secondary.extraReadings(pos, level));
            return Map.copyOf(merged);
        }

        @Override public List<MachineState.Shown> extraShown(BlockPos pos, ClientLevel level) {
            List<MachineState.Shown> merged = new ArrayList<>(primary.extraShown(pos, level));
            merged.addAll(secondary.extraShown(pos, level));
            return List.copyOf(merged);
        }

        @Override public List<MachineState.Shown> output(BlockPos pos, ClientLevel level) {
            List<MachineState.Shown> merged = new ArrayList<>(primary.output(pos, level));
            merged.addAll(secondary.output(pos, level));
            return List.copyOf(merged);
        }

        @Override public Optional<MachineFeeding> feeding(BlockPos pos, PlayerServices services) {
            Optional<MachineFeeding> first = primary.feeding(pos, services);
            return first.isPresent() ? first : secondary.feeding(pos, services);
        }

        @Override public Optional<MachineInstallations> installations(PlayerServices services) {
            Optional<MachineInstallations> first = primary.installations(services);
            return first.isPresent() ? first : secondary.installations(services);
        }

        @Override public List<MachineSetting> partSettings(BlockPos host, Direction side, PlayerServices services) {
            List<MachineSetting> merged = new ArrayList<>(primary.partSettings(host, side, services));
            merged.addAll(secondary.partSettings(host, side, services));
            return List.copyOf(merged);
        }

        @Override public Optional<MachineSettings> settings(BlockPos pos, PlayerServices services) {
            Optional<MachineSettings> first = primary.settings(pos, services);
            Optional<MachineSettings> second = secondary.settings(pos, services);
            if (first.isEmpty()) return second;
            if (second.isEmpty()) return first;
            return Optional.of(new CompositeCableSettings(first.get(), second.get()));
        }
    }

    /** 线缆格组合设置实现：列出两路全部带面缀设置，改配置时由认领该面部件的一路接手。 */
    private static final class CompositeCableSettings implements MachineSettings {

        private final MachineSettings primary;
        private final MachineSettings secondary;

        CompositeCableSettings(MachineSettings primary, MachineSettings secondary) {
            this.primary = primary;
            this.secondary = secondary;
        }

        @Override public List<MachineSetting> settings(BlockPos at) {
            List<MachineSetting> merged = new ArrayList<>(primary.settings(at));
            merged.addAll(secondary.settings(at));
            return List.copyOf(merged);
        }

        @Override public List<MachineSetting> partSettings(BlockPos host, Direction side) {
            List<MachineSetting> merged = new ArrayList<>(primary.partSettings(host, side));
            merged.addAll(secondary.partSettings(host, side));
            return List.copyOf(merged);
        }

        @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
            // 两路负责的部件种类互斥：先问线缆部件一路，未认领再交自动合成部件一路。
            Optional<Action> first = primary.change(at, key, value, permissions);
            return first.isPresent() ? first : secondary.change(at, key, value, permissions);
        }
    }

    /** 手持工具组合设置实现：按 covers 归属转发查询与校验，按槽位物品转发改设置动作。 */
    private static final class CompositeItemSettings implements ItemSettings {

        private final List<ItemSettings> delegates;

        CompositeItemSettings(List<ItemSettings> delegates) {
            this.delegates = delegates;
        }

        @Override public boolean covers(ItemStack stack) {
            for (ItemSettings item : delegates) {
                if (item.covers(stack)) return true;
            }
            return false;
        }

        @Override public List<MachineSetting> settings(ItemStack stack) {
            for (ItemSettings item : delegates) {
                if (item.covers(stack)) return item.settings(stack);
            }
            return List.of();
        }

        @Override public boolean holds(ItemStack stack, String key, String wanted) {
            for (ItemSettings item : delegates) {
                if (item.covers(stack)) return item.holds(stack, key, wanted);
            }
            return false;
        }

        @Override public boolean holds(ItemStack stack, Player player, String key, String wanted) {
            for (ItemSettings item : delegates) {
                if (item.covers(stack)) return item.holds(stack, player, key, wanted);
            }
            return false;
        }

        @Override public Optional<Action> change(int inventorySlot, Map<String, String> wanted,
                Permissions permissions) {
            for (ItemSettings item : delegates) {
                Optional<Action> action = item.change(inventorySlot, wanted, permissions);
                if (action.isPresent()) return action;
            }
            return Optional.empty();
        }
    }
}
