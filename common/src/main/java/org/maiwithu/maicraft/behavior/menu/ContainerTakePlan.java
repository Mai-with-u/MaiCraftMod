// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 开箱取货的取货计划：下一笔从容器哪一格拿几件、要点哪几下的纯计算，不含点击本身。
 *
 * <p>像真人一样不把整箱搬空：整堆够用（要的不少于这一格装着的，含刚好够）才整堆快速移动；
 * 只要这格的一部分时，按二分堆拿到光标上，放进背包那一侧放得下的一格，多拿的一个一个退回源格。
 * 拿够就停：容器里剩下的格子与件数都不再碰。随身背包取货用的是同一份计划。
 */
public final class ContainerTakePlan {

    /** 一笔取货：从容器哪一格拿几件，按顺序要点哪几下。 */
    public record Move(int containerSlotId, int amount, List<MenuClick> clicks) {

        public Move {
            if (clicks.isEmpty()) throw new IllegalArgumentException("一笔取货至少要有点击");
            clicks = List.copyOf(clicks);
        }
    }

    private ContainerTakePlan() {
    }

    /**
     * 下一笔拿什么：容器侧从前往后找装着想要东西的第一格。背包那一侧放不下尾数（没有空格，
     * 同种东西的堆也都装不下）时不给计划——宁可不拿也不硬点整堆把整组搬走，执行层照常收场。
     *
     * @param reading 界面此刻的分侧读数
     * @param wanted  什么样的槽位算想要的东西
     * @param needed  还缺几件；不缺时没有下一笔
     */
    public static Optional<Move> next(MenuContent.Reading reading, Predicate<SlotSnapshot> wanted, int needed) {
        if (needed < 1) return Optional.empty();
        List<SlotSnapshot> container = reading.containerSnapshots();
        for (int i = 0; i < container.size(); i++) {
            SlotSnapshot snapshot = container.get(i);
            if (snapshot.isEmpty() || !wanted.test(snapshot)) continue;
            int slotId = reading.containerSlotIds().get(i);
            if (snapshot.count() <= needed) {
                // 背包那一侧一件都放不下（没空格、同种的堆也满了）：点了也纹丝不动，还会被记成"没能确认"，
                // 不点，交回去让引擎先腾地方。
                if (roomyPlayerSlot(reading.playerSnapshots(), snapshot, 1) < 0) return Optional.empty();
                // 这整堆都用得上（含刚好够）：一笔快速移动整堆进背包，落在背包哪格由游戏挑。
                return Optional.of(new Move(slotId, snapshot.count(),
                        List.of(new MenuClick(slotId, true, 0, false))));
            }
            // 只要这格的一部分：按二分堆拿到光标上，放进背包侧放得下的一格。
            int targetIndex = roomyPlayerSlot(reading.playerSnapshots(), snapshot, needed);
            if (targetIndex < 0) return Optional.empty();
            return Optional.of(new Move(slotId, needed,
                    exactClicks(slotId, reading.playerSlotIds().get(targetIndex), snapshot, needed)));
        }
        return Optional.empty();
    }

    // 尾数搬运的点击序列：与搬运计划同一个拆法，点法共用 MenuClicks.exact。
    private static List<MenuClick> exactClicks(int sourceSlotId, int targetSlotId, SlotSnapshot source, int amount) {
        return MenuClicks.exact(sourceSlotId, targetSlotId, source, amount);
    }

    // 背包那一侧放得下这么多件的一格：先并进同种东西还没满的堆，再占空格；都没有给 -1。
    private static int roomyPlayerSlot(List<SlotSnapshot> player, SlotSnapshot item, int amount) {
        int max = item.stack().getMaxStackSize();
        int empty = -1;
        for (int i = 0; i < player.size(); i++) {
            SlotSnapshot slot = player.get(i);
            if (slot.isEmpty()) {
                if (empty < 0) empty = i;
            } else if (slot.sameIdentity(item) && slot.count() + amount <= max) {
                return i;
            }
        }
        return empty;
    }
}
