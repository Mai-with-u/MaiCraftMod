// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.List;

import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.behavior.recipe.ShownStack;

/**
 * 机器配方的批次换算：按目标件数折算要做几批。每格原料实际投几件、谁先投、有什么冲突，
 * 由机器类型的投料计划一次解析（spi 的 MachineFeeding.plan），这里不再逐格问。
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
}
