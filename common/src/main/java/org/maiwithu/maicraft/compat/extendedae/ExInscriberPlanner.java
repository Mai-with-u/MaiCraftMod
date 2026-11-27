// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.extendedae;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.maiwithu.maicraft.ability.machine.spi.FeedPlan;
import org.maiwithu.maicraft.ability.machine.spi.MachineFeeding;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.recipe.ShownIngredient;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.behavior.recipe.ShownStack;
import org.maiwithu.maicraft.game.world.ItemMatch;

/**
 * 扩展压印器 4 页择优调度与配方投料纯判断：
 * <ol>
 * <li>优先选已有匹配不消耗模板且无异种冲突的空闲页；</li>
 * <li>其次选全空页；</li>
 * <li>最后选冲突槽位最少的页并在计划里标明需要腾空的槽位下标。</li>
 * </ol>
 * 同时识别顶底不消耗模板，保证顶底物料排在中间主料前先投且机内已有时不重复备料。
 */
final class ExInscriberPlanner {

    private static final String PRESS_MODE = "press";
    private static final Set<String> WORKSTATION_IDS = Set.of("ae2:inscriber", "extendedae:ex_inscriber");

    /** 一次 4 页调度与投料计划的纯计算结果。 */
    record PagePlan(int targetPage, List<FeedPlan.Entry> entries, List<String> notes,
            List<Integer> conflictingSlots, Optional<String> conflict) {}

    private ExInscriberPlanner() {}

    /** 按客户端同步的 4 页快照择优选定目标页并生成投料条目与冲突腾空清单。 */
    static PagePlan plan(ShownRecipe recipe, int batches, Optional<ExtendedAeReads.ExInscriberView> viewOpt) {
        int safeBatches = Math.max(1, batches);
        List<ExtendedAeReads.InscriberPageView> pages = viewOpt
                .map(ExtendedAeReads.ExInscriberView::pages)
                .filter(list -> !list.isEmpty())
                .orElseGet(ExInscriberPlanner::emptyFourPages);
        int chosenPage = choosePage(recipe, safeBatches, pages);
        ExtendedAeReads.InscriberPageView pageView = pageOf(pages, chosenPage);
        List<Integer> conflicts = conflictingSlotIndices(recipe, safeBatches, pageView);
        List<FeedPlan.Entry> first = new ArrayList<>();
        List<FeedPlan.Entry> main = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        notes.add("扩展压印器择优选定第 " + chosenPage + " 页执行");
        Set<String> seen = new LinkedHashSet<>();

        for (ShownIngredient catalyst : recipe.catalysts()) {
            Optional<WantedItem> wanted = MachineFeeding.wantedItemOf(catalyst);
            if (wanted.isEmpty() || WORKSTATION_IDS.contains(wanted.get().specifier())) continue;
            String spec = wanted.get().specifier();
            seen.add(spec);
            long count = feedCount(recipe, catalyst, safeBatches, true, pageView);
            if (count <= 0) {
                notes.add("第 " + chosenPage + " 页已有匹配的 " + spec + "，不用再放");
                continue;
            }
            first.add(new FeedPlan.Entry(spec, (int) Math.min(Integer.MAX_VALUE, count),
                    isNonConsumedPlate(recipe, catalyst, true)));
        }
        for (ShownIngredient ingredient : recipe.inputs()) {
            Optional<WantedItem> wanted = MachineFeeding.wantedItemOf(ingredient);
            if (wanted.isEmpty()) {
                notes.add("配方还要机器自己要的东西（" + MachineFeeding.describe(ingredient) + "），带不进扩展压印器");
                continue;
            }
            String spec = wanted.get().specifier();
            if (seen.contains(spec)) continue;
            long count = feedCount(recipe, ingredient, safeBatches, false, pageView);
            if (count <= 0) {
                notes.add("第 " + chosenPage + " 页已有匹配的 " + spec + "，不用再放");
                continue;
            }
            FeedPlan.Entry entry = new FeedPlan.Entry(spec, (int) Math.min(Integer.MAX_VALUE, count),
                    isNonConsumedPlate(recipe, ingredient, false));
            if (isTopOrBottom(recipe, ingredient)) first.add(entry);
            else main.add(entry);
        }
        List<FeedPlan.Entry> entries = new ArrayList<>(first.size() + main.size());
        entries.addAll(first);
        entries.addAll(main);
        Optional<String> conflict = describeConflict(chosenPage, conflicts, pageView);
        return new PagePlan(chosenPage, List.copyOf(entries), List.copyOf(notes), List.copyOf(conflicts), conflict);
    }

