// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToIntFunction;

import org.maiwithu.maicraft.ability.machine.spi.MachineFeeding;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.recipe.ShownIngredient;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.behavior.recipe.ShownStack;

/**
 * 机器多候选配方的优选判断（纯函数）：同一台机器有多条配方都能做出目标物品时，
 * 不再盲选列表第 0 条，而是按「角色随身/已知存量可满足原料 → 主产物而非副产 →
 * 常规原料而非虫蚀方块、矿石原矿等稀有或不可再生原料」逐级择优，同等条件保持原顺序。
 */
final class MachineRecipeChoice {

    /** 虫蚀方块原料惩罚：生存中极难取得，仅当没有常规原料配方时才退而选它。 */
    private static final int PENALTY_INFESTED = 100;
    /** 不可再生或极稀有物品原料惩罚（下界合金、下界之星、龙蛋、信标等）。 */
    private static final int PENALTY_RARE = 80;
    /** 矿石原矿原料惩罚（原矿方块与远古残骸）：绝不为磨砾石、圆石去烧/磨矿石。 */
    private static final int PENALTY_ORE = 50;

    private static final Set<String> RARE_PATH_TOKENS = Set.of(
            "netherite", "nether_star", "dragon_egg", "dragon_head", "beacon",
            "elytra", "heart_of_the_sea", "heavy_core", "enchanted_golden_apple",
            "diamond_block", "emerald_block", "ancient_debris");

    private MachineRecipeChoice() {}

    /**
     * 从已筛出可在当前机器运行的候选配方中挑选最优一条。
     *
     * @param candidates     候选配方列表（非空）
     * @param targetItem     要做的目标物品注册 ID
     * @param targetCount    要做的目标件数
     * @param availableStock 查询角色随身或已知存量中某样原料件数的只读函数
     */
    static ShownRecipe choose(List<ShownRecipe> candidates, String targetItem, int targetCount,
            ToIntFunction<WantedItem> availableStock) {
        if (candidates.size() <= 1) {
            return candidates.getFirst();
        }
        return candidates.stream()
                .min(Comparator
                        // 先看随身/已知存量满足度：全部批次满足(0) < 至少够做 1 批(1) < 1 批都凑不齐(2)。
                        .comparingInt((ShownRecipe recipe) -> stockTier(recipe, targetItem, targetCount, availableStock))
                        // 再看主产物还是副产物：首位必出主产物(0) < 非首位必出(1) < 概率副产物(2)。
                        .thenComparingInt(recipe -> outputRoleTier(recipe, targetItem))
                        // 同为概率产物时，出货概率越高的越优先。
                        .thenComparing(Comparator.comparingDouble((ShownRecipe recipe) -> bestChance(recipe, targetItem)).reversed())
                        // 再看原料常规程度：常规基础方块(0) 远优于原矿(50)、珍稀物品(80)与虫蚀方块(100)。
                        .thenComparingInt(MachineRecipeChoice::ingredientRarityPenalty)
                        // 同档下缺料件数越少越好、单批产出越多越好、原料格数越少越好。
                        .thenComparingInt(recipe -> missingCount(recipe, targetItem, targetCount, availableStock))
                        .thenComparing(Comparator.comparingLong((ShownRecipe recipe) -> perBatchOutput(recipe, targetItem)).reversed())
                        .thenComparingInt(recipe -> recipe.inputs().size()))
                .orElse(candidates.getFirst());
    }

    /** 给人看的一句选中说明：带出第一项原料和类别，便于在结果备注里核对选了哪条。 */
    static String describeChoice(ShownRecipe recipe) {
        String firstInput = recipe.inputs().stream()
                .map(MachineFeeding::describe)
                .findFirst()
                .orElse("无原料");
        return recipe.category() + "（原料：" + firstInput + "）";
    }

    // 存量满足档位：0 = 满足全部所需批次；1 = 至少能做 1 批；2 = 连 1 批原料都凑不齐。
    private static int stockTier(ShownRecipe recipe, String targetItem, int targetCount,
            ToIntFunction<WantedItem> availableStock) {
        int batches = MachineIngredients.batchesNeeded(recipe, targetItem, targetCount);
        if (missingForBatches(recipe, batches, availableStock) == 0) {
            return 0;
        }
        return missingForBatches(recipe, 1, availableStock) == 0 ? 1 : 2;
    }

