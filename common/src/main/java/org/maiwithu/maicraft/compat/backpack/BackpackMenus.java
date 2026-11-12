// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.backpack;

import java.util.List;
import java.util.Optional;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * 精妙背包的界面与随身栏位读写接缝：扫出身上全部栏位的背包、认点开的背包界面、读主存储、发打开请求。
 * 只用 Minecraft 与 Java 的类型描述，不出现模组的类；真正看模组菜单、发模组打开请求的
 * 读写端在 NeoForge 模块里，测试用替身。
 */
public interface BackpackMenus {

    /** 点开的界面是不是角色身上顶层精妙背包的界面（地上放着的方块包与别人的包不算）。 */
    boolean isBackpackMenu(AbstractContainerMenu menu);

    /** 背包界面的升级占列信息是否已经从服务端同步完：没同步完前槽位还会重排，先等一等再读。 */
    default boolean isSynchronized(AbstractContainerMenu menu) {
        return true;
    }

    /**
     * 主存储的可用槽位号（升级槽、被升级占掉变灰的槽位都不算货）。
     * 主存储带无限格升级或槽位数超常理时给空，调用方按未证实跳过这只包，不硬搬。
     */
    Optional<List<Integer>> mainStorageSlotIds(AbstractContainerMenu menu);

    /** 角色主背包那一侧的槽位号（取出来的货往那里放，存东西从那里拿）。 */
    List<Integer> playerSlotIds(AbstractContainerMenu menu);

    /** 主存储这一格能不能放进这堆物品：同时受槽位本身规则与记忆筛选图标约束。 */
    default boolean canStoreInSlot(AbstractContainerMenu menu, int slotId, ItemStack stack) {
        return slotId >= 0 && slotId < menu.slots.size() && menu.getSlot(slotId).mayPlace(stack);
    }

    /** 主存储这一格是不是没有设记忆筛选图标的纯空槽：设了记忆筛选的空槽只收特定物品，不算通用空位。 */
    default boolean isUnfilteredEmptySlot(AbstractContainerMenu menu, int slotId) {
        return slotId >= 0 && slotId < menu.slots.size() && menu.getSlot(slotId).getItem().isEmpty();
    }

    /** 角色身上全部已注册栏位（饰品栏、胸甲、副手、主背包）里背着或装着的精妙背包，按开包优先顺序排。 */
    default List<BackpackSlot> carriedBackpacks(Player player) {
        return List.of();
    }

    /** 请游戏按结构化栏位定位精确打开那一只随身背包；定位为空时由服务端找身上第一只。 */
    void open(BackpackSlot.Location location);

    /** 请游戏打开身上第一只随身背包，对应玩家直接按 B 键的那一下。 */
    default void openCarried() {
        open(BackpackSlot.Location.FIRST_CARRIED);
    }

    /** 按原版主背包格号打开那一只随身背包。 */
    default void open(int mainSlot) {
        open(BackpackSlot.Location.mainSlot(mainSlot));
    }
}
