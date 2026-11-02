// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.maiwithu.maicraft.behavior.acquire.cook.BrewPlan;
import org.maiwithu.maicraft.behavior.acquire.cook.BrewingRuns;
import org.maiwithu.maicraft.behavior.acquire.cook.ReadsBrewing;
import org.maiwithu.maicraft.behavior.acquire.cook.SetsUpWorkstation;
import org.maiwithu.maicraft.behavior.acquire.cook.WorkstationRecipe;
import org.maiwithu.maicraft.behavior.acquire.engine.CarriedItems;
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
import org.maiwithu.maicraft.behavior.worldmemory.MemoryRecord;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.StackKey;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 酿药水的来源：在酿造台上照游戏的酿造表酿，水瓶 → 粗制的药水 → 要的药水，红石延长、萤石加强、火药变喷溅、
 * 龙息变滞留也都是表里的一步，烈焰粉当燃料。只酿点名了药水种类的；水瓶不用酿，去水边装。
 *
 * <p>开第一炉之前备齐第一炉要的：第一组的瓶子、第一步的材料；后面几炉的料由酿造台动作在等的时候去弄。
 * 64 格内记得的酿造台走过去用，没有就身边摆一个，放下就留着、位置记进世界记忆（新摆的燃料条是空的，先备一个烈焰粉）。
 * 点开发现放着别人东西的那台不动它，这一阵问价先不算它，都不能用就身边摆一个。
 */
public final class BrewingSource implements ItemSource {

    /** 这条路的名字：酿造台上酿。 */
    public static final AcquireVia VIA = new AcquireVia("brew",
            "酿造台上照酿造表酿药水（上一步的药水加一样材料变成这一种，烈焰粉当燃料；要说清是哪种药水）");
    /** 找记得的酿造台多远：和合成、烧炼找记得的设施一样。 */
    static final int SEARCH_RADIUS_BLOCKS = RecipeSource.SEARCH_RADIUS_BLOCKS;
    /** 放着别人东西的酿造台多久不再算它：过一阵人家酿完拿走了，再点开看。 */
    static final Duration OCCUPIED_FOR = Duration.ofMinutes(10);
    /** 一炉要等多久（刻）：二十秒。 */
    static final long BREW_TICKS = 400;
    private static final String BREWING_STAND = "minecraft:brewing_stand";
    private static final String BLAZE_POWDER = BrewPlan.BLAZE_POWDER;

    private final ReadsBrewing brewing;
    private final RememberedStations stations;
    private final BrewingRuns runs;
    private final SetsUpWorkstation placer;
    private final ItemNeeds needs;
    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final ReadsItemTags tags;
    /** 点开发现放着别人东西的酿造台，和那一刻：过了 {@link #OCCUPIED_FOR} 再算它。 */
    private final Map<WorldPosition, Instant> occupied = new HashMap<>();

    public BrewingSource(ReadsBrewing brewing, WorldMemory memory, BrewingRuns runs, SetsUpWorkstation placer,
            ItemNeeds needs, BackpackView backpack, OffhandContents offhand, ReadsItemTags tags) {
        this.brewing = Objects.requireNonNull(brewing, "brewing");
        this.stations = new RememberedStations(Objects.requireNonNull(memory, "memory"), placer, SEARCH_RADIUS_BLOCKS);
        this.runs = Objects.requireNonNull(runs, "runs");
        this.placer = placer;
        this.needs = Objects.requireNonNull(needs, "needs");
        this.backpack = Objects.requireNonNull(backpack, "backpack");
        this.offhand = offhand;
        this.tags = Objects.requireNonNull(tags, "tags");
    }

    @Override public String describe() {
        return "酿造台上酿";
    }

