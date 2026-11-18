// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.hands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;

/**
 * 落地用船接住的备手打算（实机 005 实锤的场景）：背包里只有一条船、两手空空，
 * 备手把船换进快捷栏的空格再选中——挑格不认物品种类，船这类不可堆叠的大件与零碎走同一条路。
 */
class ClientMovesToMainhandTest {

    /** 离线没有数据包，物品标签得手动绑上：船挂在原版的 boats 标签下。 */
    private static final TagKey<Item> BOATS = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath("minecraft", "boats"));

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void bindBoatTags() {
        BuiltInRegistries.ITEM.bindTags(Map.of(BOATS,
                List.of(holder(Items.OAK_BOAT), holder(Items.SPRUCE_BOAT))));
    }

    private static Holder<Item> holder(Item item) {
        return BuiltInRegistries.ITEM.wrapAsHolder(item);
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

    @Test
    void findInInventoryMatchesExactItemsAndItemTags() {
        // 石磨等机器配方按 #标签 点名备料时，换手查找能按物品标签找到背包里对应的具体物品格。
        Inventory inventory = new Inventory(null);
        inventory.selected = 0;
        inventory.setItem(12, new ItemStack(Items.OAK_BOAT));
        assertEquals(12, ClientMovesToMainhand.findInInventory(inventory, "#minecraft:boats"));
        assertEquals(12, ClientMovesToMainhand.findInInventory(inventory, "minecraft:oak_boat"));
        inventory.setItem(0, new ItemStack(Items.SPRUCE_BOAT));
        assertEquals(ClientMovesToMainhand.ALREADY_SELECTED,
                ClientMovesToMainhand.findInInventory(inventory, "#minecraft:boats"));
    }

    @Test
    void findInInventoryPrefersOffhandOverWornArmorWhileStillReportingArmorWhenAlone() {
        // 脚上护甲格（36）穿着铁靴且副手（40）拿着另一双铁靴时，优先命中副手以便换到主手；
        // 只穿在护甲格里时仍返回护甲格号，供全身持有检查认出。
        Inventory inventory = new Inventory(null);
        inventory.selected = 0;
        inventory.armor.set(0, new ItemStack(Items.IRON_BOOTS));
        assertEquals(36, ClientMovesToMainhand.findInInventory(inventory, "minecraft:iron_boots"));

        inventory.offhand.set(0, new ItemStack(Items.IRON_BOOTS));
        assertEquals(40, ClientMovesToMainhand.findInInventory(inventory, "minecraft:iron_boots"));

        inventory.setItem(15, new ItemStack(Items.IRON_BOOTS));
        assertEquals(15, ClientMovesToMainhand.findInInventory(inventory, "minecraft:iron_boots"));
    }
}
