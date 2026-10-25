// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.hands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;

/**
 * 落地用船接住的备手打算（实机 005 实锤的场景）：背包里只有一条船、两手空空，
 * 备手把船换进快捷栏的空格再选中——挑格不认物品种类，船这类不可堆叠的大件与零碎走同一条路。
 */
class ClientMovesToMainhandTest {

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void aBoatInTheBackpackWithEmptyHandsSwapsIntoAnEmptyHotbarSlotAwayFromTheSelectedOne() {
        // 两手空空：选中的第 0 格空着，快捷栏九格全空。船在主背包第 12 格，
        // 备手打算把它换进不等于选中格的空格（最靠右的第 8 格），换完选中那一格船就是主手，别的什么都没动。
        Inventory inventory = new Inventory(null);
        inventory.setItem(12, new ItemStack(Items.OAK_BOAT));
        inventory.selected = 0;
        assertEquals(8, ClientMovesToMainhand.planSwapTarget(inventory));
    }

    @Test
    void aPlainBoatIsMovableOddsAndEndsNotSomethingToKeep() {
        // 船不是工具、不是吃的、不是桶：快捷栏放着零碎时可以换进那一格，不被当成不能动的东西。
        assertEquals(HotbarSwapTarget.Holding.MOVABLE,
                HotbarSwapTarget.holding(new ItemStack(Items.OAK_BOAT), ItemsToKeep.NOTHING));
    }

    @Test
    void startingOverKeepsTheTargetTheCallerAskedFor() {
        // 卡住从头再打算（审计 A51）：调用方指定的格（垫料挪到第 3 格）照旧，"换到选中那一格"（-1）也照旧；
        // 只有原本就是挑格换到手上的，按此刻的背包重挑。
        Inventory inventory = new Inventory(null);
        inventory.setItem(12, new ItemStack(Items.OAK_BOAT));
        inventory.selected = 0;
        assertEquals(3, ClientMovesToMainhand.restartTarget(3, false, inventory));
        assertEquals(-1, ClientMovesToMainhand.restartTarget(-1, false, inventory));
        assertEquals(ClientMovesToMainhand.planSwapTarget(inventory), ClientMovesToMainhand.restartTarget(5, true, inventory));
    }

    @Test
    void stowingRelocatesAnEmptyMainSlotNotAnEmptyHotbarSlot() {
        // 收主手进背包要搬的是"空气"：重找时找主背包里还空着的格（第 10 格），不去快捷栏的空格。
        Inventory inventory = new Inventory(null);
        inventory.setItem(0, new ItemStack(Items.STONE_PICKAXE));
        inventory.setItem(9, new ItemStack(Items.COBBLESTONE, 64));
        assertEquals(10, ClientMovesToMainhand.relocate(inventory, Items.AIR));
        // 平常的东西：快捷栏、主背包里先找到的那格，再看副手。
        inventory.setItem(20, new ItemStack(Items.OAK_BOAT));
        assertEquals(20, ClientMovesToMainhand.relocate(inventory, Items.OAK_BOAT));
        assertEquals(null, ClientMovesToMainhand.relocate(inventory, Items.TORCH));
    }
}