    @Override public AcquireVia via() {
        return VIA;
    }

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        Optional<String> refusal = refusal(request);
        if (refusal.isPresent()) return new SourceQuote.Unavailable(describe(), refusal.get());
        Optional<BrewPlan> found = plan(request);
        if (found.isEmpty()) {
            return new SourceQuote.Unavailable(describe(), brewing.steps().isEmpty()
                    ? "还读不到酿造表（不在世界里）"
                    : "酿造表里没有一路酿得出 " + request.wanted().specifier() + " 的步骤");
        }
        BrewPlan plan = found.get();
        double shortfall = plan.missing(this::carried) * RecipeSource.SHORTFALL_BLOCKS_PER_ITEM;
        int actions = 4 + plan.brews() * 3;
        long waitTicks = BREW_TICKS * plan.brews();
        String chain = plan.describe() + "，" + plan.groups().size() + " 组共 " + plan.bottles() + " 瓶";
        List<WorldPosition> stands = freeStands(context);
        if (!stands.isEmpty()) {
            WorldPosition nearest = stands.getFirst();
            return new SourceQuote.Offer(describe(), request.count(),
                    new AcquisitionCost(RememberedStations.distance(nearest, context.characterAt()), actions, shortfall)
                            .withWait(waitTicks),
                    "到 " + nearest.x() + ", " + nearest.y() + ", " + nearest.z() + " 的酿造台上酿：" + chain
                            + "；一炉二十秒，等的时候顺手弄后面几炉的料");
        }
        if (placer == null) {
            return new SourceQuote.Unavailable(describe(), "酿得出（" + plan.describe() + "），但没记得附近有能用的酿造台；用过一次才会记得它在哪");
        }
        if (context.permissions().changeBlocks() == Permissions.BlockChanges.NONE) {
            return new SourceQuote.Unavailable(describe(), "酿得出（" + plan.describe() + "），附近没有能用的酿造台，"
                    + "就地放一个需要改方块的许可，这次的许可不给");
        }
        // 身边摆一个：没有酿造台先去弄一个，新摆的燃料条是空的，烈焰粉也要备一个。
        double placing = (carried(BREWING_STAND) > 0 ? 0 : 1) + (carried(BLAZE_POWDER) > 0 ? 0 : 1);
        return new SourceQuote.Offer(describe(), request.count(),
                new AcquisitionCost(0, actions + 8, shortfall + placing * RecipeSource.SHORTFALL_BLOCKS_PER_ITEM)
                        .withWait(waitTicks),
                "附近没有能用的酿造台，会在身边放一个（放下就留着、记下位置）再酿：" + chain);
    }

    @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        if (refusal(request).isPresent()) return Optional.empty();
        Optional<BrewPlan> found = plan(request);
        if (found.isEmpty()) return Optional.empty();
        BrewPlan plan = found.get();
        Permissions permissions = context.permissions();
        List<Action> steps = new ArrayList<>();
        // 开第一炉之前备齐第一炉要的：第一组的瓶子、第一步的材料；后面几炉的料等的时候再去弄。
        int bottlesShort = plan.groups().getFirst() - carried(plan.start().written());
        if (bottlesShort > 0) {
            steps.add(needs.actionFor(new ItemRequest(WantedItem.ofItem(plan.start().written()), bottlesShort,
                    "酿药水起步的瓶子"), permissions));
        }
        String firstIngredient = plan.steps().getFirst().ingredient();
        if (carried(firstIngredient) < 1) {
            steps.add(needs.actionFor(new ItemRequest(WantedItem.ofItem(firstIngredient), 1, "酿药水第一步的材料"),
                    permissions));
        }
        List<WorldPosition> stands = freeStands(context);
        if (!stands.isEmpty()) {
            Optional<Action> run = runs.brew(plan, stands, permissions, context.records(), needs, this::carried,
                    this::markOccupied);
            if (run.isEmpty()) return Optional.empty();
            steps.add(run.get());
        } else {
            if (placer == null || permissions.changeBlocks() == Permissions.BlockChanges.NONE) return Optional.empty();
            // 身边摆一个：没有酿造台先去弄一个；新摆的燃料条是空的，再备一个烈焰粉。
            if (carried(BREWING_STAND) < 1) {
                steps.add(needs.actionFor(new ItemRequest(WantedItem.ofItem(BREWING_STAND), 1, "就地摆的酿造台"),
                        permissions));
            }
            if (carried(BLAZE_POWDER) < 1) {
                steps.add(needs.actionFor(new ItemRequest(WantedItem.ofItem(BLAZE_POWDER), 1, "酿造台的燃料"),
                        permissions));
            }
            AtomicReference<WorldPosition> placedAt = new AtomicReference<>();
            Optional<Action> placing = placer.placeNearby(BREWING_STAND, placedAt::set);
            if (placing.isEmpty()) return Optional.empty();
            steps.add(placing.get());
            steps.add(new AtPlacedStand(plan, placedAt, permissions, context));
        }
        return Optional.of(new StepwiseActions("酿 " + plan.target().written(), steps.toArray(Action[]::new)));
    }

    // 估算用：照这一整条身上还缺的，起步的瓶子与各步材料（每组每步一件）。
    @Override public List<ItemRequest> missingIngredients(ItemRequest request, SourceQuote.Offer offer,
            SourceContext context) {
        Optional<BrewPlan> found = refusal(request).isPresent() ? Optional.empty() : plan(request);
        if (found.isEmpty()) return List.of();
        BrewPlan plan = found.get();
        List<ItemRequest> missing = new ArrayList<>();
        int bottles = plan.bottles() - carried(plan.start().written());
        if (bottles > 0) missing.add(new ItemRequest(WantedItem.ofItem(plan.start().written()), bottles, "酿药水起步的瓶子"));
        plan.ingredientsPerRun().forEach((ingredient, perRun) -> {
            int lacking = perRun * plan.groups().size() - carried(ingredient);
            if (lacking > 0) missing.add(new ItemRequest(WantedItem.ofItem(ingredient), lacking, "酿药水的材料"));
        });
        return missing;
    }

    // 酿造台酿不了的请求：不是药水瓶、没说是哪种药水、要的是水瓶，各说一句该怎么办。
    private static Optional<String> refusal(ItemRequest request) {
        WantedItem wanted = request.wanted();
        if (wanted.isTag() || !StackKey.POTION_BOTTLES.contains(wanted.itemId())) {
            return Optional.of("酿造台只酿药水");
        }
        if (wanted.potion() == null) return Optional.of("要说清是哪种药水（potion）才酿得出来");
        if (BrewPlan.WATER_BOTTLE.written().equals(wanted.specifier())) {
            return Optional.of("水瓶不用酿，拿玻璃瓶去水边装（fill）");
        }
        return Optional.empty();
    }

    private Optional<BrewPlan> plan(ItemRequest request) {
        return BrewPlan.plan(brewing.steps(), StackKey.parse(request.wanted().specifier()), request.count(),
                this::carried);
    }

    // 记得的、眼下还在、这一阵没碰见放着别人东西的酿造台，近的在前。
    private List<WorldPosition> freeStands(SourceContext context) {
        Instant now = Instant.now();
        occupied.values().removeIf(since -> since.plus(OCCUPIED_FOR).isBefore(now));
        List<WorldPosition> free = new ArrayList<>();
        for (MemoryRecord record : stations.standing(WorkstationRecipe.Kind.BREWING, context.characterAt())) {
            if (occupied.keySet().stream().noneMatch(spot -> MemoryRecord.sameSpot(spot, record.position()))) {
                free.add(record.position());
            }
        }
        return free;
    }

    private void markOccupied(WorldPosition at) {
        occupied.put(at, Instant.now());
    }

    private int carried(String key) {
        return CarriedItems.matching(backpack, offhand, WantedItem.ofItem(key), tags);
    }

    /** 摆完才知道酿造台落在哪一格：到这一步再按那一格建酿造台动作，没摆下就如实失败。 */
    private final class AtPlacedStand implements Action {
        private final BrewPlan plan;
        private final AtomicReference<WorldPosition> placedAt;
        private final Permissions permissions;
        private final SourceContext context;
        private Action run;

        AtPlacedStand(BrewPlan plan, AtomicReference<WorldPosition> placedAt, Permissions permissions,
                SourceContext context) {
            this.plan = plan;
            this.placedAt = placedAt;
            this.permissions = permissions;
            this.context = context;
        }

        @Override public ActionStatus tick(TickContext tick) {
            if (run == null) {
                WorldPosition at = placedAt.get();
                Optional<Action> built = at == null ? Optional.empty()
                        : runs.brew(plan, List.of(at), permissions, context.records(), needs, BrewingSource.this::carried,
                                BrewingSource.this::markOccupied);
                if (built.isEmpty()) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "酿造台摆下了却找不到它落在哪，酿不了", null));
                }
                run = built.get();
            }
            return run.tick(tick);
        }

        @Override public void pause() {
            if (run != null) run.pause();
        }

        @Override public void close() {
            if (run != null) run.close();
        }

        @Override public Interruptibility interruptibility() {
            return run == null ? Interruptibility.BETWEEN_ACTIONS : run.interruptibility();
        }

        @Override public String describe() {
            return run == null ? "到刚摆的酿造台上酿 " + plan.target().written() : run.describe();
        }
    }
}
