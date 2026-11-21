// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.appmek;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.StorageCell;
import appeng.items.contents.PortableCellMenuHost;
import appeng.items.tools.powered.PortableCellItem;
import appeng.me.cells.BasicCellInventory;
import appeng.menu.me.common.MEStorageMenu;
import me.ramidzkh.mekae2.AMMenus;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import me.ramidzkh.mekae2.ae2.MekanismKeyType;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.Chemical;
import mekanism.api.chemical.ChemicalStack;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.compat.appmek.AppMekReads;

/**
 * 应用机械（Applied Mekanistics）的 NeoForge 客户端读写端：
 * 零反射直接调用 {@link MekanismKey}、{@link MekanismKeyType}、{@link MekanismAPI#CHEMICAL_REGISTRY}、
 * {@link AMMenus#PORTABLE_CHEMICAL_CELL_TYPE}、{@link StorageCells} 与 {@link PortableCellItem}，
 * 负责将化学品注册 ID 包装成 AE2 通用堆叠物品及免开窗读取便携化学品元件电量与盘内化学品存量。
 */
public final class AppliedMekanisticsReads implements AppMekReads {

    // 按通用机械化学品注册表查出非空化学品引用，再包装成 AE2 幽灵过滤槽与处理样板槽可直接接收的 ItemStack。
    @Override public Optional<ItemStack> wrapChemical(String chemicalId, long amount) {
        if (chemicalId == null || chemicalId.isBlank() || amount <= 0L) return Optional.empty();
        ResourceLocation keyId = ResourceLocation.tryParse(chemicalId.trim());
        if (keyId == null) return Optional.empty();
        Optional<Holder.Reference<Chemical>> holder = MekanismAPI.CHEMICAL_REGISTRY.getHolder(keyId);
        if (holder.isEmpty() || holder.get().is(MekanismAPI.EMPTY_CHEMICAL_KEY)) {
            return Optional.empty();
        }
        MekanismKey key = MekanismKey.of(new ChemicalStack(holder.get(), 1L));
        if (key == null) return Optional.empty();
        ItemStack wrapped = GenericStack.wrapInItemStack(key, amount);
        return wrapped.isEmpty() ? Optional.empty() : Optional.of(wrapped);
    }

    // 仅认领键类型为 MekanismKeyType.TYPE 的便携化学品元件，直接从客户端同步组件读取电量、容量与盘内化学品。
    @Override public Optional<PortableChemicalCellSnapshot> readPortableChemicalCell(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof PortableCellItem cell)) {
            return Optional.empty();
        }
        if (cell.getKeyType() != MekanismKeyType.TYPE) {
            return Optional.empty();
        }
        double currentEnergy = cell.getAECurrentPower(stack);
        double maxEnergy = cell.getAEMaxPower(stack);
        long totalBytes = cell.getBytes(stack);
        int bytesPerType = cell.getBytesPerType(stack);
        int totalTypes = cell.getTotalTypes(stack);
        long usedBytes = 0L;
        int storedTypes = 0;
        List<ChemicalStockEntry> chemicals = new ArrayList<>();
        StorageCell inv = StorageCells.getCellInventory(stack, null);
        if (inv instanceof BasicCellInventory basic) {
            totalBytes = basic.getTotalBytes();
            usedBytes = basic.getUsedBytes();
            storedTypes = (int) basic.getStoredItemTypes();
            totalTypes = (int) basic.getTotalItemTypes();
        }
        if (inv != null) {
            for (var entry : inv.getAvailableStacks()) {
                long amount = entry.getLongValue();
                if (amount <= 0L) continue;
                AEKey key = entry.getKey();
                if (key instanceof MekanismKey chemicalKey) {
                    chemicals.add(new ChemicalStockEntry(chemicalKey.getId().toString(), amount));
                }
            }
        }
        chemicals.sort(Comparator.comparingLong(ChemicalStockEntry::amountMb).reversed()
                .thenComparing(ChemicalStockEntry::chemicalId));
        String itemId = BuiltInRegistries.ITEM.getKey(cell).toString();
        return Optional.of(new PortableChemicalCellSnapshot(itemId, currentEnergy, maxEnergy,
                totalBytes, usedBytes, bytesPerType, storedTypes, totalTypes, chemicals));
    }

    // 仅当打开的 ME 存储菜单宿主为便携元件且菜单类型为应用机械便携化学品元件时返回真。
    @Override public boolean isPortableChemicalCellMenu(AbstractContainerMenu menu) {
        if (!(menu instanceof MEStorageMenu storage)) return false;
        if (!(storage.getHost() instanceof PortableCellMenuHost<?>)) return false;
        return menu.getType() == AMMenus.PORTABLE_CHEMICAL_CELL_TYPE;
    }
}
