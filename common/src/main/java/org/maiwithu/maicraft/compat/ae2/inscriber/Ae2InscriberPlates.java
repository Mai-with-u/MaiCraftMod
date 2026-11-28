// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.inscriber;

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
 * 压印器配方的模板识别、投料件数与机内槽位冲突判断：纯函数，只看配方定义与同步过来的前三个工作格。
 *
 * <p>压印器配方分两种模式：压印模式（{@code mode=inscribe}）的顶、底压印模板压完不消耗，做几批都只要 1 张，
 * 若机器模板格里已经放着匹配的模板则 0 张要补；压制模式（{@code mode=press}）做处理器时顶、底印制板会随每批消耗，
 * 照常按批数乘算。无论哪种模式，顶、底格物料都排在中间输入格主料之前先放，否则压印器界面会因未配好模板拒收中间格。
 */
public final class Ae2InscriberPlates {

    private static final String INSCRIBER_ID = "ae2:inscriber";
    private static final String PRESS_MODE = "press";

    private Ae2InscriberPlates() {}

    /** 这格原料是不是压印器的顶、底槽物料（排在中间主料之前先放，让中间格通过槽位配方校验）。 */
    static boolean isTopOrBottom(ShownRecipe recipe, ShownIngredient ingredient) {
        if (onlyWorkstation(ingredient)) return false;
        Optional<ParsedRecipe> parsed = parse(recipe);
        if (parsed.isEmpty()) return false;
        ParsedRecipe rules = parsed.get();
        return (rules.top().matches(ingredient) || rules.bottom().matches(ingredient))
                && !rules.middle().matches(ingredient);
    }

    /** 这格原料是不是压完不消耗的压印模板（来自查看器的催化剂栏，或压印模式下的顶、底模板格）。 */
    static boolean isNonConsumedPlate(ShownRecipe recipe, ShownIngredient ingredient, boolean fromCatalysts) {
        if (onlyWorkstation(ingredient)) return false;
        if (fromCatalysts) return true;
        Optional<ParsedRecipe> parsed = parse(recipe);
        if (parsed.isEmpty() || parsed.get().consumesPlates()) return false;
        ParsedRecipe rules = parsed.get();
        return (rules.top().matches(ingredient) || rules.bottom().matches(ingredient))
                && !rules.middle().matches(ingredient);
    }

    /**
     * 压印器跑 {@code batches} 批这条配方时，这一格原料要从身上备齐并投进几件：
     * 不消耗模板在机内已有匹配模板时投 0 件，否则不论几批只投 1 件；消耗料照常乘批数。
     */
    static long feedCount(ShownRecipe recipe, ShownIngredient ingredient, int batches, boolean fromCatalysts,
            Optional<Ae2Inscribers.Inputs> currentSlots) {
        if (onlyWorkstation(ingredient)) return 0;
        if (isNonConsumedPlate(recipe, ingredient, fromCatalysts)) {
            boolean alreadyInside = currentSlots
                    .map(slots -> slots.plates().stream().anyMatch(plate -> matchesIngredient(plate, ingredient)))
                    .orElse(false);
            return alreadyInside ? 0 : 1;
        }
        return ingredient.amount() * batches;
    }

    /**
     * 压印器前三个投入工作格此刻放着的物品与当前配方冲不冲突：
     * 压印器允许顶、底模板格上下对调，空槽留给稍后投料也不算冲突；但若槽里占着别的配方留下的不兼容模板或异种原料，
     * 会直接导致槽位拒收或机器无法开工，当场点名槽内占用物说清原因。
     */
    static Optional<String> conflict(ShownRecipe recipe, Ae2Inscribers.Inputs slots) {
        if (slots == null || slots.occupied().isEmpty()) return Optional.empty();
        Ae2Inscribers.Slots four = new Ae2Inscribers.Slots(
                slots.top().isEmpty() ? null : new MachineState.Shown(slots.top(), 1),
                slots.bottom().isEmpty() ? null : new MachineState.Shown(slots.bottom(), 1),
                slots.middle().isEmpty() ? null : new MachineState.Shown(slots.middle(), 1),
                null);
        return conflict(recipe, 1, four);
    }

