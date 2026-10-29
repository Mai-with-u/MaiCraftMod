// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 挖概率掉落时挑工具，以及挑工具要的几样物品事实（时运等级、会不会让方块只掉它自己）。
 * 挖砾石出燧石、树叶出树苗时另有一套挑法：不拿会让方块只掉它自己的剪刀和精准采集，时运高的优先。
 * 平常挖方块拿哪把归玩家行为层的挑工具，这里不比较工具快慢。
 */
public final class ToolSelect {

    private ToolSelect() {}

    /**
     * 挖概率掉落的方块时挑哪一格（-1 表示就用手上这件）：砾石出燧石、树叶出树苗、草出种子都看手上拿的是什么。
     * 剪刀和带精准采集的工具会让方块只掉它自己，一件燧石、树苗都出不来，不拿；带时运的优先（时运越高出得越多），
     * 同样的时运再比挖得快。没有合适的、手上又正拿着剪刀或精准采集时，换到一格空着或拿着普通东西的格子，空手挖。
     */
    public static int bestSlotForChanceDrops(LocalPlayer p, BlockState state) {
        Inventory inv = p.getInventory();
        int usableSlots = Math.min(36, inv.getContainerSize());
        int best = -1;
        int bestFortune = 0;
        float bestSpeed = 1.0f;
        for (int i = 0; i < usableSlots; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || keepsBlockWhole(s)) continue;
            if (s.isDamageableItem() && s.getDamageValue() >= s.getMaxDamage()) continue;
            // 要合格工具才掉东西的方块（概率表里没有这种，留着兜底）：不合格的不拿，挖了什么都不掉。
            if (state.requiresCorrectToolForDrops() && !s.isCorrectToolForDrops(state)) continue;
            int fortune = fortuneLevel(s);
            float speed = s.getDestroySpeed(state);
            if (fortune == 0 && speed <= 1.0f) continue;
            if (fortune > bestFortune || fortune == bestFortune && speed > bestSpeed) {
                best = i;
                bestFortune = fortune;
                bestSpeed = speed;
            }
        }
        if (best >= 0 || !keepsBlockWhole(p.getMainHandItem())) return best;
        // 手上是剪刀或精准采集、又没有更合适的：先找快捷栏里空着或拿着普通东西的一格，再找背包里的。
        for (int i = 0; i < usableSlots; i++) {
            if (!keepsBlockWhole(inv.getItem(i))) return i;
        }
        return -1;
    }

    /** 挖概率掉落时手上会是哪一件：按上面的挑法，挑不出来就是手上现在这件。报价按它的时运算机会。 */
    public static ItemStack toolForChanceDrops(LocalPlayer p, BlockState state) {
        int slot = bestSlotForChanceDrops(p, state);
        return slot >= 0 ? p.getInventory().getItem(slot) : p.getMainHandItem();
    }

    /** 这件东西上的时运等级；没有为 0。 */
    public static int fortuneLevel(ItemStack stack) {
        return enchantmentLevel(stack, Enchantments.FORTUNE);
    }

    /** 拿它挖方块只掉方块自己：剪刀（树叶、草）和带精准采集的工具。 */
    public static boolean keepsBlockWhole(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return stack.getItem() instanceof ShearsItem || stack.is(Items.SHEARS)
                || enchantmentLevel(stack, Enchantments.SILK_TOUCH) > 0;
    }

    // 附魔组件里按附魔的注册键找等级：不经注册表查，客户端拿到的物品组件就够了。
    private static int enchantmentLevel(ItemStack stack, ResourceKey<Enchantment> enchantment) {
        for (var entry : stack.getEnchantments().entrySet()) {
            if (entry.getKey().is(enchantment)) return entry.getIntValue();
        }
        return 0;
    }
}
