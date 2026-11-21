// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.appmek;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * 应用机械（Applied Mekanistics）的客户端读写接缝：
 * 只使用 Minecraft 与 JDK 类型，不引用任何模组类；直接调用应用机械、AE2 与通用机械公开接口的实现放在
 * NeoForge 模块，离线测试使用替身实现。
 */
public interface AppMekReads {

    /** 未接入模组读写端时的空实现：所有包装与读数为空，菜单判定为否。 */
    AppMekReads NONE = new AppMekReads() {
        @Override public Optional<ItemStack> wrapChemical(String chemicalId, long amount) {
            return Optional.empty();
        }

        @Override public Optional<PortableChemicalCellSnapshot> readPortableChemicalCell(ItemStack stack) {
            return Optional.empty();
        }

        @Override public boolean isPortableChemicalCellMenu(AbstractContainerMenu menu) {
            return false;
        }
    };

    /**
     * 便携化学品元件内存储的单种化学品条目。
     *
     * @param chemicalId 化学品注册 ID（如 {@code mekanism:hydrogen}）
     * @param amountMb   存量（单位 mB，严格大于 0）
     */
    record ChemicalStockEntry(String chemicalId, long amountMb) {
        public ChemicalStockEntry {
            chemicalId = Objects.requireNonNull(chemicalId, "chemicalId");
            if (amountMb <= 0L) {
                throw new IllegalArgumentException("化学品存量必须为正：" + amountMb);
            }
        }
    }

    /**
     * 便携化学品存储元件的免开窗只读快照。
     *
     * @param itemId        物品注册 ID（{@code appmek:portable_chemical_cell_1k..256k}）
     * @param currentEnergy 当前电量（AE）
     * @param maxEnergy     最大电量上限（AE）
     * @param totalBytes    总字节容量
     * @param usedBytes     已使用字节数
     * @param bytesPerType  每种化学品占用的固定字节数
     * @param storedTypes   当前已存化学品种类数
     * @param totalTypes    最大可存化学品种类数
     * @param chemicals     盘内化学品存量列表（按存量降序）
     */
    record PortableChemicalCellSnapshot(String itemId, double currentEnergy, double maxEnergy,
                                        long totalBytes, long usedBytes, int bytesPerType,
                                        int storedTypes, int totalTypes, List<ChemicalStockEntry> chemicals) {
        public PortableChemicalCellSnapshot {
            itemId = Objects.requireNonNull(itemId, "itemId");
            chemicals = List.copyOf(Objects.requireNonNull(chemicals, "chemicals"));
        }
    }

    /** 将化学品注册 ID 与正毫桶数量包装成可发给 AE2 过滤槽或处理样板槽的 {@link ItemStack}。 */
    Optional<ItemStack> wrapChemical(String chemicalId, long amount);

    /** 免开窗读取便携化学品元件的电量、字节/种类容量与盘内化学品存量；不是便携化学品元件时为空。 */
    Optional<PortableChemicalCellSnapshot> readPortableChemicalCell(ItemStack stack);

    /** 判定已打开的容器界面是否为应用机械便携化学品元件界面。 */
    boolean isPortableChemicalCellMenu(AbstractContainerMenu menu);
}
