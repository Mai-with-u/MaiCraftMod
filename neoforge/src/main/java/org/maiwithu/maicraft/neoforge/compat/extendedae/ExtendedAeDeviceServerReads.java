// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.extendedae;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import appeng.api.inventories.InternalInventory;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.GenericStack;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.api.util.AEColor;
import appeng.api.util.IConfigManager;
import appeng.api.util.IConfigurableObject;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import appeng.helpers.externalstorage.GenericStackInv;
import com.glodblock.github.extendedae.api.CanerMode;
import com.glodblock.github.extendedae.common.me.matrix.ClusterAssemblerMatrix;
import com.glodblock.github.extendedae.common.tileentities.TileCaner;
import com.glodblock.github.extendedae.common.tileentities.TileCircuitCutter;
import com.glodblock.github.extendedae.common.tileentities.TileCrystalAssembler;
import com.glodblock.github.extendedae.common.tileentities.TileCrystalFixer;
import com.glodblock.github.extendedae.common.tileentities.TileExIOPort;
import com.glodblock.github.extendedae.common.tileentities.TileExInscriber;
import com.glodblock.github.extendedae.common.tileentities.TileExInterface;
import com.glodblock.github.extendedae.common.tileentities.TileExMolecularAssembler;
import com.glodblock.github.extendedae.common.tileentities.TileExPatternProvider;
import com.glodblock.github.extendedae.common.tileentities.TileIngredientBuffer;
import com.glodblock.github.extendedae.common.tileentities.TileWirelessConnector;
import com.glodblock.github.extendedae.common.tileentities.TileWirelessHub;
import com.glodblock.github.extendedae.common.tileentities.matrix.TileAssemblerMatrixBase;
import com.glodblock.github.extendedae.common.tileentities.matrix.TileAssemblerMatrixCrafter;
import com.glodblock.github.extendedae.common.tileentities.matrix.TileAssemblerMatrixPattern;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.maiwithu.maicraft.server.compat.extendedae.ExtendedAeDeviceQuery;
import org.maiwithu.maicraft.server.compat.extendedae.ExtendedAeDeviceQuery.DeviceSnapshot;

/**
 * ExtendedAE 服务端权威设备读数（{@code extendedae.device}）的 NeoForge 零反射实现：
 * 按目标格实际方块实体类型读取储罐、进度、输出面、4 页压印进度、36 槽样板与接口明细、
 * 8 线程组装进度、无线连接对端与能耗以及组装矩阵多方块统计。只读，不修改世界状态。
 */
public final class ExtendedAeDeviceServerReads implements ExtendedAeDeviceQuery.Reader {

    @Override public Optional<DeviceSnapshot> read(ServerLevel level, BlockPos at) {
        BlockEntity entity = level.getBlockEntity(at);
        if (entity == null) return Optional.empty();
        String blockId = BuiltInRegistries.BLOCK.getKey(entity.getBlockState().getBlock()).toString();
        if (entity instanceof TileCrystalAssembler assembler) return Optional.of(readCrystalAssembler(blockId, assembler));
        if (entity instanceof TileCircuitCutter cutter) return Optional.of(readCircuitCutter(blockId, cutter));
        if (entity instanceof TileExInscriber inscriber) return Optional.of(readExInscriber(blockId, inscriber));
        if (entity instanceof TileCrystalFixer fixer) return Optional.of(readCrystalFixer(blockId, fixer));
        if (entity instanceof TileExPatternProvider provider) return Optional.of(readExPatternProvider(blockId, provider));
        if (entity instanceof TileExInterface iface) return Optional.of(readExInterface(blockId, iface));
        if (entity instanceof TileExMolecularAssembler ma) return Optional.of(readExMolecularAssembler(blockId, ma));
        if (entity instanceof TileExIOPort transfer) return Optional.of(readExCellTransfer(blockId, transfer));
        if (entity instanceof TileIngredientBuffer buffer) return Optional.of(readIngredientBuffer(blockId, buffer));
        if (entity instanceof TileCaner caner) return Optional.of(readCaner(blockId, caner));
        if (entity instanceof TileWirelessConnector wireless) return Optional.of(readWirelessConnector(blockId, wireless));
        if (entity instanceof TileWirelessHub hub) return Optional.of(readWirelessHub(blockId, hub));
        if (entity instanceof TileAssemblerMatrixBase matrix) return Optional.of(readAssemblerMatrix(blockId, matrix));
        return Optional.empty();
    }

