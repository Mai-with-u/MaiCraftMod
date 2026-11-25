// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.maiwithu.maicraft.ability.machine.spi.FeedPlan;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.behavior.recipe.ShownStack;

/**
 * 机器配方的批次换算与备料合并：按目标件数折算要做几批，并把多格重复出现的同名原料合并总量供一次备料，
 * 与投料阶段的逐格投放职责分离。每格原料实际投几件、谁先投、有什么冲突，仍由机器类型的投料计划一次解析。
 */
final class MachineIngredients {

    private MachineIngredients() {}

    /** 做够目标物品 targetCount 件至少要跑几批这条配方。 */
    static int batchesNeeded(ShownRecipe chosen, String targetItem, int targetCount) {
        long perBatch = chosen.outputs().stream()
                .filter(stack -> stack.kind() == ShownStack.Kind.ITEM && stack.id().equals(targetItem))
                .mapToLong(ShownStack::amount).sum();
        if (perBatch <= 0) {
            return 1;
        }
        return (int) Math.min(Integer.MAX_VALUE, (targetCount + perBatch - 1) / perBatch);
    }

    /**
     * 把投料计划中多格重复出现的同名原料合并成一条总量（保持首次出现顺序），供备料阶段一次性拿齐：
     * 例如配方有 16 格各要 1 个安山合金时合并为 1 条 ×16 备料，避免拆成 16 次 ×1 往返翻箱，
     * 投料队则保留原计划的逐格条目顺序独立执行。
     */
    static List<FeedPlan.Entry> consolidateForGather(List<FeedPlan.Entry> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }
        Map<String, Long> totals = new LinkedHashMap<>();
        Map<String, Boolean> collectBacks = new LinkedHashMap<>();
        for (FeedPlan.Entry entry : entries) {
            if (entry.count() <= 0) {
                continue;
            }
            totals.merge(entry.item(), (long) entry.count(), Long::sum);
            collectBacks.merge(entry.item(), entry.collectBack(), (a, b) -> a || b);
        }
        List<FeedPlan.Entry> consolidated = new ArrayList<>(totals.size());
        totals.forEach((item, total) -> consolidated.add(new FeedPlan.Entry(
                item, (int) Math.min(Integer.MAX_VALUE, total), collectBacks.getOrDefault(item, false))));
        return List.copyOf(consolidated);
    }
}
