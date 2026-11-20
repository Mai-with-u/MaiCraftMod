// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.acquire.cook.LeftStationBatch;
import org.maiwithu.maicraft.behavior.acquire.cook.ReadsRecipes;
import org.maiwithu.maicraft.behavior.acquire.cook.StationsLeftCooking;
import org.maiwithu.maicraft.behavior.acquire.cook.WorkstationRecipe;
import org.maiwithu.maicraft.behavior.acquire.engine.OffhandContents;
import org.maiwithu.maicraft.behavior.acquire.engine.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.spi.StepwiseActions;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.StorageUse;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryRecord;
import org.maiwithu.maicraft.behavior.worldmemory.RememberedStation;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 通用箱式加工站来源（报 {@link AcquireVia#SMELT}）：使用世界记忆里人工标注的进料箱与出料箱完成烧炼，
 * 不硬编码任何模组方块。免燃料、不搓炉子，当当前维度存在可用烧炼站且目标物与原料均未命中 {@code exclude} 时，
 * 报价显著优于原版熔炉与高炉；命中 {@code exclude}（如食物）时返回不适用，自然回落到原版熔炉或烟熏炉。
 *
 * <p>执行全程走原生容器交互与正常权限检查：先查在跑账本有同目标物则先去出料箱取货，
 * 否则只经 {@link ItemNeeds} 递归备齐原料（绝不备燃料）后投入进料箱，少量短等、大批量或未出齐挂计时离开。
 */
public final class ContainerStationSource implements ItemSource {

    /** 一轮最多投多少份原料进料箱：一组，与原版熔炉单炉上限口径一致，避免递归备料塞满背包。 */
    static final int MAX_BATCHES_PER_RUN = 64;
    /** 估算报价时的极短等待代价基数（刻）：远低于原版熔炉的每件 200 刻与高炉的 100 刻。 */
    static final long OFFER_WAIT_PER_ITEM_TICKS = 10L;

    private final ReadsRecipes recipes;
    private final WorldMemory memory;
    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final ReadsItemTags tags;
    private final ItemNeeds needs;
    private final Protection protection;
    private final ContainerStationOps ops;
    private final StationsLeftCooking leftCooking;
    private final IngredientChoice choice;
    private Supplier<List<ItemRequest>> outerWanted = List::of;

    public ContainerStationSource(ReadsRecipes recipes, WorldMemory memory, BackpackView backpack,
            OffhandContents offhand, ReadsItemTags tags, ItemNeeds needs, Protection protection,
            ContainerStationOps ops, StationsLeftCooking leftCooking) {
        this.recipes = Objects.requireNonNull(recipes, "recipes");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.backpack = Objects.requireNonNull(backpack, "backpack");
        this.offhand = Objects.requireNonNull(offhand, "offhand");
        this.tags = Objects.requireNonNull(tags, "tags");
        this.needs = Objects.requireNonNull(needs, "needs");
        this.protection = Objects.requireNonNull(protection, "protection");
        this.ops = Objects.requireNonNull(ops, "ops");
        this.leftCooking = Objects.requireNonNull(leftCooking, "leftCooking");
        this.choice = new IngredientChoice(backpack, offhand, tags, needs);
    }

    /** 接上拿东西引擎的防环外层链：不拿外层正要的东西当烧炼原料。 */
    public void watchChain(Supplier<List<ItemRequest>> outerWanted) {
        this.outerWanted = Objects.requireNonNull(outerWanted, "outerWanted");
    }

    @Override
    public String describe() {
        return "箱式加工站";
    }

    @Override
    public AcquireVia via() {
        return AcquireVia.SMELT;
    }

    /** 一个命中的加工站与对应烧炼配方及可选原料项。 */
    private record Plan(RememberedStation station, WorkstationRecipe recipe, WantedItem ingredient,
            LeftStationBatch running) {}

    @Override
    public SourceQuote quote(ItemRequest request, SourceContext context) {
        String dimension = context.characterAt().dimension();
        List<RememberedStation> stations = memory.stationsFor(AcquireVia.SMELT.name(), dimension);
        if (stations.isEmpty()) {
            return new SourceQuote.Unavailable(describe(), "当前维度没有标注烧炼用的箱式加工站");
        }
        List<WorkstationRecipe> allSmelting = allSmeltingRecipes(request);
        if (allSmelting.isEmpty()) {
            return new SourceQuote.Unavailable(describe(), "没有可走箱式加工站烧出" + request.wanted().describe() + "的烧炼配方");
        }
        // 权限过滤：进料箱与出料箱都必须未被记住的区域或额外保护的地标挡住。
        List<RememberedStation> usable = new ArrayList<>();
        List<String> blockedNotes = new ArrayList<>();
        for (RememberedStation station : stations) {
            Optional<String> blocked = storageBlockedReason(station, context);
            if (blocked.isPresent()) {
                blockedNotes.add("「" + station.name() + "」" + blocked.get());
            } else {
                usable.add(station);
            }
        }
        if (usable.isEmpty()) {
            return new SourceQuote.Unavailable(describe(),
                    "标注的箱式加工站受保护不可用：" + String.join("、", blockedNotes));
        }
        // 先检查在跑账本（先于新原料不可得剪枝，确保身上无新原料时仍可把已在跑的成品取回）。
        Optional<Plan> running = findRunningPlan(usable, allSmelting, dimension);
        if (running.isPresent()) {
            Plan plan = running.get();
            double dist = distance(plan.station().output(), context.characterAt());
            return new SourceQuote.Offer(describe(), request.count(),
                    new AcquisitionCost(dist, 2),
                    "回加工站「" + plan.station().name() + "」出料箱取正在烧炼的批次",
                    encodeHint(plan.station().name(), plan.recipe().id(), true));
        }
        List<WorkstationRecipe> obtainable = obtainableSmeltingRecipes(allSmelting, request);
        if (obtainable.isEmpty()) {
            return new SourceQuote.Unavailable(describe(), "没有原料可得的箱式加工站烧炼配方");
        }
        Optional<Plan> best = pickBestStationPlan(usable, obtainable, request, context);
        if (best.isEmpty()) {
            return new SourceQuote.Unavailable(describe(),
                    "箱式加工站的排除规则（exclude）排除了" + request.wanted().describe() + "或其原料，改走其他烧炼方式");
        }
        Plan chosen = best.get();
        double dist = distance(chosen.station().input(), context.characterAt());
        int times = timesNeeded(chosen.recipe(), request.count());
        int missing = Math.max(0, chosen.recipe().ingredients().getFirst().count() * times
                - backpackCount(chosen.ingredient(), chosen.station().exclude()));
        double shortfall = missing * RecipeSource.SHORTFALL_BLOCKS_PER_ITEM;
        long wait = Math.min(request.count(), ContainerStationRun.SHORT_WAIT_MAX_ITEMS) * OFFER_WAIT_PER_ITEM_TICKS;
        // 动作数设为 5：同距离下不抢走直接开箱取现货（4），同时仍显著优于原版熔炉与高炉（7 + 燃料与更长等待）。
        return new SourceQuote.Offer(describe(), request.count(),
                new AcquisitionCost(dist, 5, shortfall).withWait(wait),
                "免燃料走箱式加工站「" + chosen.station().name() + "」烧炼",
                encodeHint(chosen.station().name(), chosen.recipe().id(), false));
    }

    @Override
    public List<ItemRequest> missingIngredients(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        Optional<Plan> plan = resolveHint(offer.hint(), request, context);
        if (plan.isEmpty() || plan.get().running() != null) return List.of();
        Plan chosen = plan.get();
        int times = timesNeeded(chosen.recipe(), request.count());
        int needed = chosen.recipe().ingredients().getFirst().count() * times;
        int carried = backpackCount(chosen.ingredient(), chosen.station().exclude());
        if (carried >= needed) return List.of();
        // 只报缺的原料，绝不报任何燃料。
        return List.of(new ItemRequest(chosen.ingredient(), needed - carried,
                "为箱式加工站烧" + chosen.recipe().result().describe() + "备原料"));
    }

    @Override
    public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        Optional<Plan> resolved = resolveHint(offer.hint(), request, context);
        if (resolved.isEmpty()) return Optional.empty();
        Plan plan = resolved.get();
        if (storageBlockedReason(plan.station(), context).isPresent()) return Optional.empty();
        Permissions permissions = context.permissions();
        boolean leavesLong = context.leavesLongCooking();
        if (plan.running() != null) {
            return Optional.of(ContainerStationRun.collect(plan.station(), plan.recipe(), plan.running(),
                    request.count(), ops, leftCooking, permissions, leavesLong));
        }
        int times = Math.min(timesNeeded(plan.recipe(), request.count()), MAX_BATCHES_PER_RUN);
        int ingredientNeed = plan.recipe().ingredients().getFirst().count() * times;
        int carried = backpackCount(plan.ingredient(), plan.station().exclude());
        List<Action> steps = new ArrayList<>();
        if (carried < ingredientNeed) {
            ItemRequest needIngredient = new ItemRequest(plan.ingredient(), ingredientNeed - carried,
                    "为箱式加工站「" + plan.station().name() + "」备原料");
            steps.add(needs.actionFor(needIngredient, permissions));
        }
        ContainerStationRun stationRun = ContainerStationRun.feedAndRun(plan.station(), plan.recipe(),
                plan.ingredient().describe(), request.count(), ingredientNeed,
                () -> concreteCarriedIngredient(plan.ingredient(), plan.station().exclude()),
                id -> backpackCount(WantedItem.ofItem(id), plan.station().exclude()),
                ops, leftCooking, permissions, leavesLong);
        if (steps.isEmpty()) {
            return Optional.of(stationRun);
        }
        steps.add(stationRun);
        return Optional.of(new StepwiseActions(
                "备齐原料后送入箱式加工站「" + plan.station().name() + "」烧" + plan.recipe().result().describe(),
                steps.toArray(Action[]::new)));
    }

    // 投料前从背包主格（36 格）解析出匹配配方原料且未命中 exclude 的具体物品 ID（支持如烧木炭这类标签原料）。
    private Optional<String> concreteCarriedIngredient(WantedItem wanted, List<String> exclude) {
        if (!wanted.isTag()) {
            return backpackCount(wanted, exclude) > 0
                    ? Optional.of(wanted.itemId()) : Optional.empty();
        }
        for (BackpackStack stack : backpack.stacks()) {
            if (stack.count() <= 0) continue;
            if (wanted.matches(stack, tags.tagsOf(stack.itemId())) && !isExcluded(stack.itemId(), exclude, tags)) {
                return Optional.of(stack.itemId());
            }
        }
        return Optional.empty();
    }

    /** 判断某个具体物品 ID 是否命中加工站的 exclude 规则列表（同时支持精确物品 ID 与 #标签）。 */
    static boolean isExcluded(String itemId, List<String> exclude, ReadsItemTags tags) {
        if (itemId == null || exclude == null || exclude.isEmpty()) return false;
        var itemTags = tags.tagsOf(itemId);
        for (String rule : exclude) {
            if (rule == null || rule.isBlank()) continue;
            if (new WantedItem(rule).matches(itemId, itemTags)) return true;
        }
        return false;
    }

    // 只认带非空原料的标准 SMELTING 配方，过滤自噬与外层正要的防环配方（不做新原料可得性剪枝，供在跑回取复用）。
    private List<WorkstationRecipe> allSmeltingRecipes(ItemRequest request) {
        return recipes.recipesProducing(request.wanted()).stream()
                .filter(recipe -> recipe.kind() == WorkstationRecipe.Kind.SMELTING)
                .filter(recipe -> !recipe.result().isTag())
                .filter(recipe -> !recipe.ingredients().isEmpty())
                .filter(recipe -> !eatsWhatIsWanted(recipe, request.wanted()))
                .filter(recipe -> !consumesOuterWanted(recipe, request))
                .toList();
    }

    // 新投料路径再滤掉原料全确认不可得的配方，避免空跑。
    private List<WorkstationRecipe> obtainableSmeltingRecipes(List<WorkstationRecipe> candidates, ItemRequest request) {
        return candidates.stream()
                .filter(recipe -> !ingredientsAllUnobtainable(recipe, request))
                .toList();
    }

    // 匹配在跑批次：同时校验站名与出料口坐标一致（同名覆盖改坐标后不拿旧批次去新箱误判堵料），并优先精确绑定原 recipeId。
    private Optional<Plan> findRunningPlan(List<RememberedStation> usable, List<WorkstationRecipe> allSmelting,
            String dimension) {
        List<String> resultIds = allSmelting.stream().map(recipe -> recipe.result().itemId()).distinct().toList();
        for (String resultId : resultIds) {
            for (LeftStationBatch batch : leftCooking.runningFor(resultId, dimension)) {
                for (RememberedStation station : usable) {
                    if (!station.name().equals(batch.stationName())) continue;
                    if (!MemoryRecord.sameSpot(station.output(), batch.output())) continue;
                    if (isExcluded(resultId, station.exclude(), tags)) continue;
                    if (isExcluded(batch.ingredientItemId(), station.exclude(), tags)) continue;
                    WorkstationRecipe bound = allSmelting.stream()
                            .filter(candidate -> candidate.id().equals(batch.recipeId()))
                            .findFirst()
                            .or(() -> allSmelting.stream()
                                    .filter(candidate -> candidate.result().itemId().equals(resultId))
                                    .findFirst())
                            .orElse(null);
                    if (bound != null) {
                        return Optional.of(new Plan(station, bound, WantedItem.ofItem(batch.ingredientItemId()), batch));
                    }
                }
            }
        }
        return Optional.empty();
    }

    // 在全部可用站 × 适用烧炼配方中挑总距离与缺料最省的一组；任一端命中 exclude 的自动跳过。
    private Optional<Plan> pickBestStationPlan(List<RememberedStation> usable, List<WorkstationRecipe> smelting,
            ItemRequest request, SourceContext context) {
        List<Plan> candidates = new ArrayList<>();
        for (RememberedStation station : usable) {
            for (WorkstationRecipe recipe : smelting) {
                if (isExcluded(recipe.result().itemId(), station.exclude(), tags)) continue;
                Optional<WantedItem> ingredient = pickAllowedIngredient(recipe, station.exclude());
                if (ingredient.isPresent()) {
                    candidates.add(new Plan(station, recipe, ingredient.get(), null));
                }
            }
        }
        return candidates.stream().min(Comparator
                .<Plan>comparingInt(plan -> Math.max(0,
                        plan.recipe().ingredients().getFirst().count() * timesNeeded(plan.recipe(), request.count())
                                - backpackCount(plan.ingredient(), plan.station().exclude())))
                .thenComparingDouble(plan -> distance(plan.station().input(), context.characterAt()))
                .thenComparing(plan -> plan.station().name()));
    }

    // 从单格烧炼原料的可选项里挑未命中 exclude 的原料（支持具体物品与标签原料）：主背包已有未排除原料优先，其余按顺序挑第一项。
    private Optional<WantedItem> pickAllowedIngredient(WorkstationRecipe recipe, List<String> exclude) {
        WorkstationRecipe.IngredientStack first = recipe.ingredients().getFirst();
        List<WantedItem> allowed = new ArrayList<>();
        for (WantedItem option : first.options()) {
            if (option.isTag()) {
                if (exclude == null || !exclude.contains(option.specifier())) {
                    allowed.add(option);
                }
            } else if (!isExcluded(option.itemId(), exclude, tags)) {
                allowed.add(option);
            }
        }
        if (allowed.isEmpty()) return Optional.empty();
        return allowed.stream()
                .max(Comparator.comparingInt(option -> backpackCount(option, exclude)))
                .filter(option -> backpackCount(option, exclude) > 0)
                .or(() -> Optional.of(allowed.getFirst()));
    }

    private Optional<Plan> resolveHint(String hint, ItemRequest request, SourceContext context) {
        if (hint == null || hint.isBlank()) return Optional.empty();
        String[] parts = hint.split("\n", 3);
        if (parts.length < 3) return Optional.empty();
        String stationName = parts[0];
        String recipeId = parts[1];
        boolean wantsRunning = "running".equals(parts[2]);
        Optional<RememberedStation> station = memory.station(stationName);
        if (station.isEmpty()) return Optional.empty();
        List<WorkstationRecipe> pool = wantsRunning
                ? allSmeltingRecipes(request)
                : obtainableSmeltingRecipes(allSmeltingRecipes(request), request);
        Optional<WorkstationRecipe> recipe = pool.stream()
                .filter(candidate -> candidate.id().equals(recipeId))
                .findFirst();
        if (recipe.isEmpty()) return Optional.empty();
        if (isExcluded(recipe.get().result().itemId(), station.get().exclude(), tags)) return Optional.empty();
        if (wantsRunning) {
            Optional<LeftStationBatch> batch = leftCooking.atStation(stationName, recipe.get().result().itemId());
            if (batch.isPresent()
                    && MemoryRecord.sameSpot(station.get().output(), batch.get().output())
                    && !isExcluded(batch.get().ingredientItemId(), station.get().exclude(), tags)) {
                return Optional.of(new Plan(station.get(), recipe.get(),
                        WantedItem.ofItem(batch.get().ingredientItemId()), batch.get()));
            }
            return Optional.empty();
        }
        return pickAllowedIngredient(recipe.get(), station.get().exclude())
                .map(ingredient -> new Plan(station.get(), recipe.get(), ingredient, null));
    }

    private Optional<String> storageBlockedReason(RememberedStation station, SourceContext context) {
        var landmarks = context.permissions().protectedLandmarks();
        StorageUse inputUse = protection.storageUse(station.input(), null, landmarks);
        if (!inputUse.usable()) return Optional.of("进料箱" + inputUse.describe());
        StorageUse outputUse = protection.storageUse(station.output(), null, landmarks);
        if (!outputUse.usable()) return Optional.of("出料箱" + outputUse.describe());
        return Optional.empty();
    }

    // 统一按主背包 36 格计数（与 StoresInContainer 实际能搬入容器的槽位口径一致），并跳过命中 exclude 的具体物品。
    private int backpackCount(WantedItem wanted, List<String> exclude) {
        int total = 0;
        for (BackpackStack stack : backpack.stacks()) {
            if (stack.count() <= 0) continue;
            if (wanted.matches(stack, tags.tagsOf(stack.itemId())) && !isExcluded(stack.itemId(), exclude, tags)) {
                total += stack.count();
            }
        }
        return total;
    }

    private boolean eatsWhatIsWanted(WorkstationRecipe recipe, WantedItem wanted) {
        int eaten = recipe.ingredients().stream()
                .filter(ingredient -> choice.satisfies(ingredient, wanted))
                .mapToInt(WorkstationRecipe.IngredientStack::count)
                .sum();
        return eaten > 0 && eaten >= recipe.resultCount();
    }

    private boolean consumesOuterWanted(WorkstationRecipe recipe, ItemRequest request) {
        List<ItemRequest> outer = new ArrayList<>(outerWanted.get());
        for (int i = outer.size() - 1; i >= 0; i--) {
            if (outer.get(i).wanted().equals(request.wanted())) {
                outer.remove(i);
                break;
            }
        }
        for (ItemRequest wanted : outer) {
            if (recipe.ingredients().stream().anyMatch(ingredient -> choice.satisfies(ingredient, wanted.wanted()))) {
                return true;
            }
        }
        return false;
    }

    private boolean ingredientsAllUnobtainable(WorkstationRecipe recipe, ItemRequest request) {
        int times = timesNeeded(recipe, request.count());
        boolean anyMissing = false;
        for (WorkstationRecipe.IngredientStack ingredient : recipe.ingredients()) {
            int needed = ingredient.count() * times;
            int inBackpack = ingredient.options().stream()
                    .mapToInt(option -> backpackCount(option, List.of()))
                    .max().orElse(0);
            if (inBackpack >= needed) continue;
            anyMissing = true;
            if (!ingredient.options().stream().allMatch(needs::knownUnobtainable)) return false;
        }
        return anyMissing;
    }

    private static int timesNeeded(WorkstationRecipe recipe, int count) {
        return Math.max(1, (count + recipe.resultCount() - 1) / recipe.resultCount());
    }

    private static double distance(WorldPosition target, WorldPosition from) {
        double dx = target.x() - from.x();
        double dy = target.y() - from.y();
        double dz = target.z() - from.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static String encodeHint(String stationName, String recipeId, boolean running) {
        return stationName + "\n" + recipeId + "\n" + (running ? "running" : "new");
    }
}