    /**
     * 压印器全部四个工作格（0 顶、1 底、2 中间、3 输出）与当前目标配方及批数冲不冲突：
     * 找出所有装着不兼容模板、残留半成品或阻塞输出格的槽位下标（0..3），供投料前自动开窗取回背包。
     */
    static List<Integer> conflictingSlotIndices(ShownRecipe recipe, int batches, Ae2Inscribers.Slots slots) {
        if (slots == null || slots.allShown().isEmpty()) return List.of();
        Optional<ParsedRecipe> parsed = parse(recipe);
        if (parsed.isEmpty()) return List.of();
        ParsedRecipe rules = parsed.get();
        String topId = slots.top() == null ? "" : slots.top().itemId();
        String bottomId = slots.bottom() == null ? "" : slots.bottom().itemId();
        String middleId = slots.middle() == null ? "" : slots.middle().itemId();

        List<Integer> directPlateConflicts = plateConflicts(topId, rules.top(), bottomId, rules.bottom(), recipe);
        List<Integer> flippedPlateConflicts = plateConflicts(topId, rules.bottom(), bottomId, rules.top(), recipe);
        // 压印器允许顶底模板上下对调：选需要取出的冲突格数更少的摆法，尽量保留机内已对得上的模板不动。
        List<Integer> conflicts = new ArrayList<>(
                directPlateConflicts.size() <= flippedPlateConflicts.size()
                        ? directPlateConflicts : flippedPlateConflicts);
        if (!middleId.isEmpty() && !slotFits(middleId, rules.middle(), recipe)) {
            conflicts.add(2);
        }
        // 输出格若占着与本配方产物不同的旧物品，或已有同种产物加上本趟产量超过单格 64 上限，也会卡住压印器不开工。
        if (slots.output() != null && outputBlocksRecipe(recipe, Math.max(1, batches), slots.output())) {
            conflicts.add(3);
        }
        return List.copyOf(conflicts);
    }

    /** 压印器四个工作格存在与目标配方冲突的占用物时，点名冲突槽位里的物品给出中文说明。 */
    static Optional<String> conflict(ShownRecipe recipe, int batches, Ae2Inscribers.Slots slots) {
        List<Integer> indices = conflictingSlotIndices(recipe, batches, slots);
        if (indices.isEmpty()) return Optional.empty();
        List<String> blocking = new ArrayList<>(indices.size());
        for (int index : indices) {
            MachineState.Shown shown = slots.slot(index);
            if (shown != null && !blocking.contains(shown.itemId())) {
                blocking.add(shown.itemId());
            }
        }
        return Optional.of("压印器里还放着 " + String.join("、", blocking) + "，与这次要做的配方不兼容");
    }

    private static List<Integer> plateConflicts(String topId, SlotRule topRule, String bottomId,
            SlotRule bottomRule, ShownRecipe recipe) {
        List<Integer> bad = new ArrayList<>(2);
        if (!topId.isEmpty() && !slotFits(topId, topRule, recipe)) bad.add(0);
        if (!bottomId.isEmpty() && !slotFits(bottomId, bottomRule, recipe)) bad.add(1);
        return bad;
    }

    private static boolean outputBlocksRecipe(ShownRecipe recipe, int batches, MachineState.Shown currentOutput) {
        Optional<ShownStack> expected = expectedItemResult(recipe);
        if (expected.isEmpty()) return false;
        if (!expected.get().id().equals(currentOutput.itemId())) {
            return true;
        }
        long perBatch = Math.max(1L, expected.get().amount());
        return currentOutput.count() + perBatch * batches > 64L;
    }

