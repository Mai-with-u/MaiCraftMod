// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.machine.spi.FeedPlan;
import org.maiwithu.maicraft.ability.machine.spi.FuelUse;
import org.maiwithu.maicraft.ability.machine.spi.MachineFeeding;
import org.maiwithu.maicraft.ability.machine.spi.FullRun;
import org.maiwithu.maicraft.ability.machine.spi.IngredientFeed;
import org.maiwithu.maicraft.ability.machine.spi.MachineFeeding;
import org.maiwithu.maicraft.ability.machine.spi.RunRequest;
import org.maiwithu.maicraft.behavior.recipe.ShownIngredient;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 联动模组交来的投料与取货本事，登记表给它包的一层：模组停用后不再给动作，读状态如实说读不到；
 * 读写时碰到模组接口对不上也在这里收住，交出的动作同样包一层。
 */
final class CompatMachineFeeding implements MachineFeeding {

    private final CompatModule module;
    private final MachineFeeding feeding;

    CompatMachineFeeding(CompatModule module, MachineFeeding feeding) {
        this.module = module;
        this.feeding = feeding;
    }

    @Override public Optional<Action> feed(BlockPos at, FeedPlan.Entry item, Permissions permissions) {
        return act(() -> feeding.feed(at, item, permissions));
    }

    // 停用或接口对不上时不编现场摆法原因，交回空。
    @Override public Optional<String> whyCannotFeed(BlockPos at, String itemId) {
        return DegradedAnswer.ask(module, () -> feeding.whyCannotFeed(at, itemId), Optional.empty());
    }

    @Override public Optional<Action> take(BlockPos at, String itemId, int count, Permissions permissions) {
        return act(() -> feeding.take(at, itemId, count, permissions));
    }

    @Override public Optional<FullRun> fullRun(BlockPos at, RunRequest request, Permissions permissions) {
        return DegradedAnswer.ask(module, () -> feeding.fullRun(at, request, permissions), Optional.<FullRun>empty())
                .map(run -> CompatAction.fullRun(module, run));
    }

    @Override public FuelUse fuelUse(BlockPos at) {
        return DegradedAnswer.ask(module, () -> feeding.fuelUse(at), FuelUse.RECIPE);
    }

    @Override public Optional<IngredientFeed> ingredientFeed(BlockPos at, ShownIngredient ingredient, long amount) {
        return DegradedAnswer.ask(module, () -> feeding.ingredientFeed(at, ingredient, amount), Optional.empty());
    }

    // 停用或接口对不上时按标准的按配方投料给一份计划：冲突与腾空动作都为空，投料动作给不出。
    @Override public FeedPlan plan(BlockPos at, ShownRecipe recipe, int batches, Permissions permissions) {
        return DegradedAnswer.ask(module, () -> feeding.plan(at, recipe, batches, permissions),
                MachineFeeding.standard(null, at, recipe, batches));
    }


    // 要一个动作：给出的动作包一层，推进到一半停用时如实按不支持收场。
    private Optional<Action> act(Supplier<Optional<Action>> make) {
        return DegradedAnswer.ask(module, make, Optional.<Action>empty()).map(action -> CompatAction.of(module, action));
    }
}
