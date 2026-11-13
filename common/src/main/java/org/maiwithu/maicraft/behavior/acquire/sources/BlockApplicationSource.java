// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import org.maiwithu.maicraft.behavior.acquire.engine.CarriedItems;
import org.maiwithu.maicraft.behavior.acquire.engine.OffhandContents;
import org.maiwithu.maicraft.behavior.acquire.engine.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.BlockApplication;
import org.maiwithu.maicraft.behavior.acquire.spi.BlockApplications;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.spi.StepwiseActions;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 手工涂抹来源（例如机械动力的 create:item_application）：要安山机壳、铜机壳、黄铜机壳、列车机壳、捆绑纸板箱等时，
 * 备好基底方块与手持涂抹材料，在身边安全空位放下一格基底、拿材料右键涂成目标方块，当场挖回进包。
 *
 * <p>基底支持多选（例如 #c:stripped_logs 展开的二十余种去皮木料）：身上已有的先用；身上没有时向取物引擎比价，
 * 自动挑中身边有对应原木、能经斧头去皮做出来的那一种去皮木料，一条龙完成"原木去皮 → 涂成机壳 → 挖回"。
 * 配方标了保留手持物（keep_held_item）时手持物只需备一件且不消耗；有耐久的手持物按耐久折算件数。
 */
public final class BlockApplicationSource implements ItemSource {

    /** 一趟最多连做几轮，做完回引擎清点，还缺再来下一趟。 */
    static final int MAX_BATCH_PER_RUN = 16;
    private static final String HINT_SEP = "|";

    private final BlockApplications applications;
    private final Supplier<Optional<List<BlockApplication>>> recipeReads;
    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final ReadsItemTags tags;
    private final ItemNeeds needs;
    private final PermissionCheck permission;
    private final InWorldBlockTransform.Seams seams;
    private final ToIntFunction<String> heldUsesPerItem;

    private Supplier<List<ItemRequest>> outerWanted = List::of;
    private boolean pricingIngredients;

    public BlockApplicationSource(BlockApplications applications, Supplier<Level> levels,
            BackpackView backpack, OffhandContents offhand, ReadsItemTags tags, ItemNeeds needs,
            PermissionCheck permission, InWorldBlockTransform.Seams seams) {
        this(applications, () -> {
            Level level = levels.get();
            return level == null ? Optional.empty() : Optional.of(applications.recipes(level));
        }, backpack, offhand, tags, needs, permission, seams, BlockApplicationSource::registryDurabilityUses);
    }

    public BlockApplicationSource(BlockApplications applications,
            Supplier<Optional<List<BlockApplication>>> recipeReads, BackpackView backpack, OffhandContents offhand,
            ReadsItemTags tags, ItemNeeds needs, PermissionCheck permission, InWorldBlockTransform.Seams seams,
            ToIntFunction<String> heldUsesPerItem) {
        this.applications = Objects.requireNonNull(applications, "applications");
        this.recipeReads = Objects.requireNonNull(recipeReads, "recipeReads");
        this.backpack = Objects.requireNonNull(backpack, "backpack");
        this.offhand = offhand;
        this.tags = Objects.requireNonNull(tags, "tags");
        this.needs = Objects.requireNonNull(needs, "needs");
        this.permission = Objects.requireNonNull(permission, "permission");
        this.seams = seams;
        this.heldUsesPerItem = Objects.requireNonNull(heldUsesPerItem, "heldUsesPerItem");
    }

    /** 接上引擎当前推进的请求链，防止把外层正要留着的基底或消耗材料拿去涂成别的。 */
    public void watchChain(Supplier<List<ItemRequest>> inProgress, Supplier<List<ItemRequest>> outer) {
        Objects.requireNonNull(inProgress, "inProgress");
        this.outerWanted = Objects.requireNonNull(outer, "outer");
    }

    @Override
    public String describe() {
        return "手工涂抹";
    }

    @Override
    public AcquireVia via() {
        return applications.via();
    }

