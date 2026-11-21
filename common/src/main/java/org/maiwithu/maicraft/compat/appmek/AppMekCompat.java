// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.appmek;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;
import org.maiwithu.maicraft.compat.ExternalKeyWrappers;
import org.maiwithu.maicraft.compat.ModApiMismatch;

/**
 * 应用机械（Applied Mekanistics）的联动入口：
 * 登记便携化学品元件界面布局证明、5 档便携化学品元件免开窗只读设置、应用机械中文知识来源，
 * 并向共享的 {@link ExternalKeyWrappers} 登记 {@code appmek:chemical} 化学品包装器。
 *
 * <p>刻意不登记随身背包（CarriedBackpack），便携化学品元件只存化学品，不参与物品腾背包；
 * 登记过程中途出错回滚或运行期触发接口不匹配停用后，立即停止提供化学品包装，不波及其他联动模组。
 */
public final class AppMekCompat extends CompatModule {

    public static final String MOD_ID = "appmek";
    /** 应用机械向 AE2 登记的化学品存储通道键类型 ID。 */
    public static final String CHEMICAL_KEY_TYPE = "appmek:chemical";

    private final AppMekReads reads;
    private final ExternalKeyWrappers externalKeys;

    /** 离线测试用简写构造：独立持有一份外部键表。 */
    public AppMekCompat(AppMekReads reads) {
        this(reads, new ExternalKeyWrappers());
    }

    public AppMekCompat(AppMekReads reads, ExternalKeyWrappers externalKeys) {
        super(MOD_ID, "应用机械");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.externalKeys = Objects.requireNonNull(externalKeys, "externalKeys");
    }

    @Override public void contribute(CompatRegistry registry) {
        // 先完成全部登记表提交，最后再把化学品包装器挂进共享外部键表；任一步失败立即撤销，防止半登记残留。
        try {
            registry.menuLayout(this, new AppMekPortableCellLayout());
            registry.itemSettings(this, services -> new AppMekPortableCellSettings(this));
            registry.knowledgeSource(this, new AppMekKnowledge());
            if (externalKeys != ExternalKeyWrappers.NONE) {
                externalKeys.register(CHEMICAL_KEY_TYPE, this::active, this::wrapChemical);
            }
        } catch (RuntimeException | LinkageError failure) {
            externalKeys.unregister(CHEMICAL_KEY_TYPE);
            throw failure;
        }
    }

    /**
     * 经模组读写端将指定化学品 ID 与数量包装成可发给界面的 {@link ItemStack}。
     * 运行期碰到模组接口对不上时自动停用本联动、撤销外部键登记并返回空，避免连带打断 AE2 操作。
     */
    public Optional<ItemStack> wrapChemical(String chemicalId, long amount) {
        if (!active()) return Optional.empty();
        try {
            return call("包装化学品键", () -> reads.wrapChemical(chemicalId, amount));
        } catch (ModApiMismatch disabled) {
            externalKeys.unregister(CHEMICAL_KEY_TYPE);
            return Optional.empty();
        }
    }

    /** 经模组读写端免开窗读取便携化学品元件状态。 */
    public Optional<AppMekReads.PortableChemicalCellSnapshot> readPortableChemicalCell(ItemStack stack) {
        return call("读取便携化学品元件状态", () -> reads.readPortableChemicalCell(stack));
    }

    /** 经模组读写端判定打开的界面是否为便携化学品元件界面。 */
    public boolean isPortableChemicalCellMenu(AbstractContainerMenu menu) {
        return call("核对便携化学品元件界面", () -> reads.isPortableChemicalCellMenu(menu));
    }
}
