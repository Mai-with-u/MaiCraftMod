// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.behavior.inventory.hands.HotbarSwapTarget;
import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;

/**
 * 挖路用的工具只在主背包时挪到快捷栏的打算：寻路引擎挖方块只从快捷栏挑工具，主背包里的镐、原子拆解机它够不着，
 * 就拿手边的扳手、火把硬挖。像玩家一样先打开背包把挑工具挑中的那把换到快捷栏，再接着走。
 *
 * <p>挑哪把归挑工具（能掉落先于不掉落、最省优先、改掉落的附魔与预留的不掺和），这里只管挑中的在主背包时往哪一格挪：
 * 按 {@link HotbarSwapTarget}，空格先于零碎东西，工具、武器、吃的、桶、火把、正要用的不挪走。
 */
final class ToolStaging {

    private static final int HOTBAR_SIZE = 9;
    private static final int LAST_MAIN_SLOT = 35;

    private ToolStaging() {}

    /**
     * 要不要挪、怎么挪。挑中的已经在快捷栏、挑工具说不换、或快捷栏腾不出格子时为空。
     *
     * @param items    背包前 36 格：0~8 快捷栏，9~35 主背包
     * @param selected 选中的快捷栏格
     * @param chosen   挑工具对这一两步要挖的方块挑中的那一格；不换为 -1
     */
    static Optional<ScaffoldStaging.Swap> plan(List<ItemStack> items, int selected, int chosen, ItemsToKeep keeps) {
        if (chosen < HOTBAR_SIZE || chosen > LAST_MAIN_SLOT || chosen >= items.size()) return Optional.empty();
        int target = HotbarSwapTarget.pick(HotbarSwapTarget.hotbar(items, keeps), selected);
        if (target < 0) return Optional.empty();
        String itemId = BuiltInRegistries.ITEM.getKey(items.get(chosen).getItem()).toString();
        return Optional.of(new ScaffoldStaging.Swap(chosen, target, itemId));
    }
}
