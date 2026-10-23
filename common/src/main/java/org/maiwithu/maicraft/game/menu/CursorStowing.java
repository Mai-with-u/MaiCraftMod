// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.menu;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 光标上拿着的东西放进玩家背包哪一格的统一挑选：像玩家一样先叠到能整个装下的同种堆上（组件也算同种），
 * 叠不下再找空格；空格主背包优先，免得把手边的快捷栏工具位占了。关界面前还光标、终端取货放光标、
 * 交易收货都问这一份；快捷栏 0-8、主背包 9-35 是背包布局的游戏事实，全仓只在这里认一次。
 *
 * <p>只认角色自己背包（主背包 27 格加快捷栏 9 格）的格子，盔甲、副手、容器那一侧都不放。
 * 两个入口在快捷栏空格上差一口气：从打开的界面挑是关界面前还光标，一格都放不下时宁可占快捷栏
 * 也不把东西丢地上；只凭读数挑的（放取货计划、装不装得下的判断）装不下时调用方另有处置
 * （放回网络、先不买），快捷栏空格不当垫底。
 */
public final class CursorStowing {

    /** 快捷栏在角色背包里的格号是 0 到 8，主背包是 9 到 35。 */
    private static final int HOTBAR_SIZE = 9;
    private static final int MAIN_INVENTORY_END = 36;
    /** 主背包有 27 格：只凭读数挑时按列表位置认主背包和快捷栏。 */
    private static final int MAIN_INVENTORY_SIZE = 27;

    private CursorStowing() {}

    /** 一格候选：界面里的槽位号、背包里的格号、现在放着什么、这一格对光标上的东西最多能放几件、这格收不收这种东西。 */
    public record Spot(int slotId, int inventoryIndex, ItemStack current, int capacity, boolean mayPlace) {}

    /** 打开的界面里挑：角色背包那一侧的格子都进候选，肯不肯收看格子自己；快捷栏空格垫底。装不下给 -1。 */
    public static int fromMenu(AbstractContainerMenu menu, ItemStack carried) {
        List<Spot> spots = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (!(slot.container instanceof Inventory) || slot.getContainerSlot() >= MAIN_INVENTORY_END) continue;
            spots.add(new Spot(slot.index, slot.getContainerSlot(), slot.getItem(),
                    Math.min(slot.getMaxStackSize(carried), carried.getMaxStackSize()), slot.mayPlace(carried)));
        }
        return choose(spots, carried);
    }

    /**
     * 只有一份角色侧读数（槽位号与每格内容对齐）、拿不到原界面时挑：这格收不收证明不了，按收得上算，
     * 容量只看物品堆叠上限。不占快捷栏空格，只剩快捷栏放得下也给 -1；同种堆在快捷栏上不占新格，照样能叠。
     * 槽位号按界面分侧读数的顺序：主背包 27 格在前、快捷栏 9 格在后，按位置还原背包格号。
     */
    public static int fromSnapshots(List<Integer> slotIds, List<ItemStack> contents, ItemStack carried) {
        List<Spot> spots = new ArrayList<>();
        for (int i = 0; i < slotIds.size(); i++) {
            ItemStack current = contents.get(i);
            boolean mainInventory = i < MAIN_INVENTORY_SIZE;
            int capacity = current.isEmpty() ? carried.getMaxStackSize()
                    : Math.min(current.getMaxStackSize(), carried.getMaxStackSize());
            // 快捷栏的空格不给读数挑的调用方当垫底；已有的同种堆不占工具位，照样能叠。
            boolean mayPlace = mainInventory || !current.isEmpty();
            spots.add(new Spot(slotIds.get(i), mainInventory ? HOTBAR_SIZE + i : i - MAIN_INVENTORY_SIZE,
                    current, capacity, mayPlace));
        }
        return choose(spots, carried);
    }

    /** 从候选里挑：能整个叠上去的同样东西优先，再主背包空格，最后快捷栏空格；都不行为 -1。 */
    static int choose(List<Spot> spots, ItemStack carried) {
        for (Spot spot : spots) {
            if (!spot.mayPlace()) continue;
            if (!spot.current().isEmpty() && ItemStack.isSameItemSameComponents(spot.current(), carried)
                    && spot.current().getCount() + carried.getCount() <= spot.capacity()) {
                return spot.slotId();
            }
        }
        int hotbarEmpty = -1;
        for (Spot spot : spots) {
            if (!spot.mayPlace() || !spot.current().isEmpty() || carried.getCount() > spot.capacity()) continue;
            if (spot.inventoryIndex() >= HOTBAR_SIZE) return spot.slotId();
            if (hotbarEmpty < 0) hotbarEmpty = spot.slotId();
        }
        return hotbarEmpty;
    }
}
