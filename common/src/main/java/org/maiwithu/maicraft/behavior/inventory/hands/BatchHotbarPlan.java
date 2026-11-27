// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.hands;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import net.minecraft.world.inventory.ClickType;

/**
 * 投前批量预置快捷栏的纯规划：把多件待连续扔出的原料在一次打开背包内，按所需份数分槽排进不同的快捷栏格，
 * 避免贴边连扔期间反复开背包换手超过原版拾取冷却时间；快捷栏空位或可挪格不足时平滑跳过剩余项，
 * 交给投掷循环按单样换手回退，不硬拒。
 */
public final class BatchHotbarPlan {

    /** 原版快捷栏格数（0..8）。 */
    static final int HOTBAR_SIZE = 9;
    /** 玩家主背包最后一格（快捷栏 0..8 + 主背包 9..35）。 */
    static final int LAST_MAIN_SLOT = 35;

    /** 一格背包的只读快照：放着什么物品、件数、单格上限、以及是否属于不可随意挪走的保护格。 */
    public record Slot(String itemId, int count, int maxStackSize, HotbarSwapTarget.Holding holding) {
        public static Slot empty() {
            return new Slot(null, 0, 64, HotbarSwapTarget.Holding.EMPTY);
        }

        public boolean isEmpty() {
            return itemId == null || count <= 0;
        }
    }

    /** 一项待预置进快捷栏的原料需求：要什么、本槽至少准备几件、是否必须精确只留这么多件。 */
    public record Request(String itemId, int count, boolean exact) {
        public Request {
            Objects.requireNonNull(itemId, "itemId");
            if (count < 1) throw new IllegalArgumentException("预置件数至少为 1：" + count);
        }
    }

    /**
     * 一次背包界面点击，以及点完后相关格子的预期件数，供服务端确认使用。
     *
     * @param inventorySlot      本次点击的背包格号（0..35）
     * @param button             左键 0 / 右键 1；当 {@code type == SWAP} 时为目标快捷栏格号（0..8）
     * @param type               点击方式（PICKUP 或 SWAP）
     * @param otherInventorySlot 参与联动的另一背包格号（分堆目标格或 SWAP 目标快捷栏格）；无则为 -1
     * @param slotCountAfter     点击后 {@code inventorySlot} 的预期件数
     * @param otherCountAfter    点击后 {@code otherInventorySlot} 的预期件数；无另一格时为 0
     * @param cursorCountAfter   点击后鼠标光标上的预期件数
     * @param itemId             本次搬运或分堆的物品 ID
     */
    public record Click(int inventorySlot, int button, ClickType type, int otherInventorySlot,
                        int slotCountAfter, int otherCountAfter, int cursorCountAfter, String itemId) {}

    /**
     * 批量预置规划结果。
     *
     * @param clicks      需要依次执行的背包点击；为空时说明所有已就位原料都已在快捷栏，不必打开背包
     * @param hotbarSlots 与每条需求一一对应的目标快捷栏格号（0..8）；空位不足未能预置的项为 -1
     */
    public record Plan(List<Click> clicks, List<Integer> hotbarSlots) {
        public Plan {
            clicks = List.copyOf(clicks);
            hotbarSlots = List.copyOf(hotbarSlots);
        }
    }

    private BatchHotbarPlan() {}

    /**
     * 逐项规划每个原料在快捷栏的槽位和最少点击序列：
     * 已经在快捷栏且件数合适的直接认领（零点击），在主背包的尽量用一次交换或最少点击分堆进空闲快捷栏格；
     * 前面已分配的快捷栏格会锁定，避免后续原料把前一样顶回主背包。
     */
    public static Plan plan(List<Slot> initialSlots, int selectedHotbar, List<Request> requests) {
        List<Slot> slots = copySlots(initialSlots);
        List<Click> clicks = new ArrayList<>();
        List<Integer> assigned = new ArrayList<>(requests.size());
        Set<Integer> reserved = new HashSet<>();

        for (Request request : requests) {
            int slot = stageOne(slots, selectedHotbar, request, reserved, clicks);
            assigned.add(slot);
            if (slot >= 0) reserved.add(slot);
        }
        return new Plan(clicks, assigned);
    }