    private static DeviceSnapshot readCrystalAssembler(String blockId, TileCrystalAssembler tile) {
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("working", tile.isWorking());
        readings.put("processing_time", tile.getProgress());
        readings.put("max_processing_time", TileCrystalAssembler.MAX_PROGRESS);
        readings.put("tank_capacity_mb", (long) TileCrystalAssembler.TANK_CAP);
        GenericStack tankStack = tile.getTank().getStack(0);
        if (tankStack != null && tankStack.what() instanceof AEFluidKey fluidKey) {
            readings.put("tank_fluid", fluidKey.getId().toString());
            readings.put("tank_amount_mb", tankStack.amount());
        } else {
            readings.put("tank_fluid", "empty");
            readings.put("tank_amount_mb", 0L);
        }
        readings.put("output_sides", directionNames(tile.getOutputSides()));
        readings.put("inputs", inventoryEntries(tile.getInput()));
        ItemStack out = tile.getOutput().getStackInSlot(0);
        readings.put("output", out.isEmpty() ? "empty" : itemIdOf(out) + " ×" + out.getCount());
        return new DeviceSnapshot("crystal_assembler", blockId, exportSettings(tile), readUpgrades(tile), readings);
    }

    private static DeviceSnapshot readCircuitCutter(String blockId, TileCircuitCutter tile) {
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("working", tile.isWorking());
        readings.put("processing_time", tile.getProgress());
        readings.put("max_processing_time", TileCircuitCutter.MAX_PROGRESS);
        readings.put("output_sides", directionNames(tile.getOutputSides()));
        ItemStack in = tile.getInput().getStackInSlot(0);
        ItemStack out = tile.getOutput().getStackInSlot(0);
        readings.put("input", in.isEmpty() ? "empty" : itemIdOf(in) + " ×" + in.getCount());
        readings.put("output", out.isEmpty() ? "empty" : itemIdOf(out) + " ×" + out.getCount());
        return new DeviceSnapshot("circuit_cutter", blockId, exportSettings(tile), readUpgrades(tile), readings);
    }

    private static DeviceSnapshot readExInscriber(String blockId, TileExInscriber tile) {
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("input_capacity", Integer.toString(tile.getInvStackSize()));
        readings.put("max_processing_time", tile.getMaxProcessingTime());
        readings.put("separate_sides", tile.isSeparateSides() ? "yes" : "no");
        List<Integer> progressList = new ArrayList<>(4);
        for (int p = 0; p < 4; p++) progressList.add(tile.getProcessingTime(p));
        readings.put("page_progress", progressList);
        return new DeviceSnapshot("ex_inscriber", blockId, exportSettings(tile), readUpgrades(tile), readings);
    }

    private static DeviceSnapshot readCrystalFixer(String blockId, TileCrystalFixer tile) {
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("progress", tile.getProgress());
        readings.put("max_progress", TileCrystalFixer.MAX_PROGRESS);
        ItemStack fuel = tile.getInternalInventory().getStackInSlot(0);
        readings.put("fuel", fuel.isEmpty() ? "empty" : itemIdOf(fuel));
        readings.put("fuel_count", fuel.getCount());
        return new DeviceSnapshot("crystal_fixer", blockId, Map.of(), List.of(), readings);
    }

