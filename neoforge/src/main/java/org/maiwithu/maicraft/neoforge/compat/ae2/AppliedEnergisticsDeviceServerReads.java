// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.ae2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import appeng.api.config.FuzzyMode;
import appeng.api.config.Settings;
import appeng.api.ids.AEComponents;
import appeng.api.implementations.items.ISpatialStorageCell;
import appeng.api.inventories.ISegmentedInventory;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.networking.spatial.ISpatialService;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.api.util.IConfigManager;
import appeng.api.util.IConfigurableObject;
import appeng.blockentity.crafting.MolecularAssemblerBlockEntity;
import appeng.blockentity.misc.CellWorkbenchBlockEntity;
import appeng.blockentity.misc.CondenserBlockEntity;
import appeng.blockentity.misc.InscriberBlockEntity;
import appeng.blockentity.misc.VibrationChamberBlockEntity;
import appeng.blockentity.networking.WirelessAccessPointBlockEntity;
import appeng.blockentity.qnb.QuantumBridgeBlockEntity;
import appeng.blockentity.spatial.SpatialAnchorBlockEntity;
import appeng.blockentity.spatial.SpatialIOPortBlockEntity;
import appeng.blockentity.spatial.SpatialPylonBlockEntity;
import appeng.blockentity.storage.IOPortBlockEntity;
import appeng.core.AEConfig;
import appeng.core.definitions.AEItems;
import appeng.helpers.IConfigInvHost;
import appeng.helpers.IPriorityHost;
import appeng.helpers.InterfaceLogicHost;
import appeng.helpers.externalstorage.GenericStackInv;
import appeng.items.storage.SpatialPlotInfo;
import appeng.me.cluster.implementations.QuantumCluster;
import appeng.me.cluster.implementations.SpatialPylonCluster;
import appeng.me.service.StatisticsService;
import appeng.parts.automation.AbstractLevelEmitterPart;
import appeng.parts.automation.AnnihilationPlanePart;
import appeng.parts.p2p.P2PTunnelPart;
import appeng.parts.storagebus.StorageBusPart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.maiwithu.maicraft.server.compat.ae2.Ae2DeviceServerReads;
import org.maiwithu.maicraft.server.compat.ae2.Ae2DeviceServerReads.DeviceSnapshot;

/**
 * AE2 服务端设备级权威只读操作（ae2.device）的 NeoForge 读写端：
 * 零反射直接调用 AE2 公开接口，覆盖线缆部件、无线访问点、振动室、物质聚合器、压印器、分子组装室、
 * 元件工作台、ME IO 传输站、量子环与量子链接仓、空间塔、空间传输站与空间锚。只翻译，不判断。
 */
public final class AppliedEnergisticsDeviceServerReads implements Ae2DeviceServerReads.Reader {

    @Override public Optional<DeviceSnapshot> read(ServerLevel level, BlockPos at, Direction side) {
        BlockEntity entity = level.getBlockEntity(at);
        if (entity == null) return Optional.empty();
        String blockId = BuiltInRegistries.BLOCK.getKey(entity.getBlockState().getBlock()).toString();
        // 客户端指定了 side 且目标格是线缆宿主时，精确读取那一面安装的部件；找不到部件时返回空。
        if (side != null && entity instanceof IPartHost host) {
            IPart part = host.getPart(side);
            return part == null ? Optional.empty() : Optional.of(readPart(blockId, side, part));
        }
        if (entity instanceof WirelessAccessPointBlockEntity accessPoint) {
            return Optional.of(readWirelessAccessPoint(blockId, accessPoint));
        }
        if (entity instanceof VibrationChamberBlockEntity chamber) {
            return Optional.of(readVibrationChamber(blockId, chamber));
        }
        if (entity instanceof CondenserBlockEntity condenser) {
            return Optional.of(readCondenser(blockId, condenser));
        }
        if (entity instanceof InscriberBlockEntity inscriber) {
            return Optional.of(readInscriber(blockId, inscriber));
        }
        if (entity instanceof MolecularAssemblerBlockEntity assembler) {
            return Optional.of(readMolecularAssembler(blockId, assembler));
        }
        if (entity instanceof CellWorkbenchBlockEntity workbench) {
            return Optional.of(readCellWorkbench(blockId, workbench));
        }
        if (entity instanceof IOPortBlockEntity transfer) {
            return Optional.of(readCellTransfer(blockId, transfer));
        }
        if (entity instanceof QuantumBridgeBlockEntity quantum) {
            return Optional.of(readQuantum(blockId, quantum));
        }
        if (entity instanceof SpatialPylonBlockEntity pylon) {
            return Optional.of(readSpatialPylon(blockId, pylon));
        }
        if (entity instanceof SpatialIOPortBlockEntity spatial) {
            return Optional.of(readSpatialTransfer(blockId, level, spatial));
        }
        if (entity instanceof SpatialAnchorBlockEntity anchor) {
            return Optional.of(readSpatialAnchor(blockId, anchor));
        }
        return readGenericAe2Machine(blockId, entity);
    }