    @Override
    public SourceQuote quote(ItemRequest request, SourceContext context) {
        if (request.wanted().potion() != null) {
            return new SourceQuote.Unavailable(describe(), "手工涂抹做不出药水");
        }
        Optional<List<BlockApplication>> recipes = recipeReads.get();
        if (recipes.isEmpty()) {
            return new SourceQuote.Unavailable(describe(), "角色不在世界里");
        }
        Optional<ChosenPlan> chosen = choosePlan(recipes.get(), request, context);
        if (chosen.isEmpty()) {
            return new SourceQuote.Unavailable(describe(), "没有手工涂抹出 " + request.wanted().specifier() + " 的配方");
        }
        ChosenPlan plan = chosen.get();
        Optional<Problem> blocked = permission.blockAllowed(context.permissions(),
                PermissionCheck.WorldAction.PLACE_BLOCK, context.characterAt(), plan.baseItem());
        if (blocked.isPresent()) {
            return new SourceQuote.NeedsApproval(describe(), blocked.get());
        }
        int totalMissing = plan.baseShort() + plan.heldShort();
        double shortfall = totalMissing * RecipeSource.SHORTFALL_BLOCKS_PER_ITEM;
        int actions = plan.rounds() * 3 + 2;
        String risk = "在身边放下 " + plan.rounds() + " 块 " + plan.baseItem() + "，拿 " + plan.heldItem()
                + " 右键涂成 " + plan.recipe().resultId() + " 再挖回"
                + (totalMissing > 0 ? "（缺的基底或涂抹物先去备）" : "");
        return new SourceQuote.Offer(describe(), plan.rounds() * plan.recipe().resultCount(),
                new AcquisitionCost(0, actions, shortfall), risk, encodeHint(plan));
    }

    @Override
    public List<ItemRequest> missingIngredients(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        Optional<List<BlockApplication>> recipes = recipeReads.get();
        if (recipes.isEmpty()) return List.of();
        Optional<ChosenPlan> resolved = decodeHint(recipes.get(), offer.hint(), request.count());
        if (resolved.isEmpty()) return List.of();
        ChosenPlan plan = resolved.get();
        List<ItemRequest> missing = new ArrayList<>();
        if (plan.heldShort() > 0) {
            missing.add(new ItemRequest(WantedItem.ofItem(plan.heldItem()), plan.heldShort(),
                    "手工涂抹做 " + plan.recipe().resultId(), request.prefer()));
        }
        if (plan.baseShort() > 0) {
            missing.add(new ItemRequest(WantedItem.ofItem(plan.baseItem()), plan.baseShort(),
                    "手工涂抹做 " + plan.recipe().resultId() + " 的基底", request.prefer()));
        }
        return List.copyOf(missing);
    }