    /**
     * 从 4 页里择优选一页：
     * 1. 有匹配不消耗模板且零冲突页（模板命中越多越优先）；
     * 2. 全空页；
     * 3. 冲突槽位最少的页。
     */
    static int choosePage(ShownRecipe recipe, int batches, List<ExtendedAeReads.InscriberPageView> pages) {
        int bestPage = 0;
        int bestRank = Integer.MAX_VALUE;
        int bestScore = Integer.MAX_VALUE;
        for (int p = 0; p < Math.min(4, pages.size()); p++) {
            ExtendedAeReads.InscriberPageView page = pages.get(p);
            List<Integer> conflicts = conflictingSlotIndices(recipe, batches, page);
            int matchedPlates = countMatchedNonConsumedPlates(recipe, page);
            int rank;
            int score;
            if (conflicts.isEmpty() && matchedPlates > 0) {
                // 第一优先：已有匹配不消耗模板且无异种冲突。
                rank = 0;
                score = -matchedPlates;
            } else if (page.empty()) {
                // 第二优先：全空页。
                rank = 1;
                score = p;
            } else if (conflicts.isEmpty()) {
                // 第三优先：无冲突非空页（例如已有同种原料）。
                rank = 2;
                score = p;
            } else {
                // 最后：冲突槽位最少的页，供腾空后使用。
                rank = 3;
                score = conflicts.size() * 10 + p;
            }
            if (rank < bestRank || (rank == bestRank && score < bestScore)) {
                bestRank = rank;
                bestScore = score;
                bestPage = page.page();
            }
        }
        return bestPage;
    }

    /** 找出单页 4 个工作格（0 顶、1 底、2 中间、3 输出）中与目标配方冲突的槽位下标。 */
    static List<Integer> conflictingSlotIndices(ShownRecipe recipe, int batches,
            ExtendedAeReads.InscriberPageView page) {
        if (page == null || page.empty()) return List.of();
        Optional<ParsedRecipe> parsed = parse(recipe);
        if (parsed.isEmpty()) return List.of();
        ParsedRecipe rules = parsed.get();
        String topId = page.top() == null ? "" : page.top().itemId();
        String bottomId = page.bottom() == null ? "" : page.bottom().itemId();
        String middleId = page.middle() == null ? "" : page.middle().itemId();

        List<Integer> direct = plateConflicts(topId, rules.top(), bottomId, rules.bottom(), recipe);
        List<Integer> flipped = plateConflicts(topId, rules.bottom(), bottomId, rules.top(), recipe);
        List<Integer> conflicts = new ArrayList<>(direct.size() <= flipped.size() ? direct : flipped);
        if (!middleId.isEmpty() && !slotFits(middleId, rules.middle(), recipe)) {
            conflicts.add(2);
        }
        if (page.output() != null && outputBlocksRecipe(recipe, Math.max(1, batches), page.output())) {
            conflicts.add(3);
        }
        return List.copyOf(conflicts);
    }

    static boolean isNonConsumedPlate(ShownRecipe recipe, ShownIngredient ingredient, boolean fromCatalysts) {
        if (onlyWorkstation(ingredient)) return false;
        if (fromCatalysts) return true;
        Optional<ParsedRecipe> parsed = parse(recipe);
        if (parsed.isEmpty() || parsed.get().consumesPlates()) return false;
        ParsedRecipe rules = parsed.get();
        return (rules.top().matches(ingredient) || rules.bottom().matches(ingredient))
                && !rules.middle().matches(ingredient);
    }

    static boolean isTopOrBottom(ShownRecipe recipe, ShownIngredient ingredient) {
        if (onlyWorkstation(ingredient)) return false;
        Optional<ParsedRecipe> parsed = parse(recipe);
        if (parsed.isEmpty()) return false;
        ParsedRecipe rules = parsed.get();
        return (rules.top().matches(ingredient) || rules.bottom().matches(ingredient))
                && !rules.middle().matches(ingredient);
    }

    static boolean isPressItemId(String itemId) {
        return itemId != null && !ItemMatch.isTag(itemId) && itemId.endsWith("_press");
    }