    // 线缆单面部件：汇总配置、升级卡、过滤/备料槽、优先级、发信器阈值与亮灭、P2P 出入端与频率、破坏面板附魔。
    private static DeviceSnapshot readPart(String blockId, Direction side, IPart part) {
        String partId = BuiltInRegistries.ITEM.getKey(part.getPartItem().asItem()).toString();
        Map<String, String> settings = exportSettings(part);
        List<String> upgrades = readUpgrades(part);
        Map<String, Object> readings = new LinkedHashMap<>();
        if (part instanceof IPriorityHost priorityHost) {
            readings.put("priority", priorityHost.getPriority());
        }
        if (part instanceof IConfigInvHost configHost) {
            putGenericSlots(readings, "config_slots", "config_slot_types", configHost.getConfig());
        }
        if (part instanceof InterfaceLogicHost interfaceHost) {
            putGenericSlots(readings, "storage_slots", "storage_slot_types", interfaceHost.getStorage());
        }
        if (part instanceof AbstractLevelEmitterPart emitter) {
            readings.put("reporting_value", emitter.getReportingValue());
            readings.put("emitting", emitter.isProvidingStrongPower() > 0);
        }
        if (part instanceof P2PTunnelPart<?> tunnel) {
            readings.put("p2p_output", tunnel.isOutput());
            // P2P 频率为 16 位无符号短整型，按无符号整数输出，避免 >= 0x8000 符号扩展为负数。
            readings.put("p2p_frequency", Short.toUnsignedInt(tunnel.getFrequency()));
        }
        if (part instanceof StorageBusPart storageBus) {
            Component connectedTo = storageBus.getConnectedToDescription();
            if (connectedTo != null && !connectedTo.getString().isEmpty()) {
                readings.put("connected_to", connectedTo.getString());
            }
        }
        if (part instanceof AnnihilationPlanePart plane) {
            readings.put("enchantments", readEnchantments(plane.getEnchantments()));
        }
        return new DeviceSnapshot("cable_part", blockId, side.getSerializedName(), partId,
                settings, upgrades, readings);
    }

    private static DeviceSnapshot readWirelessAccessPoint(String blockId, WirelessAccessPointBlockEntity accessPoint) {
        ItemStack stack = accessPoint.getInternalInventory().getStackInSlot(0);
        int boosters = stack.isEmpty() ? 0 : stack.getCount();
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("boosters", boosters);
        readings.put("range", accessPoint.getRange());
        readings.put("power_drain", AEConfig.instance().wireless_getPowerDrain(boosters));
        readings.put("online", accessPoint.isActive());
        readings.put("powered", accessPoint.isPowered());
        return new DeviceSnapshot("wireless_access_point", blockId, "", "", Map.of(), List.of(), readings);
    }

    private static DeviceSnapshot readVibrationChamber(String blockId, VibrationChamberBlockEntity chamber) {
        ItemStack fuel = chamber.getInternalInventory().getStackInSlot(0);
        double remaining = chamber.getRemainingFuelTicks();
        double total = chamber.getFuelItemFuelTicks();
        int percent = total > 0 ? (int) Math.round(Math.max(0.0, Math.min(100.0, remaining * 100.0 / total))) : 0;
        double powerPerTick = remaining > 0 ? chamber.getCurrentFuelTicksPerTick() * chamber.getEnergyPerFuelTick() : 0.0;
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("active", remaining > 0);
        readings.put("fuel_item", fuel.isEmpty() ? "" : itemIdOf(fuel));
        readings.put("fuel_count", fuel.getCount());
        readings.put("remaining_fuel_ticks", remaining);
        readings.put("fuel_item_ticks", total);
        readings.put("remaining_burn_percent", percent);
        readings.put("power_per_tick", powerPerTick);
        readings.put("max_power_per_tick", chamber.getMaxEnergyRate());
        readings.put("energy_per_fuel_tick", chamber.getEnergyPerFuelTick());
        return new DeviceSnapshot("vibration_chamber", blockId, "", "", Map.of(), readUpgrades(chamber), readings);
    }

