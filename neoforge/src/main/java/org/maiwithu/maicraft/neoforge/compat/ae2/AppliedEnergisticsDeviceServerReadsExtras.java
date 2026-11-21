// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.ae2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.crafting.CraftingJobStatus;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.menu.me.networktool.MachineGroup;
import appeng.menu.me.networktool.NetworkStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.server.compat.ae2.Ae2NetworkServerExtras;

/**
 * AE2 服务端网络扩展明细的 NeoForge 读写端：零反射直接调用 AE2 公开接口
 * （{@link NetworkStatus#fromGrid}、{@link CraftingCPUCluster}、{@link PatternContainer}），
 * 为 ae2.network 补充电网收支与分组耗电、各台合成 CPU 在途/待产物料明细及全网样板容器分组清单。
 */
public final class AppliedEnergisticsDeviceServerReadsExtras implements Ae2NetworkServerExtras {

    private static final String CHEMICAL_KEY_TYPE_ID = "appmek:chemical";

    @Override public Optional<NetworkDetail> detail(ServerLevel level, BlockPos at) {
        IGrid grid = gridAt(level, at);
        if (grid == null) return Optional.empty();
        // 从 AE2 原生 NetworkStatus 读取平均输入、平均消耗、频道耗电与分组设备耗电。
        NetworkStatus status = NetworkStatus.fromGrid(grid);
        List<MachineEntry> machines = readMachines(status);
        List<CpuEntry> cpus = readCpus(grid);
        List<PatternGroupEntry> patterns = readPatternContainers(grid, level);
        ChemicalInventory chemicals = readStoredChemicals(grid);
        return Optional.of(new NetworkDetail(
                status.getAveragePowerInjection(),
                status.getAveragePowerUsage(),
                status.getChannelPower(),
                machines,
                cpus,
                patterns,
                chemicals.totalTypes(),
                chemicals.topListed()));
    }

    private record ChemicalInventory(int totalTypes, List<StockAmount> topListed) {}

    // 仅按 AEKeyType 注册 ID 筛选 appmek:chemical 库存并按数量降序截顶，不引用任何应用机械类。
    private static ChemicalInventory readStoredChemicals(IGrid grid) {
        List<StockAmount> found = new ArrayList<>();
        for (var entry : grid.getStorageService().getCachedInventory()) {
            AEKey key = entry.getKey();
            long amount = entry.getLongValue();
            if (key == null || amount <= 0L) continue;
            if (!CHEMICAL_KEY_TYPE_ID.equals(key.getType().getId().toString())) continue;
            found.add(new StockAmount(key.getId().toString(), amount));
        }
        int totalTypes = found.size();
        if (totalTypes == 0) return new ChemicalInventory(0, List.of());
        found.sort(Comparator.comparingLong(StockAmount::amount).reversed()
                .thenComparing(StockAmount::keyId));
        List<StockAmount> listed = found.size() > MAX_LISTED_CHEMICALS
                ? List.copyOf(found.subList(0, MAX_LISTED_CHEMICALS))
                : List.copyOf(found);
        return new ChemicalInventory(totalTypes, listed);
    }

    private static List<MachineEntry> readMachines(NetworkStatus status) {
        List<MachineGroup> sorted = new ArrayList<>(status.getGroupedMachines());
        sorted.sort(MachineGroup.COMPARATOR);
        List<MachineEntry> out = new ArrayList<>(sorted.size());
        for (MachineGroup group : sorted) {
            String itemId = group.getDisplay().getId().toString();
            String displayName = group.getDisplay().getDisplayName().getString();
            out.add(new MachineEntry(itemId, displayName, group.getCount(),
                    group.getIdlePowerUsage(), group.getPowerGenerationCapacity(), group.isMissingChannel()));
        }
        return out;
    }