    private static List<Slot> copySlots(List<Slot> initialSlots) {
        List<Slot> copy = new ArrayList<>(LAST_MAIN_SLOT + 1);
        for (int i = 0; i <= LAST_MAIN_SLOT; i++) {
            copy.add(i < initialSlots.size() && initialSlots.get(i) != null ? initialSlots.get(i) : Slot.empty());
        }
        return copy;
    }

    // 为单条需求在未锁定的快捷栏中安排槽位：优先零点击认领现成格，再考虑拆分或交换；空间不足返回 -1 平滑回退。
    private static int stageOne(List<Slot> slots, int selectedHotbar, Request request,
                                Set<Integer> reserved, List<Click> clicks) {
        int exactInHotbar = findHotbarSlot(slots, request.itemId(), request.count(), true, reserved);
        if (exactInHotbar >= 0) return exactInHotbar;

        int source = findSourceSlot(slots, request.itemId(), request.count(), reserved);
        if (source < 0) return -1;

        Slot sourceSlot = slots.get(source);
        int want = Math.min(request.count(), Math.max(1, sourceSlot.maxStackSize()));
        // 整格件数不超过所需份数：已在快捷栏直接认领，在主背包则一次交换进空闲或可挪的快捷栏格。
        if (sourceSlot.count() <= want) {
            if (source < HOTBAR_SIZE) return source;
            int targetHotbar = pickTargetHotbar(slots, selectedHotbar, reserved, false);
            if (targetHotbar < 0) return -1;
            appendSwap(slots, source, targetHotbar, request.itemId(), clicks);
            return targetHotbar;
        }
        return stageBySplitting(slots, selectedHotbar, source, want, request, reserved, clicks);
    }

    // 源格件数超出本轮所需份数：优先直接分进空闲快捷栏格；快捷栏无空格时按是否需要精确份数决定借主背包空格中转还是整格认领。
    private static int stageBySplitting(List<Slot> slots, int selectedHotbar, int source, int want,
                                        Request request, Set<Integer> reserved, List<Click> clicks) {
        int emptyHotbar = pickTargetHotbar(slots, selectedHotbar, reserved, true);
        if (emptyHotbar >= 0) {
            appendSplit(slots, source, emptyHotbar, want, clicks);
            return emptyHotbar;
        }
        // 非精确份数且快捷栏无空格：若已在快捷栏直接整格认领，若在主背包则用一次交换进可挪格，避免卡在开包拆堆。
        if (!request.exact()) {
            if (source < HOTBAR_SIZE) return source;
            int movableHotbar = pickTargetHotbar(slots, selectedHotbar, reserved, false);
            if (movableHotbar < 0) return -1;
            appendSwap(slots, source, movableHotbar, request.itemId(), clicks);
            return movableHotbar;
        }
        // 精确份数且快捷栏无空格：先借主背包空格分出恰好份数，再交换进目标快捷栏格，让余量安全留在主背包。
        int emptyMain = findEmptyMainSlot(slots);
        if (emptyMain < 0) return -1;
        int targetHotbar = source < HOTBAR_SIZE ? source : pickTargetHotbar(slots, selectedHotbar, reserved, false);
        if (targetHotbar < 0) return -1;
        appendSplit(slots, source, emptyMain, want, clicks);
        appendSwap(slots, emptyMain, targetHotbar, request.itemId(), clicks);
        return targetHotbar;
    }

    // 在快捷栏未锁定格中寻找已有同物品的格：exactOnly 为真时只认件数恰好相等（或单格已满时等于上限）的格。
    private static int findHotbarSlot(List<Slot> slots, String itemId, int wantCount, boolean exactOnly,
                                      Set<Integer> reserved) {
        for (int slot = 0; slot < HOTBAR_SIZE; slot++) {
            if (reserved.contains(slot)) continue;
            Slot current = slots.get(slot);
            if (current.isEmpty() || !itemId.equals(current.itemId())) continue;
            int capped = Math.min(wantCount, Math.max(1, current.maxStackSize()));
            if (!exactOnly || current.count() == capped) return slot;
        }
        return -1;
    }