    private static DeviceSnapshot readCondenser(String blockId, CondenserBlockEntity condenser) {
        InternalInventory inv = condenser.getInternalInventory();
        ItemStack output = inv.size() > 1 ? inv.getStackInSlot(1) : ItemStack.EMPTY;
        ItemStack component = inv.size() > 2 ? inv.getStackInSlot(2) : ItemStack.EMPTY;
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("mode", condenser.getConfigManager().getSetting(Settings.CONDENSER_OUTPUT)
                .name().toLowerCase(Locale.ROOT));
        readings.put("stored_power", condenser.getStoredPower());
        readings.put("required_energy", condenser.getRequiredPower());
        readings.put("storage_capacity", condenser.getStorage());
        readings.put("storage_component", component.isEmpty() ? "" : itemIdOf(component));
        readings.put("output_item", output.isEmpty() ? "" : itemIdOf(output));
        readings.put("output_count", output.getCount());
        return new DeviceSnapshot("condenser", blockId, "", "", exportSettings(condenser), List.of(), readings);
    }

    private static DeviceSnapshot readInscriber(String blockId, InscriberBlockEntity inscriber) {
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("processing_time", inscriber.getProcessingTime());
        readings.put("max_processing_time", inscriber.getMaxProcessingTime());
        readings.put("internal_power", inscriber.getInternalCurrentPower());
        readings.put("internal_max_power", inscriber.getInternalMaxPower());
        return new DeviceSnapshot("inscriber", blockId, "", "",
                exportSettings(inscriber), readUpgrades(inscriber), readings);
    }

    private static DeviceSnapshot readMolecularAssembler(String blockId, MolecularAssemblerBlockEntity assembler) {
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("powered", assembler.isPowered());
        readings.put("active", assembler.isActive());
        readings.put("craft_progress", assembler.getCraftingProgress());
        readings.put("accepts_plans", assembler.acceptsPlans());
        readings.put("has_single_pattern", !assembler.acceptsPlans());
        return new DeviceSnapshot("molecular_assembler", blockId, "", "",
                Map.of(), readUpgrades(assembler), readings);
    }

    private static DeviceSnapshot readCellWorkbench(String blockId, CellWorkbenchBlockEntity workbench) {
        InternalInventory cellInv = workbench.getSubInventory(ISegmentedInventory.CELLS);
        ItemStack cell = cellInv != null && cellInv.size() > 0 ? cellInv.getStackInSlot(0) : ItemStack.EMPTY;
        IUpgradeInventory upgrades = workbench.getUpgrades();
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("cell_item", cell.isEmpty() ? "" : itemIdOf(cell));
        readings.put("copy_mode", workbench.getConfigManager().getSetting(Settings.COPY_MODE)
                .name().toLowerCase(Locale.ROOT));
        FuzzyMode fuzzy = cell.isEmpty() ? null : cell.get(AEComponents.STORAGE_CELL_FUZZY_MODE);
        readings.put("fuzzy_mode", fuzzy == null ? "" : fuzzy.name().toLowerCase(Locale.ROOT));
        readings.put("upgrade_slot_count", upgrades == null ? 0 : upgrades.size());
        putGenericSlots(readings, "config_slots", "config_slot_types", workbench.getConfig());
        return new DeviceSnapshot("cell_workbench", blockId, "", "",
                exportSettings(workbench), readUpgrades(workbench), readings);
    }

