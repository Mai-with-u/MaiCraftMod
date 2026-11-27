// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.advancedae;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.inventories.InternalInventory;
import appeng.api.orientation.RelativeSide;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.GenericStack;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.helpers.externalstorage.GenericStackInv;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.pedroksl.advanced_ae.common.entities.ReactionChamberEntity;

import org.maiwithu.maicraft.compat.advancedae.AdvancedAeReads;
import org.maiwithu.maicraft.server.compat.advancedae.AdvancedAeServerReads;
import org.maiwithu.maicraft.server.compat.advancedae.AdvancedAeServerReads.DeviceSnapshot;

/**
 * 高级 AE 服务端只读操作（advanced_ae.device）的 NeoForge 读写端：
 * 零反射直接读取 ReactionChamberEntity 的双流体槽（槽 1 输入、槽 0 输出）、
 * 输入输出物品槽快照、加工进度、缓冲电量、自动输出开关、允许输出面、升级卡与缺电/输出告警。
 */
public final class AdvancedAeDeviceServerReads implements AdvancedAeServerReads.Reader {

    @Override public Optional<DeviceSnapshot> read(ServerLevel level, BlockPos at) {
        BlockEntity entity = level.getBlockEntity(at);
        if (!(entity instanceof ReactionChamberEntity chamber)) return Optional.empty();
        String blockId = BuiltInRegistries.BLOCK.getKey(chamber.getBlockState().getBlock()).toString();
        String autoExport = chamber.getConfigManager().getSetting(Settings.AUTO_EXPORT) == YesNo.YES ? "yes" : "no";
        Map<String, String> settings = Map.of("auto_export", autoExport);
        List<String> upgrades = readUpgrades(chamber.getUpgrades());
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("working", chamber.isWorking());
        readings.put("powered", chamber.getMainNode().isPowered());
        readings.put("active", chamber.getMainNode().isActive());
        fillTank(readings, "input_fluid", chamber.getTank(), 1);
        fillTank(readings, "output_fluid", chamber.getTank(), 0);
        readings.put("processing_time", chamber.getProcessingTime());
        readings.put("max_processing_time", chamber.getMaxProcessingTime());
        readings.put("energy_stored", chamber.getInternalCurrentPower());
        readings.put("energy_capacity", chamber.getInternalMaxPower());
        readings.put("auto_export", autoExport);
        readings.put("output_sides", readOutputSides(chamber));
        readings.put("show_warning", chamber.showWarning());
        readings.put("input_items", readInventoryTexts(chamber.getInput()));
        readings.put("output_items", readInventoryTexts(chamber.getOutput()));
        return Optional.of(new DeviceSnapshot("reaction_chamber", blockId, settings, upgrades, readings));
    }

    private static void fillTank(Map<String, Object> readings, String prefix, GenericStackInv tank, int slot) {
        GenericStack stack = tank != null && slot >= 0 && slot < tank.size() ? tank.getStack(slot) : null;
        if (stack != null && stack.what() instanceof AEFluidKey fluidKey && stack.amount() > 0L) {
            readings.put(prefix, BuiltInRegistries.FLUID.getKey(fluidKey.getFluid()).toString());
            readings.put(prefix + "_mb", stack.amount());
        } else {
            readings.put(prefix, "empty");
            readings.put(prefix + "_mb", 0L);
        }
        readings.put(prefix + "_capacity_mb", (long) AdvancedAeReads.TANK_CAPACITY_MB);
    }

    private static List<String> readOutputSides(ReactionChamberEntity chamber) {
        List<String> out = new ArrayList<>();
        for (RelativeSide side : chamber.getAllowedOutputs()) {
            out.add(side.name().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(out);
    }

    private static List<String> readUpgrades(IUpgradeInventory inv) {
        if (inv == null) return List.of();
        List<String> out = new ArrayList<>(inv.size());
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            out.add(stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        }
        return List.copyOf(out);
    }

    private static List<String> readInventoryTexts(InternalInventory inv) {
        if (inv == null) return List.of();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.isEmpty()) {
                out.add(BuiltInRegistries.ITEM.getKey(stack.getItem()) + " x" + stack.getCount());
            }
        }
        return List.copyOf(out);
    }
}