    private static long feedCount(ShownRecipe recipe, ShownIngredient ingredient, int batches, boolean fromCatalysts,
            ExtendedAeReads.InscriberPageView page) {
        if (onlyWorkstation(ingredient)) return 0;
        if (isNonConsumedPlate(recipe, ingredient, fromCatalysts)) {
            boolean alreadyInside = matchesPlateInPage(page, ingredient);
            return alreadyInside ? 0 : 1;
        }
        return (long) ingredient.amount() * batches;
    }

    private static int countMatchedNonConsumedPlates(ShownRecipe recipe, ExtendedAeReads.InscriberPageView page) {
        int matched = 0;
        for (ShownIngredient catalyst : recipe.catalysts()) {
            if (isNonConsumedPlate(recipe, catalyst, true) && matchesPlateInPage(page, catalyst)) matched++;
        }
        for (ShownIngredient input : recipe.inputs()) {
            if (isNonConsumedPlate(recipe, input, false) && matchesPlateInPage(page, input)) matched++;
        }
        return matched;
    }

    private static boolean matchesPlateInPage(ExtendedAeReads.InscriberPageView page, ShownIngredient ingredient) {
        if (page == null) return false;
        return (page.top() != null && matchesIngredient(page.top().itemId(), ingredient))
                || (page.bottom() != null && matchesIngredient(page.bottom().itemId(), ingredient));
    }

    private static boolean matchesIngredient(String itemId, ShownIngredient ingredient) {
        if (itemId == null || itemId.isEmpty()) return false;
        if (ingredient.accepts(itemId)) return true;
        if (ingredient.tag() != null) {
            Optional<ItemStack> stack = stackOf(itemId);
            if (stack.isPresent() && ItemMatch.matches(stack.get(), "#" + ingredient.tag())) return true;
        }
        return false;
    }

    private static Optional<String> describeConflict(int page, List<Integer> indices,
            ExtendedAeReads.InscriberPageView pageView) {
        if (indices.isEmpty()) return Optional.empty();
        List<String> blocking = new ArrayList<>();
        for (int index : indices) {
            MachineState.Shown shown = pageView.slot(index);
            if (shown != null && !blocking.contains(shown.itemId())) blocking.add(shown.itemId());
        }
        return Optional.of("扩展压印器第 " + page + " 页还放着 " + String.join("、", blocking)
                + "，与这次要做的配方不兼容");
    }

    private static List<Integer> plateConflicts(String topId, SlotRule topRule, String bottomId,
            SlotRule bottomRule, ShownRecipe recipe) {
        List<Integer> bad = new ArrayList<>(2);
        if (!topId.isEmpty() && !slotFits(topId, topRule, recipe)) bad.add(0);
        if (!bottomId.isEmpty() && !slotFits(bottomId, bottomRule, recipe)) bad.add(1);
        return bad;
    }

    private static boolean outputBlocksRecipe(ShownRecipe recipe, int batches, MachineState.Shown output) {
        Optional<ShownStack> expected = expectedItemResult(recipe);
        if (expected.isEmpty()) return false;
        if (!expected.get().id().equals(output.itemId())) return true;
        return output.count() + Math.max(1L, expected.get().amount()) * batches > 64L;
    }

    private static Optional<ShownStack> expectedItemResult(ShownRecipe recipe) {
        for (ShownStack out : recipe.outputs()) {
            if (out.kind() == ShownStack.Kind.ITEM && !out.id().isEmpty()) return Optional.of(out);
        }
        JsonObject def = recipe.definition();
        if (def != null && def.has("result") && def.get("result").isJsonObject()) {
            JsonObject res = def.getAsJsonObject("result");
            if (res.has("id") && res.get("id").isJsonPrimitive()) {
                int count = res.has("count") && res.get("count").isJsonPrimitive() ? res.get("count").getAsInt() : 1;
                return Optional.of(ShownStack.item(res.get("id").getAsString(), res.get("id").getAsString(), count));
            }
        }
        return Optional.empty();
    }

    private static boolean onlyWorkstation(ShownIngredient ingredient) {
        return ingredient.tag() == null && !ingredient.options().isEmpty()
                && ingredient.options().stream().allMatch(s -> WORKSTATION_IDS.contains(s.id()));
    }

    private static boolean slotFits(String itemId, SlotRule rule, ShownRecipe recipe) {
        if (itemId == null || itemId.isEmpty()) return true;
        if (rule.isEmpty()) return false;
        return rule.matchesActualItem(itemId, recipe);
    }

