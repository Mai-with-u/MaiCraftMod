// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.backpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.behavior.menu.MenuClick;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;

/**
 * 往随身背包主存储里塞一堆物品的点击规划：纯判断，不碰游戏。
 *
 * <p>不用 Shift 快速移动盲弹（避免把升级件误弹进升级槽），而是显式拿起主背包源格后只往通过校验的主存储槽左键放入：
 * 先并入同物品同组件的未满堆，再占空的主存储槽；正打开的那只背包所在槽位与任何精妙背包物品都排除在源格之外，
 * 记忆筛选锁给别的物品的槽位与过滤升级拒收的槽位由 {@code canStoreInSlot} 挡在落点之外；
 * 没有空槽只能并走一部分时，最后补一下左键把尾数放回原源格，保证光标落空。
 */
final class BackpackStorePlan {

    private BackpackStorePlan() {}

    /** 规划好的一笔存放：从哪个界面源槽拿起、依次点哪些槽位。 */
    record PlannedMove(int sourceSlotId, List<MenuClick> clicks) {
        PlannedMove {
            clicks = List.copyOf(clicks);
        }
    }

    /**
     * 在主背包一侧挑出要搬走的那一格：优先挑件数与计划一致的，否则挑件数最少的一格（最容易整格搬空腾出空位）；
     * 正在打开的那只随身背包自己与任何精妙背包物品绝不选中。
     */
    static int pickSourceIndex(MenuContent.Reading reading, String wantedItemId, int preferredCount,
            BackpackSlot.Location openedPack, Predicate<String> itemIdOfSnapshotMatches,
            Predicate<String> isBackpackItem) {
        if (isBackpackItem.test(wantedItemId)) {
            return -1;
        }
        int bestIndex = -1;
        int bestCount = Integer.MAX_VALUE;
        List<SlotSnapshot> playerSnapshots = reading.playerSnapshots();
        List<Integer> playerIndices = reading.playerInventoryIndices();
        for (int i = 0; i < playerSnapshots.size(); i++) {
            // 正在打开的这只包若在主背包里，绝不点起它自己套娃。
            if (openedPack.inMainInventory() && playerIndices.get(i) == openedPack.slotIndex()) {
                continue;
            }
            SlotSnapshot snapshot = playerSnapshots.get(i);
            if (snapshot.isEmpty() || !itemIdOfSnapshotMatches.test(itemIdOf(snapshot))) {
                continue;
            }
            if (snapshot.count() == preferredCount) {
                return i;
            }
            if (snapshot.count() < bestCount) {
                bestCount = snapshot.count();
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    /**
     * 为选定的源槽规划放入主存储的点击序列；主存储已满或所有槽位都拒收这种物品时给空。
     *
     * @param canStoreInSlot 按界面槽位号回答该主存储槽能否接纳源格里的物品栈
     */
    static Optional<PlannedMove> planClicks(MenuContent.Reading reading, int sourceIndex,
            IntPredicate canStoreInSlot) {
        SlotSnapshot source = reading.playerSnapshots().get(sourceIndex);
        if (source.isEmpty()) {
            return Optional.empty();
        }
        int sourceSlotId = reading.playerSlotIds().get(sourceIndex);
        ItemStack sourceStack = source.stack();
        int maxStack = Math.max(1, sourceStack.getMaxStackSize());
        int remaining = source.count();
        List<Integer> targetSlots = new ArrayList<>();

        List<Integer> storageSlotIds = reading.containerSlotIds();
        List<SlotSnapshot> storageSnapshots = reading.containerSnapshots();
        // 先并进主存储里已有的同物品同组件未满堆，省下空槽装别的杂物。
        for (int i = 0; i < storageSnapshots.size() && remaining > 0; i++) {
            int slotId = storageSlotIds.get(i);
            SlotSnapshot target = storageSnapshots.get(i);
            if (target.isEmpty() || !canStoreInSlot.test(slotId)) continue;
            if (!ItemStack.isSameItemSameComponents(target.stack(), sourceStack)) continue;
            int room = maxStack - target.count();
            if (room <= 0) continue;
            targetSlots.add(slotId);
            remaining -= Math.min(remaining, room);
        }
        // 还有剩的就找可放入的空主存储槽一次放下。
        for (int i = 0; i < storageSnapshots.size() && remaining > 0; i++) {
            int slotId = storageSlotIds.get(i);
            if (!storageSnapshots.get(i).isEmpty() || !canStoreInSlot.test(slotId)) continue;
            targetSlots.add(slotId);
            remaining = 0;
        }
        if (targetSlots.isEmpty()) {
            return Optional.empty();
        }
        List<MenuClick> clicks = new ArrayList<>(targetSlots.size() + 2);
        clicks.add(new MenuClick(sourceSlotId, false, 0, true));
        for (int targetSlotId : targetSlots) {
            clicks.add(new MenuClick(targetSlotId, false, 0, false));
        }
        // 主存储没有空槽、只并走了一部分时，把光标上剩下的尾数左键放回原源格。
        if (remaining > 0) {
            clicks.add(new MenuClick(sourceSlotId, false, 0, false));
        }
        return Optional.of(new PlannedMove(sourceSlotId, clicks));
    }

    private static String itemIdOf(SlotSnapshot snapshot) {
        return BuiltInRegistries.ITEM.getKey(snapshot.stack().getItem()).toString();
    }
}