    // 挑选源格：优先件数恰好等于所需份数的格（免拆分），其次件数足够且尽量在快捷栏之外、或件数最接近的同物品格。
    private static int findSourceSlot(List<Slot> slots, String itemId, int wantCount, Set<Integer> reserved) {
        int exactMain = -1;
        int enough = -1;
        int fallback = -1;
        for (int slot = 0; slot <= LAST_MAIN_SLOT; slot++) {
            if (slot < HOTBAR_SIZE && reserved.contains(slot)) continue;
            Slot current = slots.get(slot);
            if (current.isEmpty() || !itemId.equals(current.itemId())) continue;
            int capped = Math.min(wantCount, Math.max(1, current.maxStackSize()));
            if (current.count() == capped) {
                if (slot >= HOTBAR_SIZE && exactMain < 0) exactMain = slot;
                if (fallback < 0) fallback = slot;
            } else if (current.count() >= capped && enough < 0) {
                enough = slot;
            } else if (fallback < 0) {
                fallback = slot;
            }
        }
        if (exactMain >= 0) return exactMain;
        if (enough >= 0) return enough;
        return fallback;
    }

    // 挑选目标快捷栏格：跳过已锁定格和保护格（工具/武器/食物/桶/火把/图腾）；空格优先于可挪格，靠右优先。
    private static int pickTargetHotbar(List<Slot> slots, int selectedHotbar, Set<Integer> reserved,
                                        boolean emptyOnly) {
        List<HotbarSwapTarget.Holding> order = emptyOnly
                ? List.of(HotbarSwapTarget.Holding.EMPTY)
                : List.of(HotbarSwapTarget.Holding.EMPTY, HotbarSwapTarget.Holding.MOVABLE);
        for (HotbarSwapTarget.Holding wanted : order) {
            int fallbackSelected = -1;
            for (int slot = HOTBAR_SIZE - 1; slot >= 0; slot--) {
                if (reserved.contains(slot)) continue;
                Slot current = slots.get(slot);
                HotbarSwapTarget.Holding holding = current.isEmpty()
                        ? HotbarSwapTarget.Holding.EMPTY : current.holding();
                if (holding != wanted) continue;
                if (slot != selectedHotbar) return slot;
                fallbackSelected = slot;
            }
            if (fallbackSelected >= 0) return fallbackSelected;
        }
        return -1;
    }

    private static int findEmptyMainSlot(List<Slot> slots) {
        for (int slot = HOTBAR_SIZE; slot <= LAST_MAIN_SLOT; slot++) {
            if (slots.get(slot).isEmpty()) return slot;
        }
        return -1;
    }

    // 记录一组最少点击分堆操作，并同步更新虚拟背包快照。
    private static void appendSplit(List<Slot> slots, int sourceSlot, int destinationSlot, int amount,
                                    List<Click> clicks) {
        Slot source = slots.get(sourceSlot);
        int capacity = Math.max(1, source.maxStackSize());
        for (StackSplit.Step step : StackSplit.plan(source.count(), amount, capacity)) {
            boolean clickSource = step.side() == StackSplit.Side.SOURCE;
            int clicked = clickSource ? sourceSlot : destinationSlot;
            int other = clickSource ? destinationSlot : sourceSlot;
            int clickedAfter = clickSource ? step.after().source() : step.after().deposited();
            int otherAfter = clickSource ? step.after().deposited() : step.after().source();
            clicks.add(new Click(clicked, step.button(), ClickType.PICKUP, other,
                    clickedAfter, otherAfter, step.after().cursor(), source.itemId()));
        }
        int remain = source.count() - amount;
        slots.set(sourceSlot, remain > 0
                ? new Slot(source.itemId(), remain, capacity, source.holding()) : Slot.empty());
        slots.set(destinationSlot, new Slot(source.itemId(), amount, capacity, HotbarSwapTarget.Holding.KEEP));
    }

    // 记录一次原生 SWAP 交换操作（主背包格与快捷栏格互换），并同步更新虚拟背包快照。
    private static void appendSwap(List<Slot> slots, int mainSlot, int hotbarSlot, String itemId,
                                   List<Click> clicks) {
        Slot fromMain = slots.get(mainSlot);
        Slot fromHotbar = slots.get(hotbarSlot);
        int mainAfterCount = fromHotbar.isEmpty() ? 0 : fromHotbar.count();
        int hotbarAfterCount = fromMain.isEmpty() ? 0 : fromMain.count();
        clicks.add(new Click(mainSlot, hotbarSlot, ClickType.SWAP, hotbarSlot,
                mainAfterCount, hotbarAfterCount, 0, itemId));
        slots.set(mainSlot, fromHotbar);
        slots.set(hotbarSlot, fromMain);
    }
}
