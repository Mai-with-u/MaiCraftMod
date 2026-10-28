// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.result.Problem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.maiwithu.maicraft.behavior.inventory.containers.KnownContainer;
import org.maiwithu.maicraft.behavior.inventory.drops.IncidentalLoot;

/**
 * 腾挪计划：看着背包现在有什么、要腾几格，排出"先动哪一堆"的清单。
 *
 * <p>纯判断，不碰游戏。腾背包只做可逆的搬动，不替玩家扔东西：能合的散堆先合起来；然后从最不值钱的开始
 * 往外存——身上带着无线终端或附近有能用的 ME 终端就存进网络，随身背包能塞就塞，附近有记得的容器就存进去。
 * 装备、贵重品与要留的东西不进计划；能搬的都搬了还腾不出，如实失败，把现场能丢的候选、能存的容器
 * 列进建议里，丢哪一堆、存到哪由调用方拿主意。
 */
final class SpacePlanner {

    private SpacePlanner() {}

    /** 建议里最多列几个可丢候选与几个容器：列表太长反而盖住重点，数量照实写。 */
    private static final int MAX_ADVICE_ITEMS = 5;

    /**
     * 一次腾挪要看的现场。
     *
     * @param stacks                    背包主格里现在的物品堆
     * @param freeSlots                 还空着几格
     * @param slotsNeeded               要腾出几格
     * @param reservedItemIds           这次任务要留用的物品注册 ID
     * @param stackMergerAvailable      合并散堆的接缝接上了没有
     * @param carriedBackpackFreeSlots  随身背包还空着几格；没有随身背包时为 0
     * @param nearbyContainers          记得的附近容器；接缝没接上时为空
     * @param networkStorage            有终端能把东西存进网络：身上绑好的无线终端，或附近能用的装着的终端
     */
    record Scene(
            List<BackpackStack> stacks,
            int freeSlots,
            int slotsNeeded,
            Set<String> reservedItemIds,
            boolean stackMergerAvailable,
            int carriedBackpackFreeSlots,
            List<KnownContainer> nearbyContainers,
            boolean networkStorage) {

        /** 没有网络可存。 */
        Scene(List<BackpackStack> stacks, int freeSlots, int slotsNeeded, Set<String> reservedItemIds,
                boolean stackMergerAvailable, int carriedBackpackFreeSlots, List<KnownContainer> nearbyContainers) {
            this(stacks, freeSlots, slotsNeeded, reservedItemIds, stackMergerAvailable, carriedBackpackFreeSlots,
                    nearbyContainers, false);
        }
    }

    /**
     * 排好的计划：moves 是照顺序执行的腾挪；搬得动的都搬了还差格子时给 problem，搬得够就是全空。
     * 两者至多有一样。
     */
    record Plan(List<SpaceMove> moves, Problem problem) {
    }

    static Plan plan(Scene scene) {
        int slotsFreed = scene.freeSlots();
        if (slotsFreed >= scene.slotsNeeded) {
            throw new IllegalArgumentException("还有空格就不需要计划腾挪");
        }

        List<SpaceMove> moves = new ArrayList<>();
        slotsFreed += addMergeMoves(scene, moves);

        // 挪东西从最不值钱的开始：垃圾、零碎、建材、食物、有用的依次往后，装备、贵重、要留的东西不动。
        Map<String, Integer> totals = totalsInBackpack(scene.stacks());
        List<BackpackStack> movable = movableStacksWorthLeastFirst(scene);
        int containerIndex = 0;
        int carriedRoom = scene.carriedBackpackFreeSlots();
        // 存网络的几堆攒成一步：终端打开一次存完再关，不每样开关一次。
        List<BackpackStack> toNetwork = new ArrayList<>();
        for (BackpackStack stack : movable) {
            if (slotsFreed >= scene.slotsNeeded) {
                return new Plan(withNetwork(moves, toNetwork), null);
            }
            // 网络能存就先存进网络（玩家整理背包就是往 AE 里放）；再是随身背包、记得的容器。
            // 没有地方可存就跳过：这一堆留在背包里，要不要丢由调用方处置，Mod 不往地上扔。
            if (scene.networkStorage()) {
                toNetwork.add(stack);
            } else if (carriedRoom > 0) {
                moves.add(new SpaceMove.ToCarriedBackpack(stack));
                carriedRoom--;
            } else if (containerIndex < scene.nearbyContainers().size()) {
                moves.add(new SpaceMove.ToKnownContainer(scene.nearbyContainers().get(containerIndex), stack));
                containerIndex++;
            } else {
                continue;
            }
            slotsFreed++;
        }
        if (slotsFreed >= scene.slotsNeeded) {
            return new Plan(withNetwork(moves, toNetwork), null);
        }

        return new Plan(withNetwork(moves, toNetwork), notEnoughProblem(scene, slotsFreed));
    }

    // 攒下的存网络几堆作为一步接在合并之后。
    private static List<SpaceMove> withNetwork(List<SpaceMove> moves, List<BackpackStack> toNetwork) {
        if (toNetwork.isEmpty()) return List.copyOf(moves);
        List<SpaceMove> all = new ArrayList<>(moves);
        all.add(new SpaceMove.ToNetwork(toNetwork));
        return List.copyOf(all);
    }