    private static ExtendedAeReads.InscriberPageView pageOf(List<ExtendedAeReads.InscriberPageView> pages, int target) {
        for (ExtendedAeReads.InscriberPageView page : pages) {
            if (page.page() == target) return page;
        }
        return new ExtendedAeReads.InscriberPageView(target, null, null, null, null);
    }

    private static List<ExtendedAeReads.InscriberPageView> emptyFourPages() {
        List<ExtendedAeReads.InscriberPageView> list = new ArrayList<>(4);
        for (int p = 0; p < 4; p++) list.add(new ExtendedAeReads.InscriberPageView(p, null, null, null, null));
        return list;
    }

    private static Optional<ParsedRecipe> parse(ShownRecipe recipe) {
        if (recipe == null || recipe.definition() == null) return Optional.empty();
        JsonObject def = recipe.definition();
        if (!def.has("ingredients") || !def.get("ingredients").isJsonObject()) return Optional.empty();
        JsonObject ingredients = def.getAsJsonObject("ingredients");
        String mode = def.has("mode") && def.get("mode").isJsonPrimitive()
                ? def.get("mode").getAsString() : "inscribe";
        return Optional.of(new ParsedRecipe(PRESS_MODE.equals(mode),
                SlotRule.of(ingredients.get("top")),
                SlotRule.of(ingredients.get("bottom")),
                SlotRule.of(ingredients.get("middle"))));
    }

    private static Optional<ItemStack> stackOf(String itemId) {
        ResourceLocation key = ResourceLocation.tryParse(itemId);
        if (key == null) return Optional.empty();
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(key);
        return item.filter(found -> found != Items.AIR).map(ItemStack::new);
    }

    private record ParsedRecipe(boolean consumesPlates, SlotRule top, SlotRule bottom, SlotRule middle) {}

    private record SlotRule(Set<String> items, Set<String> tags) {
        static SlotRule of(JsonElement element) {
            if (element == null || element.isJsonNull()) return new SlotRule(Set.of(), Set.of());
            Set<String> items = new LinkedHashSet<>();
            Set<String> tags = new LinkedHashSet<>();
            collect(element, items, tags);
            return new SlotRule(Set.copyOf(items), Set.copyOf(tags));
        }

        private static void collect(JsonElement element, Set<String> items, Set<String> tags) {
            if (element.isJsonArray()) {
                for (JsonElement child : element.getAsJsonArray()) collect(child, items, tags);
                return;
            }
            if (!element.isJsonObject()) return;
            JsonObject obj = element.getAsJsonObject();
            if (obj.has("item") && obj.get("item").isJsonPrimitive()) items.add(obj.get("item").getAsString());
            if (obj.has("tag") && obj.get("tag").isJsonPrimitive()) {
                String tag = obj.get("tag").getAsString();
                tags.add(tag.startsWith("#") ? tag.substring(1) : tag);
            }
        }

        boolean isEmpty() { return items.isEmpty() && tags.isEmpty(); }

        boolean matches(ShownIngredient ingredient) {
            if (isEmpty()) return false;
            if (ingredient.tag() != null && tags.contains(ingredient.tag())) return true;
            for (ShownStack option : ingredient.options()) {
                if (option.kind() != ShownStack.Kind.ITEM) continue;
                if (items.contains(option.id())) return true;
                Optional<ItemStack> stack = stackOf(option.id());
                if (stack.isPresent() && tags.stream().anyMatch(tag -> ItemMatch.matches(stack.get(), "#" + tag))) {
                    return true;
                }
            }
            return false;
        }

        boolean matchesActualItem(String actualItemId, ShownRecipe recipe) {
            if (items.contains(actualItemId)) return true;
            if (tags.isEmpty()) return false;
            for (ShownIngredient shown : recipe.inputs()) {
                if (shown.tag() != null && tags.contains(shown.tag()) && shown.accepts(actualItemId)) return true;
            }
            for (ShownIngredient shown : recipe.catalysts()) {
                if (shown.tag() != null && tags.contains(shown.tag()) && shown.accepts(actualItemId)) return true;
            }
            Optional<ItemStack> stack = stackOf(actualItemId);
            return stack.map(s -> tags.stream().anyMatch(t -> ItemMatch.matches(s, "#" + t))).orElse(true);
        }
    }
}
