// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.Locale;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 一件东西命中没有：一个物品 ID 或一个 {@code #} 物品标签，写法和身上那件对得上就算命中。
 *
 * <p>标签是 Minecraft 自己表达"一类"的方式（{@code #c:gems/certus_quartz} 这一类石英），配方按标签点名备料时，
 * 身上带的是其中某一种具体物品，也要算命中。查不到标签（拼错、数据包里没有这个标签）时不命中，不当成命中一类。
 */
public final class ItemMatch {

    private ItemMatch() {}

    /**
     * 这一堆东西命中没有。
     *
     * @param stack      要看的那堆东西，空的一律不命中
     * @param itemOrTag 物品 ID（如 {@code minecraft:oak_boat}）或 {@code #} 开头的物品标签
     */
    public static boolean matches(ItemStack stack, String itemOrTag) {
        if (stack == null || stack.isEmpty() || itemOrTag == null || itemOrTag.isEmpty()) return false;
        if (itemOrTag.startsWith(InitTag.TAG_PREFIX)) {
            // 标签名同样按小写读，和游戏读 ID 的规矩一样。
            String name = itemOrTag.substring(1).trim().toLowerCase(Locale.ROOT);
            TagKey<Item> tag = InitTag.parseRef(Registries.ITEM,
                    InitTag.TAG_PREFIX + name);
            return tag != null && stack.is(tag);
        }
        ResourceLocation wanted = ResourceLocation.tryParse(itemOrTag.toLowerCase(Locale.ROOT));
        return wanted != null && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(wanted);
    }

    /** 写的是一个标签名吗（{@code #} 开头）。 */
    public static boolean isTag(String itemOrTag) {
        return itemOrTag != null && itemOrTag.startsWith(InitTag.TAG_PREFIX);
    }
}
