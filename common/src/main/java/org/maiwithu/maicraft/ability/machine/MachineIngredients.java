// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.machine.spi.IngredientFeed;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.recipe.ShownIngredient;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.behavior.recipe.ShownStack;

/**
 * 机器配方的批次换算与投料排队：按目标件数折算要做几批，再问机器类型每格原料实际要投几件、谁先投。
 *
 * <p>化学换料排在最前（储罐先有灌注物，主料一放就开工）；需要先把模板或上下印制板放好的机器把那些格排在主料前；
 * 机器里原本就放着匹配模板、本趟无需再补的记一句说明后跳过备料与投料。
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

    /** 把一条配方的催化剂（若机器要求投入）与输入排成备料与投料顺序，顺带把换料与跳过原因写进说明。 */
    static List<MachineRunDetails.Fed> queue(MachineType type, BlockPos cell, ShownRecipe chosen, int batches,
            List<String> notes) {
        List<MachineRunDetails.Fed> converted = new ArrayList<>();
        List<MachineRunDetails.Fed> firstItems = new ArrayList<>();
        List<MachineRunDetails.Fed> mainItems = new ArrayList<>();
        Set<String> seenCatalysts = new HashSet<>();
        if (type.feedsCatalysts()) {
            for (ShownIngredient catalyst : chosen.catalysts()) {
                Optional<WantedItem> wanted = wantedItemOf(catalyst);
                if (wanted.isEmpty() || wanted.get().specifier().equals(type.id())) continue;
                seenCatalysts.add(wanted.get().specifier());
                long count = type.feedCount(cell, chosen, catalyst, batches, true);
                if (count <= 0) {
                    notes.add("机器里已有匹配的 " + wanted.get().specifier() + "，不用再放");
                    continue;
                }
                firstItems.add(new MachineRunDetails.Fed(wanted.get().specifier(), clampCount(count)));
            }
        }
        for (ShownIngredient ingredient : chosen.inputs()) {
            Optional<WantedItem> wanted = wantedItemOf(ingredient);
            if (wanted.isEmpty()) {
                Optional<IngredientFeed> byItem = type.ingredientFeed(cell, ingredient, ingredient.amount() * batches);
                if (byItem.isPresent()) {
                    notes.add(byItem.get().note());
                    converted.add(new MachineRunDetails.Fed(byItem.get().itemId(), byItem.get().count()));
                } else {
                    notes.add("配方还要机器自己要的东西（" + describe(ingredient)
                            + "），带不进机器，做不做得看机器里还有没有");
                }
                continue;
            }
            String specifier = wanted.get().specifier();
            if (seenCatalysts.contains(specifier)) continue;
            long count = type.feedCount(cell, chosen, ingredient, batches, false);
            if (count <= 0) {
                notes.add("机器里已有匹配的 " + specifier + "，不用再放");
                continue;
            }
            MachineRunDetails.Fed entry = new MachineRunDetails.Fed(specifier, clampCount(count));
            if (type.feedsFirst(chosen, ingredient)) {
                firstItems.add(entry);
            } else {
                mainItems.add(entry);
            }
        }
        List<MachineRunDetails.Fed> ordered = new ArrayList<>(
                converted.size() + firstItems.size() + mainItems.size());
        ordered.addAll(converted);
        ordered.addAll(firstItems);
        ordered.addAll(mainItems);
        return ordered;
    }

    private static Optional<WantedItem> wantedItemOf(ShownIngredient ingredient) {
        if (ingredient.tag() != null) {
            return Optional.of(WantedItem.ofTag(ingredient.tag()));
        }
        return ingredient.options().stream()
                .filter(option -> option.kind() == ShownStack.Kind.ITEM)
                .findFirst()
                .map(item -> WantedItem.ofItem(item.id()));
    }

    private static int clampCount(long count) {
        return (int) Math.min(Integer.MAX_VALUE, count);
    }

    private static String describe(ShownIngredient ingredient) {
        if (ingredient.tag() != null) {
            return ingredient.tag();
        }
        return ingredient.options().stream().findFirst().map(ShownStack::id).orElse("未知的东西");
    }
}
