// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.hands;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * 全身背包按注册名的两种读法：数遍每一个格（含盔甲与副手格）与查主手。直接读角色背包本体，
 * 与身上清点的主格数法是两种问法——给带电工具补电这类要看见每一个格里的东西，主格清点看不见。
 */
public final class WholeInventory {

    private WholeInventory() {}

    /** 全身背包（含盔甲与副手格）里注册名正是 itemId 的东西共几件。 */
    public static int count(Inventory inventory, String itemId) {
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 主手（选中的快捷栏格）拿的是不是注册名正是 itemId 的东西；和玩家主手读到的是同一格。 */
    public static boolean mainhandHolds(Inventory inventory, String itemId) {
        ItemStack held = inventory.getSelected();
        return !held.isEmpty() && BuiltInRegistries.ITEM.getKey(held.getItem()).toString().equals(itemId);
    }
}
