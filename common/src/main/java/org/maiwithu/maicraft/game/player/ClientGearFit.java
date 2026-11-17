// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 按游戏自己的规则读物品与装备栏的匹配：先问原版 Equipable 接口，未实现该接口的模组可穿戴物
 * （如精妙背包重写了扩展槽位规则）再通过当前角色背包界面的护甲槽位 rules（mayPlace）向加载器核对。
 *
 * <p>护甲、鞘翅、盾牌、可戴的头颅各自知道自己的栏位，不自己维护一份"什么能戴头上"的清单。查不到的物品 ID 什么都放不进。
 */
public final class ClientGearFit implements ReadsGearFit {

    private final Supplier<PlayerContext> contexts;

    public ClientGearFit() {
        this(() -> null);
    }

    public ClientGearFit(Supplier<PlayerContext> contexts) {
        this.contexts = Objects.requireNonNull(contexts, "contexts");
    }

    @Override
    public boolean fits(String itemId, GearSlotName slot) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null) return false;
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(id);
        if (item.isEmpty()) return false;
        ItemStack stack = new ItemStack(item.get());
        Equipable equipable = Equipable.get(stack);
        if (equipable != null) {
            EquipmentSlot nativeSlot = equipable.getEquipmentSlot();
            return switch (slot) {
                case MAINHAND -> nativeSlot == EquipmentSlot.MAINHAND;
                case OFFHAND -> nativeSlot == EquipmentSlot.OFFHAND;
                case HEAD -> nativeSlot == EquipmentSlot.HEAD;
                case CHEST -> nativeSlot == EquipmentSlot.CHEST;
                case LEGS -> nativeSlot == EquipmentSlot.LEGS;
                case FEET -> nativeSlot == EquipmentSlot.FEET;
            };
        }
        // 精妙背包等未实现原版 Equipable 的物品：借用当前玩家背包界面护甲格的 mayPlace 触发 NeoForge 扩展槽位校验。
        int menuSlot = slot.armorScreenSlot();
        if (menuSlot < 0) return false;
        PlayerContext current = contexts.get();
        if (current == null || current.localPlayer() == null) return false;
        var menu = current.localPlayer().inventoryMenu;
        return menu != null && menuSlot < menu.slots.size() && menu.getSlot(menuSlot).mayPlace(stack);
    }
}