    private static List<CpuEntry> readCpus(IGrid grid) {
        List<CpuEntry> out = new ArrayList<>();
        int serial = 0;
        for (ICraftingCPU cpu : grid.getCraftingService().getCpus()) {
            String name = cpu.getName() == null ? "" : cpu.getName().getString();
            String mode = cpu.getSelectionMode().name().toLowerCase(Locale.ROOT);
            CraftingJobStatus job = cpu.getJobStatus();
            String currentJob = job == null ? "" : formatGenericStack(job.crafting());
            long progress = job == null ? 0L : job.progress();
            long totalItems = job == null ? 0L : job.totalItems();
            boolean suspended = false;
            boolean cantStore = false;
            List<StockAmount> active = List.of();
            List<StockAmount> pending = List.of();
            if (cpu instanceof CraftingCPUCluster cluster) {
                CraftingCpuLogic logic = cluster.craftingLogic;
                suspended = logic.isJobSuspended();
                cantStore = logic.isCantStoreItems();
                List<StockAmount> waitingList = new ArrayList<>();
                List<StockAmount> pendingList = new ArrayList<>();
                collectCpuItems(logic, waitingList, pendingList);
                active = waitingList;
                pending = pendingList;
            }
            out.add(new CpuEntry(serial++, name, cpu.getAvailableStorage(), cpu.getCoProcessors(),
                    mode, cpu.isBusy(), suspended, cantStore, currentJob, progress, totalItems, active, pending));
        }
        return out;
    }

    // 逐项读取正在机器里加工等待回收的物料（waitingFor）与尚未推单的待产物料（pendingOutputs）。
    private static void collectCpuItems(CraftingCpuLogic logic, List<StockAmount> active, List<StockAmount> pending) {
        KeyCounter all = new KeyCounter();
        logic.getAllItems(all);
        for (var entry : all) {
            AEKey key = entry.getKey();
            if (key == null) continue;
            String keyId = key.getId().toString();
            long waiting = logic.getWaitingFor(key);
            if (waiting > 0) {
                active.add(new StockAmount(keyId, waiting));
            }
            long queued = logic.getPendingOutputs(key);
            if (queued > 0) {
                pending.add(new StockAmount(keyId, queued));
            }
        }
    }

    // 扫描全网在样板管理终端可见的样板容器，按 PatternContainerGroup 汇总总槽数、已用槽数与样板主产出。
    private static List<PatternGroupEntry> readPatternContainers(IGrid grid, ServerLevel level) {
        Map<PatternContainerGroup, GroupAccumulator> grouped = new LinkedHashMap<>();
        for (Class<?> machineClass : grid.getMachineClasses()) {
            if (!PatternContainer.class.isAssignableFrom(machineClass)) continue;
            for (IGridNode node : grid.getMachineNodes(machineClass)) {
                // 对齐 AE2 样板管理终端，仅统计在线且活跃的样板容器节点。
                if (!node.isActive()) continue;
                if (!(node.getOwner() instanceof PatternContainer container)) continue;
                if (!container.isVisibleInTerminal()) continue;
                PatternContainerGroup group = container.getTerminalGroup();
                if (group == null) group = PatternContainerGroup.nothing();
                GroupAccumulator acc = grouped.computeIfAbsent(group, GroupAccumulator::new);
                accumulateContainer(acc, container.getTerminalPatternInventory(), level);
            }
        }
        List<PatternGroupEntry> out = new ArrayList<>(grouped.size());
        for (GroupAccumulator acc : grouped.values()) {
            out.add(acc.finish());
        }
        return out;
    }

    private static void accumulateContainer(GroupAccumulator acc, InternalInventory inventory, ServerLevel level) {
        if (inventory == null) return;
        acc.totalSlots += inventory.size();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            acc.usedSlots++;
            IPatternDetails details = PatternDetailsHelper.decodePattern(stack, level);
            if (details != null && details.getPrimaryOutput() != null) {
                acc.outputs.add(details.getPrimaryOutput().what().getId().toString());
            }
        }
    }

    private static IGrid gridAt(ServerLevel level, BlockPos at) {
        IInWorldGridNodeHost host = GridHelper.getNodeHost(level, at);
        if (host == null) return null;
        IGridNode node = host.getGridNode(null);
        if (node == null) {
            for (Direction side : Direction.values()) {
                node = host.getGridNode(side);
                if (node != null) break;
            }
        }
        return node == null ? null : node.getGrid();
    }

    private static String formatGenericStack(GenericStack stack) {
        if (stack == null) return "";
        return stack.what().getId().toString() + " x" + stack.amount();
    }

    /** 按样板容器分组累计槽位数与主产出的临时累加器。 */
    private static final class GroupAccumulator {
        private final String name;
        private final String iconItemId;
        private int totalSlots;
        private int usedSlots;
        private final List<String> outputs = new ArrayList<>();

        private GroupAccumulator(PatternContainerGroup group) {
            this.name = group.name().getString();
            this.iconItemId = group.icon() == null ? "" : group.icon().getId().toString();
        }

        private PatternGroupEntry finish() {
            return new PatternGroupEntry(name, iconItemId, totalSlots, usedSlots, outputs);
        }
    }
}
