// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 角色自身观察主手读法：主手空着返回 null，不再拿副手物品顶替。 */
class ClientSelfSightTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 主手空时返回空_主手有物时返回注册名() {
        assertNull(ClientSelfSight.mainHandItemId(ItemStack.EMPTY), "主手空着就是 null，不拿副手顶替");
        assertNull(ClientSelfSight.mainHandItemId(null));
        assertEquals("minecraft:iron_pickaxe", ClientSelfSight.mainHandItemId(new ItemStack(Items.IRON_PICKAXE)));
    }
}