    private static DeviceSnapshot readCellTransfer(String blockId, IOPortBlockEntity transfer) {
        InternalInventory inv = transfer.getInternalInventory();
        List<String> inputCells = new ArrayList<>();
        List<String> outputCells = new ArrayList<>();
        for (int i = 0; i < Math.min(6, inv.size()); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.isEmpty()) inputCells.add(itemIdOf(stack));
        }
        for (int i = 6; i < Math.min(12, inv.size()); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.isEmpty()) outputCells.add(itemIdOf(stack));
        }
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("active", transfer.isActive());
        readings.put("has_redstone_card", transfer.getUpgrades().isInstalled(AEItems.REDSTONE_CARD));
        readings.put("input_cells", inputCells);
        readings.put("output_cells", outputCells);
        return new DeviceSnapshot("io_transfer", blockId, "", "",
                exportSettings(transfer), readUpgrades(transfer), readings);
    }

    // 量子环与量子链接仓：读成型/四角/供电/奇点与频率，并沿中心网格节点的非邻接连接检查对端跨维连通状态。
    private static DeviceSnapshot readQuantum(String blockId, QuantumBridgeBlockEntity quantum) {
        QuantumCluster cluster = quantum.getCluster();
        QuantumBridgeBlockEntity center = cluster != null && cluster.getCenter() != null
                ? cluster.getCenter() : quantum;
        long frequency = center.getQEFrequency();
        boolean peerConnected = false;
        boolean peerCrossDimension = false;
        String peerDimension = "";
        IGridNode centerNode = center.getGridNode();
        if (centerNode != null) {
            for (IGridConnection connection : centerNode.getConnections()) {
                if (connection.isInWorld()) continue;
                IGridNode other = connection.getOtherSide(centerNode);
                if (other != null && other.getOwner() instanceof QuantumBridgeBlockEntity remote
                        && remote.getLevel() != null && center.getLevel() != null) {
                    peerConnected = true;
                    peerCrossDimension = !remote.getLevel().dimension().equals(center.getLevel().dimension());
                    peerDimension = remote.getLevel().dimension().location().toString();
                    break;
                }
            }
        }
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("formed", quantum.isFormed());
        readings.put("corner", quantum.isCorner());
        readings.put("powered", quantum.isPowered());
        // 服务端 constructed 字节不含 hasSingularity 位，直接按中心链接仓槽 0 是否非空判定。
        readings.put("has_singularity", !center.getInternalInventory().getStackInSlot(0).isEmpty());
        readings.put("frequency", frequency);
        readings.put("peer_connected", peerConnected);
        readings.put("peer_cross_dimension", peerCrossDimension);
        if (!peerDimension.isEmpty()) {
            readings.put("peer_dimension", peerDimension);
        }
        String kind = blockId.equals("ae2:quantum_link") ? "quantum_link" : "quantum_ring";
        return new DeviceSnapshot(kind, blockId, "", "", Map.of(), List.of(), readings);
    }

    // 空间塔：返回单块塔态并结合同网空间服务补全合围内腔尺寸、塔块数、所需塔块数、效率与切换总能耗。
    private static DeviceSnapshot readSpatialPylon(String blockId, SpatialPylonBlockEntity pylon) {
        SpatialPylonBlockEntity.ClientState visual = pylon.getClientState();
        SpatialPylonCluster cluster = pylon.getCluster();
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("powered", visual.powered());
        readings.put("online", visual.online());
        readings.put("axis", visual.axis().getName());
        readings.put("axis_position", visual.axisPosition().name().toLowerCase(Locale.ROOT));
        readings.put("cluster_size", cluster == null ? 0 : cluster.size());
        IGridNode node = pylon.getGridNode();
        if (node != null && node.getGrid() != null) {
            fillSpatialRegion(readings, node.getGrid(), true);
        }
        return new DeviceSnapshot("spatial_pylon", blockId, "", "", Map.of(), List.of(), readings);
    }

    // 空间传输站：纯只读查区域尺寸、效率、电量、邻格红石信号（绝不调 getRedstoneState 避免触发切换）及槽 0 / 槽 1 空间元件绑定。
    private static DeviceSnapshot readSpatialTransfer(String blockId, ServerLevel level,
                                                      SpatialIOPortBlockEntity spatial) {
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("active", spatial.isActive());
        readings.put("redstone_powered", level.getBestNeighborSignal(spatial.getBlockPos()) != 0);
        InternalInventory inv = spatial.getInternalInventory();
        fillSpatialCellSlot(readings, "input", inv.size() > 0 ? inv.getStackInSlot(0) : ItemStack.EMPTY);
        fillSpatialCellSlot(readings, "output", inv.size() > 1 ? inv.getStackInSlot(1) : ItemStack.EMPTY);
        IGridNode node = spatial.getGridNode();
        if (node != null && node.getGrid() != null) {
            IGrid grid = node.getGrid();
            fillSpatialRegion(readings, grid, false);
            readings.put("current_power", grid.getEnergyService().getStoredPower());
            readings.put("max_power", grid.getEnergyService().getMaxStoredPower());
        }
        return new DeviceSnapshot("spatial_transfer", blockId, "", "", Map.of(), List.of(), readings);
    }

    // 空间锚：读加载区块数、实时能耗与覆盖层模式，并按 StatisticsService 统计全网多维加载区块数。
    private static DeviceSnapshot readSpatialAnchor(String blockId, SpatialAnchorBlockEntity anchor) {
        int loaded = anchor.countLoadedChunks();
        long power = 80L + (long) loaded * (loaded + 1) / 2;
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("active", anchor.isActive());
        readings.put("overlay_mode", anchor.getConfigManager().getSetting(Settings.OVERLAY_MODE)
                .name().toLowerCase(Locale.ROOT));
        readings.put("loaded_chunks", loaded);
        readings.put("power_consumption", power);
        IGridNode node = anchor.getGridNode();
        if (node != null && node.getGrid() != null) {
            fillAnchorStatistics(readings, node.getGrid());
        }
        return new DeviceSnapshot("spatial_anchor", blockId, "", "",
                exportSettings(anchor), List.of(), readings);
    }

    private static void fillAnchorStatistics(Map<String, Object> readings, IGrid grid) {
        Map<LevelAccessor, Integer> loadedByLevel = new HashMap<>();
        for (SpatialAnchorBlockEntity other : grid.getMachines(SpatialAnchorBlockEntity.class)) {
            if (other.getLevel() != null) {
                loadedByLevel.merge(other.getLevel(), other.countLoadedChunks(), Math::max);
            }
        }
        int allLoadedChunks = loadedByLevel.values().stream().mapToInt(Integer::intValue).sum();
        int allLoadedWorlds = loadedByLevel.size();
        StatisticsService statistics = grid.getService(StatisticsService.class);
        int allWorlds = statistics.getChunks().size();
        int allChunks = 0;
        for (var perLevel : statistics.getChunks().values()) {
            allChunks += perLevel.elementSet().size();
        }
        readings.put("all_loaded_worlds", allLoadedWorlds);
        readings.put("all_loaded_chunks", allLoadedChunks);
        readings.put("all_worlds", allWorlds);
        readings.put("all_chunks", allChunks);
    }

    private static void fillSpatialRegion(Map<String, Object> readings, IGrid grid, boolean includePylonCounts) {
        ISpatialService spatial = grid.getSpatialService();
        boolean hasRegion = spatial.hasRegion();
        boolean validRegion = spatial.isValidRegion();
        readings.put("has_region", hasRegion);
        readings.put("valid_region", validRegion);
        readings.put("efficiency", (double) spatial.currentEfficiency());
        readings.put("required_power", spatial.requiredPower());
        BlockPos min = spatial.getMin();
        BlockPos max = spatial.getMax();
        if (min != null && max != null && validRegion) {
            int spanX = max.getX() - min.getX();
            int spanY = max.getY() - min.getY();
            int spanZ = max.getZ() - min.getZ();
            readings.put("x_size", spanX - 1);
            readings.put("y_size", spanY - 1);
            readings.put("z_size", spanZ - 1);
            if (includePylonCounts) {
                int requiredBlocks = Math.max(6, (spanX * spanZ + spanX * spanY + spanY * spanZ) * 3 / 8);
                readings.put("pylon_blocks", countOnlinePylonBlocks(grid, spatial.getLevel()));
                readings.put("required_pylon_blocks", requiredBlocks);
            }
        } else {
            readings.put("x_size", 0);
            readings.put("y_size", 0);
            readings.put("z_size", 0);
            if (includePylonCounts) {
                readings.put("pylon_blocks", countOnlinePylonBlocks(grid, spatial.getLevel()));
                readings.put("required_pylon_blocks", 0);
            }
        }
    }

    private static int countOnlinePylonBlocks(IGrid grid, Level captureLevel) {
        Map<SpatialPylonCluster, Boolean> seen = new HashMap<>();
        int total = 0;
        for (IGridNode node : grid.getMachineNodes(SpatialPylonBlockEntity.class)) {
            if (!node.meetsChannelRequirements()) continue;
            if (!(node.getOwner() instanceof SpatialPylonBlockEntity pylon)) continue;
            SpatialPylonCluster cluster = pylon.getCluster();
            if (cluster == null || seen.put(cluster, Boolean.TRUE) != null) continue;
            if (captureLevel == null || cluster.setLevel() == captureLevel) {
                total += cluster.size();
            }
        }
        return total;
    }

    private static void fillSpatialCellSlot(Map<String, Object> readings, String prefix, ItemStack stack) {
        if (stack.isEmpty()) {
            readings.put(prefix + "_cell", "");
            return;
        }
        readings.put(prefix + "_cell", itemIdOf(stack));
        if (stack.getItem() instanceof ISpatialStorageCell cell && cell.isSpatialStorage(stack)) {
            readings.put(prefix + "_cell_max_dim", cell.getMaxStoredDim(stack));
            readings.put(prefix + "_plot_id", cell.getAllocatedPlotId(stack));
            SpatialPlotInfo binding = stack.get(AEComponents.SPATIAL_PLOT_INFO);
            if (binding != null && binding.size() != null) {
                BlockPos size = binding.size();
                readings.put(prefix + "_plot_size", size.getX() + "x" + size.getY() + "x" + size.getZ());
            }
        }
    }

    private static Optional<DeviceSnapshot> readGenericAe2Machine(String blockId, BlockEntity entity) {
        if (!blockId.startsWith("ae2:")) return Optional.empty();
        Map<String, String> settings = exportSettings(entity);
        List<String> upgrades = readUpgrades(entity);
        Map<String, Object> readings = new LinkedHashMap<>();
        if (entity instanceof IPriorityHost priorityHost) {
            readings.put("priority", priorityHost.getPriority());
        }
        if (entity instanceof IConfigInvHost configHost) {
            putGenericSlots(readings, "config_slots", "config_slot_types", configHost.getConfig());
        }
        if (entity instanceof InterfaceLogicHost interfaceHost) {
            putGenericSlots(readings, "storage_slots", "storage_slot_types", interfaceHost.getStorage());
        }
        if (settings.isEmpty() && upgrades.isEmpty() && readings.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new DeviceSnapshot("generic_machine", blockId, "", "", settings, upgrades, readings));
    }

    private static Map<String, String> exportSettings(Object target) {
        if (!(target instanceof IConfigurableObject configurable)) return Map.of();
        IConfigManager config = configurable.getConfigManager();
        if (config == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        config.exportSettings().forEach((k, v) -> out.put(k.toLowerCase(Locale.ROOT), v.toLowerCase(Locale.ROOT)));
        return out;
    }

    // 按升级槽完整下标输出（空槽写空串），确保客户端按 upgrade.0..N 对齐，不因中间空槽错位。
    private static List<String> readUpgrades(Object target) {
        if (!(target instanceof IUpgradeableObject upgradeable)) return List.of();
        IUpgradeInventory inv = upgradeable.getUpgrades();
        if (inv == null) return List.of();
        List<String> out = new ArrayList<>(inv.size());
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            out.add(stack.isEmpty() ? "" : itemIdOf(stack));
        }
        return out;
    }

    // 保留原有 config_slots/storage_slots 文本格式向后兼容；当存在流体、化学品等非物品槽位时补充介质类型映射表。
    private static void putGenericSlots(Map<String, Object> readings, String slotsKey, String typesKey,
                                        GenericStackInv inv) {
        Map<String, String> slots = new LinkedHashMap<>();
        Map<String, String> types = new LinkedHashMap<>();
        boolean hasNonItem = false;
        if (inv != null) {
            for (int i = 0; i < inv.size(); i++) {
                GenericStack stack = inv.getStack(i);
                if (stack == null) continue;
                String indexKey = Integer.toString(i);
                slots.put(indexKey, stack.what().getId().toString() + " x" + stack.amount());
                String category = keyTypeCategory(stack.what());
                types.put(indexKey, category);
                if (!"item".equals(category)) hasNonItem = true;
            }
        }
        readings.put(slotsKey, slots);
        if (hasNonItem) {
            readings.put(typesKey, types);
        }
    }

    private static String keyTypeCategory(AEKey key) {
        if (key.getType() == AEKeyType.items()) return "item";
        if (key.getType() == AEKeyType.fluids()) return "fluid";
        String typeId = key.getType().getId().toString();
        return "appmek:chemical".equals(typeId) ? "chemical" : typeId;
    }

    private static Map<String, Integer> readEnchantments(ItemEnchantments enchantments) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (enchantments == null) return out;
        for (var entry : enchantments.entrySet()) {
            out.put(entry.getKey().getRegisteredName(), entry.getIntValue());
        }
        return out;
    }

    private static String itemIdOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