    // 产出主副档位：首位必出为主产物(0)，非首位必出次之(1)，带概率的副产物垫底(2)。
    private static int outputRoleTier(ShownRecipe recipe, String targetItem) {
        List<ShownStack> outputs = recipe.outputs();
        int best = 2;
        for (int i = 0; i < outputs.size(); i++) {
            ShownStack stack = outputs.get(i);
            if (stack.kind() != ShownStack.Kind.ITEM || !stack.id().equals(targetItem)) {
                continue;
            }
            int tier = stack.hasChance() ? 2 : (i == 0 ? 0 : 1);
            if (tier < best) {
                best = tier;
            }
        }
        return best;
    }

    // 目标产物在这条配方输出里的最高几率；没标几率按 1.0 算。
    private static double bestChance(ShownRecipe recipe, String targetItem) {
        return recipe.outputs().stream()
                .filter(stack -> stack.kind() == ShownStack.Kind.ITEM && stack.id().equals(targetItem))
                .mapToDouble(ShownStack::chance)
                .max()
                .orElse(0.0);
    }

    // 整条配方的原料稀有/不可再生惩罚总分：多选原料格取其中最常规的一个选项，避免标签里混入稀有变体误伤常规配方。
    static int ingredientRarityPenalty(ShownRecipe recipe) {
        int total = 0;
        for (ShownIngredient ingredient : recipe.inputs()) {
            total += ingredientPenalty(ingredient);
        }
        return total;
    }

    private static int ingredientPenalty(ShownIngredient ingredient) {
        List<ShownStack> items = ingredient.options().stream()
                .filter(option -> option.kind() == ShownStack.Kind.ITEM)
                .toList();
        if (items.isEmpty()) {
            return 0;
        }
        int best = Integer.MAX_VALUE;
        for (ShownStack option : items) {
            int penalty = itemRarityPenalty(option.id());
            if (penalty < best) {
                best = penalty;
            }
        }
        return best == Integer.MAX_VALUE ? 0 : best;
    }

    // 单个物品 ID 的稀有度惩罚：虫蚀方块 > 极稀有/不可再生块 > 矿石原矿 > 常规材料。
    static int itemRarityPenalty(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return 0;
        }
        String lower = itemId.toLowerCase(Locale.ROOT);
        int colon = lower.indexOf(':');
        String path = colon >= 0 ? lower.substring(colon + 1) : lower;
        if (path.startsWith("infested_") || path.contains("_infested_")) {
            return PENALTY_INFESTED;
        }
        for (String token : RARE_PATH_TOKENS) {
            if (path.equals(token) || path.contains(token)) {
                return PENALTY_RARE;
            }
        }
        if (path.endsWith("_ore") || path.contains("_ore_") || path.equals("ore")) {
            return PENALTY_ORE;
        }
        return 0;
    }

    private static int missingCount(ShownRecipe recipe, String targetItem, int targetCount,
            ToIntFunction<WantedItem> availableStock) {
        int batches = MachineIngredients.batchesNeeded(recipe, targetItem, targetCount);
        return missingForBatches(recipe, batches, availableStock);
    }

    private static int missingForBatches(ShownRecipe recipe, int batches, ToIntFunction<WantedItem> availableStock) {
        // 先按同种原料汇总多格需求总量再扣除库存，避免多格要求同种原料时重复计算同一份随身库存。
        Map<WantedItem, Long> requiredByItem = new LinkedHashMap<>();
        for (ShownIngredient ingredient : recipe.inputs()) {
            Optional<WantedItem> wanted = MachineFeeding.wantedItemOf(ingredient);
            if (wanted.isEmpty()) {
                continue;
            }
            long required = ingredient.amount() * batches;
            requiredByItem.merge(wanted.get(), required, Long::sum);
        }
        int missing = 0;
        for (Map.Entry<WantedItem, Long> entry : requiredByItem.entrySet()) {
            int have = Math.max(0, availableStock.applyAsInt(entry.getKey()));
            long lack = Math.max(0, entry.getValue() - have);
            missing = (int) Math.min(Integer.MAX_VALUE, (long) missing + lack);
        }
        return missing;
    }

    private static long perBatchOutput(ShownRecipe recipe, String targetItem) {
        return recipe.outputs().stream()
                .filter(stack -> stack.kind() == ShownStack.Kind.ITEM && stack.id().equals(targetItem))
                .mapToLong(ShownStack::amount)
                .sum();
    }
}
