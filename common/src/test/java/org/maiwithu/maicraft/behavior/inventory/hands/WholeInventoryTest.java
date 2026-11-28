// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.hands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 全身背包按注册名的两种读法：数遍每一个格（含盔甲与副手格）与查主手读选中的快捷栏格。 */
class WholeInventoryTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 清点数遍全身含盔甲与副手格() {
        Inventory inventory = new Inventory(null);
        inventory.setItem(3, new ItemStack(Items.COAL, 5));
        inventory.setItem(12, new ItemStack(Items.COAL, 7));
        inventory.setItem(36, new ItemStack(Items.COAL, 2));
        inventory.setItem(40, new ItemStack(Items.COAL));
        inventory.setItem(20, new ItemStack(Items.STICK));
        assertEquals(15, WholeInventory.count(inventory, "minecraft:coal"),
                "主格、盔甲格与副手格里的煤一起数，别的东西不算");
        assertEquals(0, WholeInventory.count(inventory, "minecraft:iron_ingot"), "身上没有就数出 0");
    }

    @Test
    void 主手查验读选中的快捷栏格() {
        Inventory inventory = new Inventory(null);
        inventory.setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
        inventory.selected = 0;
        assertTrue(WholeInventory.mainhandHolds(inventory, "minecraft:diamond_pickaxe"), "选中格拿的就是主手");
        inventory.selected = 3;
        inventory.setItem(3, new ItemStack(Items.STICK));
        assertFalse(WholeInventory.mainhandHolds(inventory, "minecraft:diamond_pickaxe"), "换格之后主手不再是它");
        assertTrue(WholeInventory.mainhandHolds(inventory, "minecraft:stick"), "换了格按新选中格查验");
    }
}