    // 散堆合并：只数"真能并出来"的格子——数量小的并进装得下它的最大堆，并到没有一堆装得下另一堆为止。
    // 不能按"总数除以堆上限"的理想装袋数：那种数法会把需要拆堆才能达到的格子也算进来，合并的执行做不到。
    private static int addMergeMoves(Scene scene, List<SpaceMove> moves) {
        if (!scene.stackMergerAvailable()) {
            return 0;
        }
        Map<String, List<BackpackStack>> byItem = new LinkedHashMap<>();
        for (BackpackStack stack : scene.stacks()) {
            byItem.computeIfAbsent(stack.itemId(), ignored -> new ArrayList<>()).add(stack);
        }
        int freed = 0;
        for (List<BackpackStack> stacks : byItem.values()) {
            if (stacks.size() < 2) {
                continue;
            }
            List<Integer> counts = stacks.stream().map(BackpackStack::count).toList();
            int saved = achievableMerges(counts, stacks.getFirst().maxStackSize());
            if (saved > 0) {
                freed += saved;
                moves.add(new SpaceMove.MergeStacks(stacks.getFirst().itemId(), saved));
            }
        }
        return freed;
    }

    // 模拟合并的执行，数能省出几格：每次挑最小的一堆，并进装得下它的最大一堆；并不动了就停。
    // 模拟按同一种物品算（执行读端还比对物品组件，组件不同的散堆并不了，实际会比这里少并）。
    private static int achievableMerges(List<Integer> counts, int maxStackSize) {
        List<Integer> piles = new ArrayList<>(counts);
        int saved = 0;
        while (true) {
            piles.sort(Integer::compare);
            boolean merged = false;
            for (int small = 0; small < piles.size() && !merged; small++) {
                for (int big = piles.size() - 1; big > small; big--) {
                    if (piles.get(small) + piles.get(big) <= maxStackSize) {
                        piles.set(big, piles.get(small) + piles.get(big));
                        piles.remove(small);
                        saved++;
                        merged = true;
                        break;
                    }
                }
            }
            if (!merged) return saved;
        }
    }

    // 能自动挪去存的堆：装备、贵重与要留的东西不动，从垃圾排起（垃圾里按先存哪堆的既定顺序），
    // 零碎按清单先后，其余同级的保持背包里的格子顺序。
    private static List<BackpackStack> movableStacksWorthLeastFirst(Scene scene) {
        Map<String, Integer> totals = totalsInBackpack(scene.stacks());
        record Entry(BackpackStack stack, int worthRank, int junkOrder) {}
        List<Entry> entries = new ArrayList<>();
        for (BackpackStack stack : scene.stacks()) {
            Worth worth = ItemWorth.of(stack, scene.reservedItemIds(), totals);
            if (worth == Worth.KEPT || worth == Worth.GEAR || worth == Worth.PRECIOUS) continue;
            int order = worth == Worth.JUNK ? JunkItems.dropOrder(stack.itemId())
                    : worth == Worth.INCIDENTAL ? IncidentalLoot.dropOrder(stack.itemId()) : 0;
            entries.add(new Entry(stack, worth.ordinal(), order));
        }
        // 稳定排序：同级之间不动格子顺序，计划才可复现、测试才说得清。
        entries.sort((a, b) -> a.worthRank != b.worthRank ? Integer.compare(b.worthRank, a.worthRank)
                : Integer.compare(a.junkOrder, b.junkOrder));
        return entries.stream().map(Entry::stack).toList();
    }

    // 能搬的都搬了还腾不出：如实失败，建议照现场列——背包里有什么可丢的候选、附近有什么可存的容器，
    // 能力名给出来，处置的路怎么走由调用方定，不替它拍板丢哪一堆。
    private static Problem notEnoughProblem(Scene scene, int slotsFreedAfterMoves) {
        int atMost = slotsFreedAfterMoves;
        String message = "背包塞满了，能搬的都搬了也腾不出 " + scene.slotsNeeded() + " 格，最多腾出 " + atMost + " 格";
        List<String> ways = new ArrayList<>();
        // 可丢候选与能搬的是同一份排序：从最不值钱的排起，装备、贵重品不列——丢了难补的东西不该出现在建议里。
        List<BackpackStack> candidates = movableStacksWorthLeastFirst(scene);
        if (!candidates.isEmpty()) {
            List<String> described = candidates.stream().limit(MAX_ADVICE_ITEMS)
                    .map(candidate -> describe(candidate)).toList();
            ways.add("可以考虑丢掉一些：候选有 " + String.join("、", described)
                    + (candidates.size() > MAX_ADVICE_ITEMS ? " 等 " + candidates.size() + " 堆" : "")
                    + "（只是候选，丢哪堆你来定）");
        }
        List<String> containers = scene.nearbyContainers().stream().limit(MAX_ADVICE_ITEMS)
                .map(container -> container.name() + "（" + container.at().x() + "," + container.at().y()
                        + "," + container.at().z() + "）")
                .toList();
        if (scene.networkStorage()) {
            ways.add("身上的终端连着存储网络，可以把不重要的存进去");
        }
        if (!containers.isEmpty()) {
            ways.add("记得的容器有 " + String.join("、", containers));
        }
        // 能力名要与能力表一致：调用方照建议下达就能走通，写旧叫法会吃 unknown_ability。
        ways.add("用 drop 丢东西、用 deposit 存进容器，处置后重新下达这个目标");
        return Problem.of(Problem.Kind.INVENTORY_FULL, message, String.join("；", ways));
    }

    // 向 LLM 描述要处置的那一堆：数量加物品名，例如"32 个 minecraft:iron_ingot"。
    private static String describe(BackpackStack stack) {
        return stack.count() + " 个 " + stack.itemId();
    }

    private static Map<String, Integer> totalsInBackpack(List<BackpackStack> stacks) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (BackpackStack stack : stacks) {
            totals.merge(stack.itemId(), stack.count(), Integer::sum);
        }
        return totals;
    }
}