    private static DeviceSnapshot readExPatternProvider(String blockId, TileExPatternProvider tile) {
        InternalInventory inv = tile.getLogic().getPatternInv();
        List<String> patterns = new ArrayList<>();
        int occupied = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.isEmpty()) {
                occupied++;
                patterns.add(i + ":" + itemIdOf(stack));
            }
        }
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("priority", tile.getLogic().getPriority());
        readings.put("pattern_slots_total", inv.size());
        readings.put("pattern_slots_used", occupied);
        readings.put("patterns", patterns);
        return new DeviceSnapshot("ex_pattern_provider", blockId,
                exportSettings(tile.getLogic()), List.of(), readings);
    }

    private static DeviceSnapshot readExInterface(String blockId, TileExInterface tile) {
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("priority", tile.getPriority());
        readings.put("config_slots", genericInvMap(tile.getConfig()));
        readings.put("storage_slots", genericInvMap(tile.getStorage()));
        return new DeviceSnapshot("ex_interface", blockId, exportSettings(tile), readUpgrades(tile), readings);
    }

    private static DeviceSnapshot readExMolecularAssembler(String blockId, TileExMolecularAssembler tile) {
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("powered", tile.isPowered());
        readings.put("active", tile.isActive());
        List<Map<String, Object>> threads = new ArrayList<>(TileExMolecularAssembler.MAX_THREAD);
        for (int i = 0; i < TileExMolecularAssembler.MAX_THREAD; i++) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("thread", i);
            entry.put("progress", tile.getCraftingProgress(i));
            IMolecularAssemblerSupportedPattern pat = tile.getCurrentPattern(i);
            entry.put("pattern_output", pat != null && pat.getPrimaryOutput() != null
                    ? pat.getPrimaryOutput().what().getId().toString() : "");
            threads.add(entry);
        }
        readings.put("thread_states", threads);
        return new DeviceSnapshot("ex_molecular_assembler", blockId, Map.of(), readUpgrades(tile), readings);
    }

    private static DeviceSnapshot readExCellTransfer(String blockId, TileExIOPort tile) {
        InternalInventory inv = tile.getInternalInventory();
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
        readings.put("active", tile.isActive());
        readings.put("input_cells", inputCells);
        readings.put("output_cells", outputCells);
        return new DeviceSnapshot("ex_io_transfer", blockId, exportSettings(tile), readUpgrades(tile), readings);
    }

    private static DeviceSnapshot readIngredientBuffer(String blockId, TileIngredientBuffer tile) {
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("contents", genericInvMap(tile.getGenericInv()));
        return new DeviceSnapshot("ingredient_buffer", blockId, Map.of(), List.of(), readings);
    }

    private static DeviceSnapshot readCaner(String blockId, TileCaner tile) {
        Map<String, Object> readings = new LinkedHashMap<>();
        CanerMode mode = tile.getMode();
        readings.put("mode", mode == null ? "fill" : mode.name().toLowerCase(Locale.ROOT));
        readings.put("done", tile.isDone());
        ItemStack container = tile.getContainer().getStackInSlot(0);
        readings.put("container_item", container.isEmpty() ? "empty" : itemIdOf(container) + " ×" + container.getCount());
        readings.put("generic_slot", genericInvMap(tile.getGenericInv()));
        return new DeviceSnapshot("caner", blockId, Map.of(), List.of(), readings);
    }

    private static DeviceSnapshot readWirelessConnector(String blockId, TileWirelessConnector tile) {
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("connected", tile.isConnected());
        readings.put("frequency", tile.getFrequency());
        BlockPos other = tile.getOtherSide();
        readings.put("other_side", other == null ? "" : other.toShortString());
        readings.put("power_use", tile.getPowerUse());
        readings.put("color", colorName(tile.getColor()));
        return new DeviceSnapshot("wireless_connect", blockId, Map.of(), readUpgrades(tile), readings);
    }

    private static DeviceSnapshot readWirelessHub(String blockId, TileWirelessHub tile) {
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("connected", tile.isConnected());
        readings.put("power_use", tile.getPowerUse());
        readings.put("color", colorName(tile.getColor()));
        List<Map<String, Object>> channels = new ArrayList<>(TileWirelessHub.MAX_PORT);
        for (int i = 0; i < TileWirelessHub.MAX_PORT; i++) {
            Map<String, Object> entry = new LinkedHashMap<>();
            long freq = tile.getFrequency(i);
            BlockPos other = tile.getOtherSide(i);
            entry.put("index", i);
            entry.put("frequency", freq);
            entry.put("other_side", other == null ? "" : other.toShortString());
            channels.add(entry);
        }
        readings.put("ports", channels);
        return new DeviceSnapshot("wireless_hub", blockId, Map.of(), readUpgrades(tile), readings);
    }

    private static DeviceSnapshot readAssemblerMatrix(String blockId, TileAssemblerMatrixBase tile) {
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("formed", tile.isFormed());
        readings.put("powered", tile.isPowered());
        readings.put("active", tile.isActive());
        ClusterAssemblerMatrix cluster = tile.getCluster();
        if (cluster != null && !cluster.isDestroyed()) {
            BlockPos min = cluster.getBoundsMin();
            BlockPos max = cluster.getBoundsMax();
            if (min != null) readings.put("bounds_min", min.toShortString());
            if (max != null) readings.put("bounds_max", max.toShortString());
            List<TileAssemblerMatrixPattern> patternCores = cluster.getPatterns();
            int patternCount = 0;
            for (TileAssemblerMatrixPattern pCore : patternCores) {
                InternalInventory inv = pCore.getPatternInventory();
                for (int i = 0; i < inv.size(); i++) {
                    if (!inv.getStackInSlot(i).isEmpty()) patternCount++;
                }
            }
            int crafterCores = 0;
            Iterator<TileAssemblerMatrixBase> it = cluster.getBlockEntities();
            while (it.hasNext()) {
                if (it.next() instanceof TileAssemblerMatrixCrafter) crafterCores++;
            }
            readings.put("pattern_cores", patternCores.size());
            readings.put("crafter_cores", crafterCores);
            readings.put("speed_cores", cluster.getSpeedCore());
            readings.put("busy_crafters", cluster.getBusyCrafterAmount());
            readings.put("pattern_count", patternCount);
        }
        return new DeviceSnapshot("assembler_matrix", blockId, Map.of(), List.of(), readings);
    }

    private static Map<String, String> exportSettings(Object target) {
        if (!(target instanceof IConfigurableObject configurable)) return Map.of();
        IConfigManager config = configurable.getConfigManager();
        if (config == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        config.exportSettings().forEach((k, v) -> out.put(k.toLowerCase(Locale.ROOT), v.toLowerCase(Locale.ROOT)));
        return out;
    }

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

    private static List<String> inventoryEntries(InternalInventory inv) {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.isEmpty()) list.add(i + ":" + itemIdOf(stack) + " ×" + stack.getCount());
        }
        return list;
    }

    private static Map<String, String> genericInvMap(GenericStackInv inv) {
        Map<String, String> map = new LinkedHashMap<>();
        if (inv == null) return map;
        for (int i = 0; i < inv.size(); i++) {
            GenericStack stack = inv.getStack(i);
            if (stack != null) map.put(Integer.toString(i), stack.what().getId() + " ×" + stack.amount());
        }
        return map;
    }

    private static List<String> directionNames(Iterable<Direction> dirs) {
        List<String> list = new ArrayList<>();
        if (dirs != null) {
            for (Direction dir : dirs) {
                if (dir != null) list.add(dir.getSerializedName());
            }
        }
        return list;
    }

    private static String colorName(AEColor color) {
        return color == null ? "transparent" : color.name().toLowerCase(Locale.ROOT);
    }

    private static String itemIdOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