    @Override
    public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        if (seams == null) return Optional.empty();
        Optional<List<BlockApplication>> recipes = recipeReads.get();
        if (recipes.isEmpty()) return Optional.empty();
        Optional<ChosenPlan> resolved = decodeHint(recipes.get(), offer.hint(), request.count());
        if (resolved.isEmpty()) return Optional.empty();
        ChosenPlan plan = resolved.get();
        if (blockedByOuter(plan.baseItem())
                || (!plan.recipe().keepHeldItem() && blockedByOuter(plan.heldItem()))) {
            return Optional.empty();
        }
        if (permission.blockAllowed(context.permissions(), PermissionCheck.WorldAction.PLACE_BLOCK,
                context.characterAt(), plan.baseItem()).isPresent()) {
            return Optional.empty();
        }
        int batchRounds = Math.min(plan.rounds(), MAX_BATCH_PER_RUN);
        Permissions permissions = context.permissions();
        Action prepareHeld = new DeferredStep("准备涂抹用的 " + plan.heldItem(), () -> {
            int neededHeld = heldItemsNeeded(plan.recipe(), plan.heldItem(), batchRounds);
            int shortHeld = neededHeld - carried(plan.heldItem());
            return shortHeld <= 0 ? Optional.empty()
                    : Optional.of(needs.actionFor(
                            new ItemRequest(WantedItem.ofItem(plan.heldItem()), shortHeld,
                                    "手工涂抹做 " + plan.recipe().resultId(), request.prefer()),
                            permissions, context.records()));
        });
        Action prepareBase = new DeferredStep("准备涂抹基底 " + plan.baseItem(), () -> {
            int shortBase = batchRounds - carried(plan.baseItem());
            return shortBase <= 0 ? Optional.empty()
                    : Optional.of(needs.actionFor(
                            new ItemRequest(WantedItem.ofItem(plan.baseItem()), shortBase,
                                    "手工涂抹做 " + plan.recipe().resultId() + " 的基底", request.prefer()),
                            permissions, context.records()));
        });
        Action apply = new DeferredStep("摆下 " + plan.baseItem() + " 涂成 " + plan.recipe().resultId(), () -> {
            int doable = doableRounds(plan.recipe(), plan.baseItem(), plan.heldItem(), batchRounds);
            return Optional.of(new InWorldBlockTransform(plan.baseItem(), WantedItem.ofItem(plan.heldItem()),
                    plan.recipe().resultId(), !plan.recipe().placesResultBlock(),
                    doable > 0 ? doable : batchRounds, permissions, context.records(), seams));
        });
        return Optional.of(new StepwiseActions(
                "手工涂抹出 " + plan.recipe().resultId(), prepareHeld, prepareBase, apply));
    }

    private Optional<ChosenPlan> choosePlan(List<BlockApplication> recipes, ItemRequest request,
            SourceContext context) {
        List<ChosenPlan> plans = new ArrayList<>();
        Set<String> prefer = Set.copyOf(request.prefer());
        boolean wasPricing = pricingIngredients;
        pricingIngredients = true;
        try {
            for (BlockApplication recipe : recipes) {
                if (!request.wanted().matches(recipe.resultId(), tags.tagsOf(recipe.resultId()))) continue;
                int rounds = (request.count() + recipe.resultCount() - 1) / recipe.resultCount();
                OptionPick base = bestOption(recipe.baseItems(), rounds, true, request.wanted(), prefer,
                        context.permissions(), wasPricing);
                if (base == null) continue;
                OptionPick held = bestHeldOption(recipe, rounds, request.wanted(), prefer,
                        context.permissions(), wasPricing);
                if (held == null) continue;
                plans.add(new ChosenPlan(recipe, base.itemId(), held.itemId(), rounds,
                        base.shortfallCount(), held.shortfallCount(),
                        base.preferred() || held.preferred(),
                        base.fetchCost() + held.fetchCost()));
            }
        } finally {
            pricingIngredients = wasPricing;
        }
        plans.sort(Comparator.comparingInt((ChosenPlan p) -> p.baseShort() + p.heldShort())
                .thenComparing(p -> !p.preferred())
                .thenComparingDouble(ChosenPlan::fetchCost)
                .thenComparing(Comparator.comparingInt((ChosenPlan p) -> p.recipe().resultCount()).reversed()));
        return plans.isEmpty() ? Optional.empty() : Optional.of(plans.getFirst());
    }

    private OptionPick bestOption(List<String> candidates, int needed, boolean consumed, WantedItem target,
            Set<String> prefer, Permissions permissions, boolean skipRemotePricing) {
        List<OptionPick> picks = new ArrayList<>();
        for (String item : candidates) {
            if (consumed && (target.matches(item, tags.tagsOf(item)) || blockedByOuter(item))) continue;
            int have = carried(item);
            int shortCount = Math.max(0, needed - have);
            boolean pref = prefers(item, prefer);
            double cost = shortCount == 0 || skipRemotePricing ? 0
                    : needs.cheapestCost(new ItemRequest(WantedItem.ofItem(item), shortCount, "挑手工涂抹材料"),
                            permissions, true).orElse(Double.MAX_VALUE);
            picks.add(new OptionPick(item, have, shortCount, pref, cost));
        }
        picks.sort(Comparator.comparingInt((OptionPick o) -> o.shortfallCount == 0 ? 0 : 1)
                .thenComparing(o -> !o.preferred)
                .thenComparingDouble(OptionPick::fetchCost)
                .thenComparingInt(OptionPick::shortfallCount)
                .thenComparing(Comparator.comparingInt(OptionPick::carried).reversed()));
        return picks.isEmpty() ? null : picks.getFirst();
    }

    private OptionPick bestHeldOption(BlockApplication recipe, int rounds, WantedItem target, Set<String> prefer,
            Permissions permissions, boolean skipRemotePricing) {
        List<OptionPick> picks = new ArrayList<>();
        for (String item : recipe.heldItems()) {
            if (!recipe.keepHeldItem() && (target.matches(item, tags.tagsOf(item)) || blockedByOuter(item))) {
                continue;
            }
            int needed = heldItemsNeeded(recipe, item, rounds);
            int have = carried(item);
            int shortCount = Math.max(0, needed - have);
            boolean pref = prefers(item, prefer);
            double cost = shortCount == 0 || skipRemotePricing ? 0
                    : needs.cheapestCost(new ItemRequest(WantedItem.ofItem(item), shortCount, "挑手工涂抹手持物"),
                            permissions, true).orElse(Double.MAX_VALUE);
            picks.add(new OptionPick(item, have, shortCount, pref, cost));
        }
        picks.sort(Comparator.comparingInt((OptionPick o) -> o.shortfallCount == 0 ? 0 : 1)
                .thenComparing(o -> !o.preferred)
                .thenComparingDouble(OptionPick::fetchCost)
                .thenComparingInt(OptionPick::shortfallCount)
                .thenComparing(Comparator.comparingInt(OptionPick::carried).reversed()));
        return picks.isEmpty() ? null : picks.getFirst();
    }

    private int heldItemsNeeded(BlockApplication recipe, String heldItem, int rounds) {
        if (recipe.keepHeldItem()) return 1;
        int usesPerItem = Math.max(1, heldUsesPerItem.applyAsInt(heldItem));
        return (rounds + usesPerItem - 1) / usesPerItem;
    }

    private int doableRounds(BlockApplication recipe, String baseItem, String heldItem, int batchRounds) {
        int haveBase = carried(baseItem);
        int haveHeld = carried(heldItem);
        if (haveBase <= 0 || haveHeld <= 0) return 0;
        int byHeld = recipe.keepHeldItem()
                ? batchRounds
                : haveHeld * Math.max(1, heldUsesPerItem.applyAsInt(heldItem));
        return Math.min(batchRounds, Math.min(haveBase, byHeld));
    }

    private static String encodeHint(ChosenPlan plan) {
        return plan.recipe().id() + HINT_SEP + plan.baseItem() + HINT_SEP + plan.heldItem();
    }

    private Optional<ChosenPlan> decodeHint(List<BlockApplication> recipes, String hint, int wantedCount) {
        String[] parts = hint.split("\\|", -1);
        if (parts.length != 3) return Optional.empty();
        for (BlockApplication recipe : recipes) {
            if (!recipe.id().equals(parts[0])) continue;
            if (!recipe.baseItems().contains(parts[1]) || !recipe.heldItems().contains(parts[2])) continue;
            int rounds = (wantedCount + recipe.resultCount() - 1) / recipe.resultCount();
            int baseShort = Math.max(0, rounds - carried(parts[1]));
            int heldShort = Math.max(0, heldItemsNeeded(recipe, parts[2], rounds) - carried(parts[2]));
            return Optional.of(new ChosenPlan(recipe, parts[1], parts[2], rounds, baseShort, heldShort, false, 0));
        }
        return Optional.empty();
    }

    private boolean prefers(String itemId, Set<String> prefer) {
        for (String p : prefer) {
            if (new WantedItem(p).matches(itemId, tags.tagsOf(itemId))) return true;
        }
        return false;
    }

    private boolean blockedByOuter(String itemId) {
        Set<String> itemTags = tags.tagsOf(itemId);
        for (ItemRequest outer : outerWanted.get()) {
            if (outer.wanted().matches(itemId, itemTags)) return true;
        }
        return false;
    }

    private int carried(String itemId) {
        return CarriedItems.matching(backpack, offhand, WantedItem.ofItem(itemId), tags);
    }

    private static int registryDurabilityUses(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return 1;
        int maxDamage = new ItemStack(BuiltInRegistries.ITEM.get(id)).getMaxDamage();
        return maxDamage > 0 ? maxDamage : 1;
    }

    private record OptionPick(String itemId, int carried, int shortfallCount, boolean preferred, double fetchCost) {}

    private record ChosenPlan(BlockApplication recipe, String baseItem, String heldItem, int rounds,
                              int baseShort, int heldShort, boolean preferred, double fetchCost) {}
}
