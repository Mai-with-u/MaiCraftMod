// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.appmek;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.ability.machine.spi.ItemSettings;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 5 档便携化学品元件（1k/4k/16k/64k/256k）的免开窗只读物品检查（{@code machine_inspect(item=...)}）：
 * 直接读取客户端同步组件中的电量、字节/种类容量与盘内化学品清单（单位 mB）；
 * 电量耗尽时明确提示放入充能器（{@code ae2:charger}）执行 {@code machine_run} 补电。
 */
public final class AppMekPortableCellSettings implements ItemSettings {

    /** 全部 5 档便携化学品存储元件的物品注册 ID。 */
    public static final Set<String> PORTABLE_CHEMICAL_CELL_IDS = Set.of(
            "appmek:portable_chemical_cell_1k",
            "appmek:portable_chemical_cell_4k",
            "appmek:portable_chemical_cell_16k",
            "appmek:portable_chemical_cell_64k",
            "appmek:portable_chemical_cell_256k");

    private final AppMekCompat compat;
    private final Function<ItemStack, String> itemIdReader;

    public AppMekPortableCellSettings(AppMekCompat compat) {
        this(compat, AppMekPortableCellSettings::registryItemId);
    }

    /** 离线测试用构造：允许无真实模组注册物品时直接指定堆叠对应的物品 ID。 */
    AppMekPortableCellSettings(AppMekCompat compat, Function<ItemStack, String> itemIdReader) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.itemIdReader = Objects.requireNonNull(itemIdReader, "itemIdReader");
    }

    @Override public boolean covers(ItemStack stack) {
        return stack != null && !stack.isEmpty() && PORTABLE_CHEMICAL_CELL_IDS.contains(itemIdReader.apply(stack));
    }

    @Override public List<MachineSetting> settings(ItemStack stack) {
        if (!covers(stack)) return List.of();
        Optional<AppMekReads.PortableChemicalCellSnapshot> snapshotOpt = compat.readPortableChemicalCell(stack);
        if (snapshotOpt.isEmpty()) return List.of();
        AppMekReads.PortableChemicalCellSnapshot cell = snapshotOpt.get();
        String energyPair = formatAe(cell.currentEnergy()) + "/" + formatAe(cell.maxEnergy()) + " AE";
        String energyNote = cell.currentEnergy() <= 0.0
                ? "电量不足（" + energyPair + "），请把便携化学品元件放入充能器（ae2:charger）执行 machine_run 补电"
                : "当前电量（AE）；低电时请放入充能器（ae2:charger）执行 machine_run 补电，绝不潜行右键空元件以防拆解";
        String bytesValue = cell.usedBytes() + "/" + cell.totalBytes();
        String bytesNote = "已用/总容量字节数（每字节可存 8000 mB 化学品，单种类基础占用 " + cell.bytesPerType() + " 字节）";
        String typesValue = cell.storedTypes() + "/" + cell.totalTypes();
        String chemicalsValue = formatChemicals(cell.chemicals());
        return List.of(
                readOnly("energy", formatAe(cell.currentEnergy()), energyNote),
                readOnly("energy_max", formatAe(cell.maxEnergy()), "便携化学品元件电量上限（AE）"),
                readOnly("bytes", bytesValue, bytesNote),
                readOnly("types", typesValue, "已存/最大化学品种类数（不可存入放射性化学品）"),
                readOnly("chemicals", chemicalsValue, "盘内已存化学品清单（单位 mB，免开窗只读）"));
    }

    @Override public Optional<Action> change(int inventorySlot, Map<String, String> wanted, Permissions permissions) {
        return Optional.empty();
    }

    /** 格式化盘内化学品清单：空盘返回 empty，有存货按「id 数量 mB」逗号拼接。 */
    static String formatChemicals(List<AppMekReads.ChemicalStockEntry> entries) {
        if (entries == null || entries.isEmpty()) return "empty";
        return entries.stream()
                .map(entry -> entry.chemicalId() + " " + entry.amountMb() + " mB")
                .collect(Collectors.joining(", "));
    }

    /** 格式化 AE 电量数值：接近整数时去掉小数尾零，否则保留一位小数。 */
    static String formatAe(double value) {
        double safe = Math.max(0.0, value);
        if (Math.abs(safe - Math.rint(safe)) < 0.05) {
            return Long.toString(Math.round(safe));
        }
        return String.format(Locale.ROOT, "%.1f", safe);
    }

    private static MachineSetting readOnly(String key, String value, String description) {
        return new MachineSetting(key, value, List.of(), description);
    }

    private static String registryItemId(ItemStack stack) {
        return stack == null || stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