    private static Optional<ShownStack> expectedItemResult(ShownRecipe recipe) {
        for (ShownStack out : recipe.outputs()) {
            if (out.kind() == ShownStack.Kind.ITEM && !out.id().isEmpty()) {
                return Optional.of(out);
            }
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

    /** 物品 ID 本身是不是压印模板（未经过配方计数直接投料时的兜底识别）。 */
    public static boolean isPressItemId(String itemId) {
        return itemId != null && !ItemMatch.isTag(itemId) && itemId.endsWith("_press");
    }

    /** 一格原料写成备料与记账用的规格串（带 # 的标签或具体物品 ID）。 */
    static Optional<String> specifierOf(ShownIngredient ingredient) {
        if (ingredient.tag() != null) return Optional.of("#" + ingredient.tag());
        return ingredient.options().stream()
                .filter(stack -> stack.kind() == ShownStack.Kind.ITEM)
                .findFirst()
                .map(ShownStack::id);
    }

    /** 机内某一格的非空物品 ID 匹不匹配这一格原料要求。 */
    static boolean matchesIngredient(String itemId, ShownIngredient ingredient) {
        if (itemId == null || itemId.isEmpty()) return false;
        if (ingredient.accepts(itemId)) return true;
        if (ingredient.tag() != null) {
            Optional<ItemStack> stack = stackOf(itemId);
            if (stack.isPresent() && ItemMatch.matches(stack.get(), "#" + ingredient.tag())) return true;
        }
        return false;
    }

    private static boolean onlyWorkstation(ShownIngredient ingredient) {
        return ingredient.tag() == null
                && !ingredient.options().isEmpty()
                && ingredient.options().stream().allMatch(stack -> INSCRIBER_ID.equals(stack.id()));
    }

    /** 这条配方在这台压印器上怎么投，一次算清的结论：投料顺序、说明与机内冲突。 */
    public record MachinePlan(List<FeedPlan.Entry> entries, List<String> notes, Optional<String> conflict) {
    }

    /**
     * 一次解析这条配方在这台压印器怎么投：不消耗的压印模板（查看器列在催化剂栏或压印模式的顶底格）
     * 排在中间主料之前、做几批都只投 1 张，机内已有匹配模板的跳过并留一句说明；机内占着不兼容物的给冲突说明。
     * 只看配方定义与同步过来的四个工作格，不碰现场。
     */
    public static MachinePlan plan(ShownRecipe recipe, int batches, Optional<Ae2Inscribers.Slots> currentSlots) {
        Optional<Ae2Inscribers.Inputs> slots = currentSlots.map(Ae2Inscribers.Slots::toInputs);
        List<FeedPlan.Entry> first = new ArrayList<>();
        List<FeedPlan.Entry> main = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ShownIngredient catalyst : recipe.catalysts()) {
            Optional<WantedItem> wanted = MachineFeeding.wantedItemOf(catalyst);
            // 工作站图标（这一格写的就是压印器自己）不是投料，跳过。
            if (wanted.isEmpty() || wanted.get().specifier().equals(INSCRIBER_ID)) continue;
            seen.add(wanted.get().specifier());
            long count = feedCount(recipe, catalyst, batches, true, slots);
            if (count <= 0) {
                notes.add("机器里已有匹配的 " + wanted.get().specifier() + "，不用再放");
                continue;
            }
            first.add(new FeedPlan.Entry(wanted.get().specifier(),
                    (int) Math.min(Integer.MAX_VALUE, count),
                    isNonConsumedPlate(recipe, catalyst, true)));
        }
        for (ShownIngredient ingredient : recipe.inputs()) {
            Optional<WantedItem> wanted = MachineFeeding.wantedItemOf(ingredient);
            if (wanted.isEmpty()) {
                notes.add("配方还要机器自己要的东西（" + MachineFeeding.describe(ingredient)
                        + "），带不进机器，做不做得看机器里还有没有");
                continue;
            }
            String specifier = wanted.get().specifier();
            if (seen.contains(specifier)) continue;
            long count = feedCount(recipe, ingredient, batches, false, slots);
            if (count <= 0) {
                notes.add("机器里已有匹配的 " + specifier + "，不用再放");
                continue;
            }
            FeedPlan.Entry entry = new FeedPlan.Entry(specifier, (int) Math.min(Integer.MAX_VALUE, count),
                    isNonConsumedPlate(recipe, ingredient, false));
            if (isTopOrBottom(recipe, ingredient)) {
                first.add(entry);
            } else {
                main.add(entry);
            }
        }
        List<FeedPlan.Entry> entries = new ArrayList<>(first.size() + main.size());
        entries.addAll(first);
        entries.addAll(main);
        Optional<String> conflict = currentSlots
                .flatMap(slots2 -> conflict(recipe, batches, slots2));
        return new MachinePlan(List.copyOf(entries), List.copyOf(notes), conflict);
    }

    private static boolean slotFits(String actualItemId, SlotRule rule, ShownRecipe recipe) {
        if (actualItemId == null || actualItemId.isEmpty()) return true;
        if (rule.isEmpty()) return false;
        return rule.matchesActualItem(actualItemId, recipe);
    }

    private static Optional<ParsedRecipe> parse(ShownRecipe recipe) {
        if (recipe == null) return Optional.empty();
        JsonObject def = recipe.definition();
        if (def == null || !def.has("ingredients") || !def.get("ingredients").isJsonObject()) {
            return Optional.empty();
        }
        JsonObject ingredients = def.getAsJsonObject("ingredients");
        String mode = def.has("mode") && def.get("mode").isJsonPrimitive()
                ? def.get("mode").getAsString() : "inscribe";
        return Optional.of(new ParsedRecipe(
                PRESS_MODE.equals(mode),
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

    /** 配方定义里单个槽位的允许项集合（具体物品 ID 与不带 # 的标签名）。 */
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
            if (obj.has("item") && obj.get("item").isJsonPrimitive()) {
                items.add(obj.get("item").getAsString());
            }
            if (obj.has("tag") && obj.get("tag").isJsonPrimitive()) {
                String tag = obj.get("tag").getAsString();
                tags.add(tag.startsWith("#") ? tag.substring(1) : tag);
            }
        }

        boolean isEmpty() {
            return items.isEmpty() && tags.isEmpty();
        }

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
            for (ShownIngredient shown : concat(recipe.inputs(), recipe.catalysts())) {
                if (shown.tag() != null && tags.contains(shown.tag()) && shown.accepts(actualItemId)) {
                    return true;
                }
            }
            Optional<ItemStack> stack = stackOf(actualItemId);
            if (stack.isPresent()) {
                return tags.stream().anyMatch(tag -> ItemMatch.matches(stack.get(), "#" + tag));
            }
            // 离线环境未注册该模组物品且配方未附带标签展开候选时，不凭未知标签误判为冲突。
            return true;
        }

        private static List<ShownIngredient> concat(List<ShownIngredient> first, List<ShownIngredient> second) {
            if (second.isEmpty()) return first;
            ArrayList<ShownIngredient> all = new ArrayList<>(first.size() + second.size());
            all.addAll(first);
            all.addAll(second);
            return all;
        }
    }
}
