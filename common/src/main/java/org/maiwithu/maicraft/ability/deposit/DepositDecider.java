// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.deposit;

import java.util.function.ToIntFunction;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;

import org.maiwithu.maicraft.game.player.BackpackStack;

/**
 * 存什么的判定：这次存哪些、各存几件，哪些留在身上。
 *
 * <p>LLM 点名 items 时存匹配的物品，点名的照存（点名的算允许，食物装备也不例外）；
 * 不点名时随身要留的东西以外的全部存掉：工具、武器、护甲与盾牌、食物、火把、
 * 一组垫脚方块留在身上，预留的、正要用的留够件数在身上，其余（包括贵重物品）都存——存在箱子里比带在身上安全。
 * count 是一共存几件，不给就存到匹配的都存完为止。
 */
final class DepositDecider {

    /** 不点名时留在身上的物品：盾牌和火把按注册 ID 认，装备与食物由背包快照的分类标记给。 */
    private static final Set<String> KEEP_BY_ID = Set.of(
            "minecraft:shield", "minecraft:torch", "minecraft:soul_torch", "minecraft:redstone_torch");

    /** 一件要存的东西：哪一格、存几件。 */
    record ToDeposit(BackpackStack stack, int amount) {}

    private DepositDecider() {}

    /**
     * 从背包堆里挑出这次要存的东西，按背包格的顺序；每组最多留一组建材当垫脚。
     *
     * @param stacks   背包主格里的物品堆，按格子顺序
     * @param itemIds  LLM 点名的物品 ID 或 # 标签；空表示不点名
     * @param count    一共存几件；null 表示存完为止
     * @param taggedIn 标签判断（itemId 是否在 tag 里）；接缝没接上时传永远为假的判断
     * @param keep     要留的东西每种留几件：不点名时留够这么多在身上
     */
    static List<ToDeposit> choose(List<BackpackStack> stacks, List<String> itemIds, Integer count,
            BiPredicate<String, String> taggedIn, ToIntFunction<String> keep) {
        boolean named = itemIds != null && !itemIds.isEmpty();
        List<ToDeposit> chosen = new ArrayList<>();
        int budget = count == null ? Integer.MAX_VALUE : count;
        boolean footBlockKept = false;
        // 不点名时按格子顺序先把要留的件数算进留在身上的，留够了后面的照常存；这一堆比要留的多，就只存多出来的。
        Map<String, Integer> stillToKeep = new HashMap<>();
        for (BackpackStack stack : stacks) {
            if (budget <= 0) break;
            int spare = stack.count();
            if (!named) {
                if (staysOnPlayer(stack)) continue;
                int left = stillToKeep.computeIfAbsent(stack.itemId(), keep::applyAsInt);
                if (left > 0) {
                    stillToKeep.put(stack.itemId(), left - Math.min(left, spare));
                    spare -= Math.min(left, spare);
                    if (spare <= 0) continue;
                }
                // 第一组整块建材留在身上当垫脚，往外的组都存。
                if (stack.buildingMaterial()) {
                    if (!footBlockKept) {
                        footBlockKept = true;
                        continue;
                    }
                }
            } else if (!matches(stack.itemId(), itemIds, taggedIn)) {
                continue;
            }
            int amount = Math.min(spare, budget);
            chosen.add(new ToDeposit(stack, amount));
            budget -= amount;
        }
        return chosen;
    }

    // 不点名时留在身上的：盾牌与火把按 ID，装备与食物按背包快照的分类标记。
    private static boolean staysOnPlayer(BackpackStack stack) {
        if (KEEP_BY_ID.contains(stack.itemId())) return true;
        return stack.gear() || stack.food();
    }

    // 点名的物品 ID 或标签对不对得上这一格。
    private static boolean matches(String itemId, List<String> itemIds, BiPredicate<String, String> taggedIn) {
        for (String filter : itemIds) {
            if (filter.startsWith("#")) {
                if (taggedIn.test(itemId, filter.substring(1))) return true;
            } else if (itemId.equals(filter)) {
                return true;
            }
        }
        return false;
    }
}
