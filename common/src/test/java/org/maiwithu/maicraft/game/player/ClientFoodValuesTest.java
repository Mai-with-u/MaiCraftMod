// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 烹饪配方升级食物判断的离线测试：确认生肉、生鱼、生土豆烤成高营养无害熟食会被自然识别为可烹饪生食材，
 * 紫颂果、不可食用原料与有害产物不会被误判。
 */
class ClientFoodValuesTest {

    @BeforeAll
    static void 引导物品注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 烤成更高营养无害熟食的生食材应识别为可烹饪生食材() {
        assertTrue(ClientFoodValues.upgradesToBetterFood(new ItemStack(Items.COD), new ItemStack(Items.COOKED_COD)));
        assertTrue(ClientFoodValues.upgradesToBetterFood(new ItemStack(Items.SALMON), new ItemStack(Items.COOKED_SALMON)));
        assertTrue(ClientFoodValues.upgradesToBetterFood(new ItemStack(Items.BEEF), new ItemStack(Items.COOKED_BEEF)));
        assertTrue(ClientFoodValues.upgradesToBetterFood(new ItemStack(Items.MUTTON), new ItemStack(Items.COOKED_MUTTON)));
        assertTrue(ClientFoodValues.upgradesToBetterFood(new ItemStack(Items.POTATO), new ItemStack(Items.BAKED_POTATO)));
    }

    @Test
    void 不可食用产物或未提升营养或有害产物不应误判为可烹饪生食材() {
        // 紫颂果烧成爆裂紫颂果不可食用；海带原料本身不可食用；熟牛排不比面包低；毒马铃薯带有害效果。
        assertFalse(ClientFoodValues.upgradesToBetterFood(
                new ItemStack(Items.CHORUS_FRUIT), new ItemStack(Items.POPPED_CHORUS_FRUIT)));
        assertFalse(ClientFoodValues.upgradesToBetterFood(
                new ItemStack(Items.KELP), new ItemStack(Items.DRIED_KELP)));
        assertFalse(ClientFoodValues.upgradesToBetterFood(
                new ItemStack(Items.COOKED_BEEF), new ItemStack(Items.BREAD)));
        assertFalse(ClientFoodValues.upgradesToBetterFood(
                new ItemStack(Items.POTATO), new ItemStack(Items.POISONOUS_POTATO)));
    }
}
