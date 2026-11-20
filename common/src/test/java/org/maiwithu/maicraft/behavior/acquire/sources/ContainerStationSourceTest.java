// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.acquire.cook.FurnacesLeftCooking;
import org.maiwithu.maicraft.behavior.acquire.cook.LeftStationBatch;
import org.maiwithu.maicraft.behavior.acquire.cook.ReadsFuels;
import org.maiwithu.maicraft.behavior.acquire.cook.ReadsRecipes;
import org.maiwithu.maicraft.behavior.acquire.cook.RecipeRuns;
import org.maiwithu.maicraft.behavior.acquire.cook.StationsLeftCooking;
import org.maiwithu.maicraft.behavior.acquire.cook.WorkstationRecipe;
import org.maiwithu.maicraft.behavior.acquire.engine.ItemAcquisition;
import org.maiwithu.maicraft.behavior.acquire.engine.OffhandContents;
import org.maiwithu.maicraft.behavior.acquire.engine.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.CookingLeftBehind;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsBlockOwnership;
import org.maiwithu.maicraft.behavior.worldmemory.RememberedStation;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 通用箱式加工站来源离线测试：覆盖报价优先于原版熔炉、exclude 物品 ID 与 #标签排除回落、
 * 权限保护跳过、在跑账本优先回取与摘计时、少量短等取出、大批量与短等未出齐挂计时离开、
 * 容器消失/拒开失败、以及出料 0 产出且进料未减少的堵料失败。
 */
class ContainerStationSourceTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final WorldPosition HERE = new WorldPosition(0, 64, 0, OVERWORLD);
    private static final WorldPosition INPUT_POS = new WorldPosition(6, 64, 0, OVERWORLD);
    private static final WorldPosition OUTPUT_POS = new WorldPosition(10, 64, 0, OVERWORLD);

    private static final WorkstationRecipe IRON_SMELT = new WorkstationRecipe(
            "minecraft:iron_ingot_from_smelting_raw_iron", WorkstationRecipe.Kind.SMELTING,
            WantedItem.ofItem("minecraft:iron_ingot"), 1,
            List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:raw_iron"), 1)));
    private static final WorkstationRecipe BEEF_SMELT = new WorkstationRecipe(
            "minecraft:cooked_beef_from_smelting", WorkstationRecipe.Kind.SMELTING,
            WantedItem.ofItem("minecraft:cooked_beef"), 1,
            List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:beef"), 1)));
    private static final WorkstationRecipe KELP_SMELT = new WorkstationRecipe(
            "minecraft:dried_kelp_from_smelting", WorkstationRecipe.Kind.SMELTING,
            WantedItem.ofItem("minecraft:dried_kelp"), 1,
            List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:kelp"), 1)));

    @TempDir
    Path temp;

    private WorldMemory memory;
    private Protection protection;
    private MutableBackpack backpack;
    private FakeTimers timers;
    private StationsLeftCooking leftCooking;
    private FakeOps ops;
    private List<ItemRequest> requestedIngredients;
    private ReadsRecipes recipes;
    private ReadsItemTags tags;
    private ContainerStationSource source;

    private static final OffhandContents EMPTY_OFFHAND = Optional::empty;
    private static final ReadsBlockOwnership NO_OWNER = (dim, x, y, z) -> Optional.empty();

    @BeforeEach
    void setup() {
        memory = new WorldMemory(new DocumentStore(temp.resolve("station.sqlite")), "a".repeat(64));
        protection = new Protection(NO_OWNER, memory, memory, "self");
        backpack = new MutableBackpack();
        timers = new FakeTimers();
        leftCooking = new StationsLeftCooking(timers);
        ops = new FakeOps(backpack);
        requestedIngredients = new ArrayList<>();
        recipes = wanted -> switch (wanted.specifier()) {
            case "minecraft:iron_ingot" -> List.of(IRON_SMELT);
            case "minecraft:cooked_beef" -> List.of(BEEF_SMELT);
            case "minecraft:dried_kelp" -> List.of(KELP_SMELT);
            default -> List.of();
        };
        tags = itemId -> switch (itemId) {
            case "minecraft:beef", "minecraft:cooked_beef" -> Set.of("maicraft:foods/edible");
            default -> Set.of();
        };
        ItemNeeds stubNeeds = new ItemNeeds() {
            @Override public Action actionFor(ItemRequest request, Permissions permissions) {
                requestedIngredients.add(request);
                return new Action() {
                    @Override public ActionStatus tick(TickContext context) {
                        backpack.add(request.wanted().itemId(), request.count());
                        return ActionStatus.done();
                    }
                    @Override public String describe() { return "备料 " + request.wanted().describe(); }
                };
            }
        };
        source = new ContainerStationSource(recipes, memory, backpack, EMPTY_OFFHAND, tags, stubNeeds,
                protection, ops, leftCooking);
    }

    @Test
    void quotesSignificantlyCheaperThanVanillaFurnaceAndFallsBackWhenExcluded() {
        memory.rememberStation(new RememberedStation("鼓风熔炼站", "smelt", INPUT_POS, OUTPUT_POS,
                List.of("#maicraft:foods/edible", "minecraft:kelp")));
        memory.rememberWorkstationUsed(INPUT_POS, "minecraft:furnace", Instant.EPOCH);
        ReadsFuels fuels = id -> "minecraft:coal".equals(id) ? 1600 : 0;
        RecipeRuns noRuns = new RecipeRuns() {
            @Override public Optional<Action> run(WorkstationRecipe r, WorldPosition s, int t, Permissions p, TaskRecords rec) {
                return Optional.empty();
            }
            @Override public Optional<Action> runAtRememberedStation(WorkstationRecipe r, int t, Permissions p, TaskRecords rec) {
                return Optional.empty();
            }
        };
        RecipeSource vanillaSmelt = new RecipeSource(Set.of(WorkstationRecipe.Kind.SMELTING), recipes, memory, fuels,
                noRuns, backpack, EMPTY_OFFHAND, tags, (req, perm) -> null, null, null);
        SourceContext ctx = SourceContext.unscoped(HERE, Permissions.DEFAULT);

        // 未命中 exclude 的铁锭：箱式加工站报价显著低于原版熔炉，且缺失原料清单绝不含燃料。
        ItemRequest ironReq = new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), 8, "烧铁");
        SourceQuote.Offer stationOffer = assertInstanceOf(SourceQuote.Offer.class, source.quote(ironReq, ctx));
        SourceQuote.Offer furnaceOffer = assertInstanceOf(SourceQuote.Offer.class, vanillaSmelt.quote(ironReq, ctx));
        assertEquals(AcquireVia.SMELT, source.via());
        assertTrue(stationOffer.cost().total() < furnaceOffer.cost().total(),
                "箱式加工站报价(" + stationOffer.cost().total() + ")应显著低于原版熔炉(" + furnaceOffer.cost().total() + ")");
        List<ItemRequest> missing = source.missingIngredients(ironReq, stationOffer, ctx);
        assertEquals(1, missing.size());
        assertEquals("minecraft:raw_iron", missing.getFirst().wanted().itemId());

        // 命中 #maicraft:foods/edible 标签排除的熟牛肉：返回不适用，自然回落原版熔炉。
        ItemRequest beefReq = new ItemRequest(WantedItem.ofItem("minecraft:cooked_beef"), 4, "烤牛肉");
        assertInstanceOf(SourceQuote.Unavailable.class, source.quote(beefReq, ctx));
        assertInstanceOf(SourceQuote.Offer.class, vanillaSmelt.quote(beefReq, ctx));

        // 原料命中精确物品 ID 排除（minecraft:kelp）的干海带：同样返回不适用。
        ItemRequest kelpReq = new ItemRequest(WantedItem.ofItem("minecraft:dried_kelp"), 4, "烤海带");
        assertInstanceOf(SourceQuote.Unavailable.class, source.quote(kelpReq, ctx));
    }

    @Test
    void skipsStationProtectedByRememberedRegion() {
        memory.rememberStation(new RememberedStation("别人家站", "smelt", INPUT_POS, OUTPUT_POS, List.of()));
        memory.rememberRegion("保护基地", INPUT_POS, 4);

        SourceQuote quote = source.quote(
                new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), 4, "烧铁"),
                SourceContext.unscoped(HERE, Permissions.DEFAULT));
        SourceQuote.Unavailable unavailable = assertInstanceOf(SourceQuote.Unavailable.class, quote);
        assertTrue(unavailable.reason().contains("保护基地"), unavailable.reason());
    }

    @Test
    void smallBatchFeedsOnlyIngredientsAndShortWaitsToCollectOutput() {
        memory.rememberStation(new RememberedStation("鼓风站", "smelt", INPUT_POS, OUTPUT_POS, List.of()));
        ops.outputReadyAtTick = 1200L;
        ops.outputAvailable = 4;

        ItemRequest req = new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), 4, "少量烧铁");
        SourceContext ctx = SourceContext.unscoped(HERE, Permissions.DEFAULT);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(req, ctx));
        Action action = source.begin(req, offer, ctx).orElseThrow();

        FakeTick tick = new FakeTick(1000L);
        ActionStatus status = driveUntilTerminal(action, tick, 600);
        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(1, requestedIngredients.size());
        assertEquals("minecraft:raw_iron", requestedIngredients.getFirst().wanted().itemId(), "只备原料、绝不备燃料");
        assertEquals(4, backpack.count("minecraft:iron_ingot"));
        assertTrue(leftCooking.runningFor("minecraft:iron_ingot", OVERWORLD).isEmpty(), "短等出齐不留账本");
        assertTrue(timers.activeIds().isEmpty(), "短等出齐不挂计时");
    }

    @Test
    void largeBatchLeavesWithTimerAndEngineStopsWithoutFallingBackToFurnace() {
        memory.rememberStation(new RememberedStation("鼓风站", "smelt", INPUT_POS, OUTPUT_POS, List.of()));
        backpack.add("minecraft:raw_iron", 16);
        ops.remainingInInputAfterFeed = 16;

        ItemAcquisition engine = new ItemAcquisition(List.of(source), backpack, EMPTY_OFFHAND, tags,
                () -> HERE, Optional.empty(), ItemAcquisition.DEFAULT_MAX_DEPTH,
                CookingLeftBehind.combine(new FurnacesLeftCooking(FurnacesLeftCooking.SetsTimers.NONE), leftCooking));

        ItemRequest req = new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), 16, "大批量烧铁");
        Action run = engine.need(req, Permissions.DEFAULT,
                new ItemAcquisition.Scope(Set.of(), null, null, true), null, null);
        FakeTick tick = new FakeTick(2000L);
        ActionStatus status = driveUntilTerminal(run, tick, 40);

        ActionStatus.Failed left = assertInstanceOf(ActionStatus.Failed.class, status);
        assertEquals(Problem.Kind.WRONG_TIME, left.problem().kind());
        List<LeftStationBatch> running = leftCooking.runningFor("minecraft:iron_ingot", OVERWORLD);
        assertEquals(1, running.size());
        assertEquals(16, running.getFirst().loaded());
        assertNotNull(running.getFirst().timerId());
        assertTrue(timers.activeIds().contains(running.getFirst().timerId()));

        // 到点回来再次 obtain：优先去出料箱取货，取够即完成并自动摘掉计时。
        ops.outputAvailable = 16;
        ops.outputReadyAtTick = 0L;
        Action collectRun = engine.need(req, Permissions.DEFAULT,
                new ItemAcquisition.Scope(Set.of(), null, null, true), null, null);
        ActionStatus collected = driveUntilTerminal(collectRun, new FakeTick(2600L), 40);
        assertInstanceOf(ActionStatus.Done.class, collected);
        assertEquals(16, backpack.count("minecraft:iron_ingot"));
        assertTrue(leftCooking.runningFor("minecraft:iron_ingot", OVERWORLD).isEmpty());
        assertTrue(timers.activeIds().isEmpty(), "取够后自动摘掉计时");
    }

    @Test
    void stalledMachineWithZeroOutputAndUnchangedInputFailsHonestlyAndClearsTimer() {
        memory.rememberStation(new RememberedStation("停转站", "smelt", INPUT_POS, OUTPUT_POS, List.of()));
        leftCooking.leave(new LeftStationBatch("停转站", "smelt", INPUT_POS, OUTPUT_POS, IRON_SMELT.id(),
                "minecraft:iron_ingot", "minecraft:iron_ingot", "minecraft:raw_iron", "minecraft:raw_iron",
                16, 16, 16, 1000L, 1400L, null));
        ops.outputAvailable = 0;
        ops.peekInputRemaining = 16;

        ItemRequest req = new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), 16, "回来取货");
        SourceContext ctx = SourceContext.unscoped(HERE, Permissions.DEFAULT);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(req, ctx));
        Action action = source.begin(req, offer, ctx).orElseThrow();

        ActionStatus status = driveUntilTerminal(action, new FakeTick(1500L), 40);
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, status);
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, failed.problem().kind());
        assertTrue(failed.problem().message().contains("未减少"), failed.problem().message());
        assertTrue(leftCooking.runningFor("minecraft:iron_ingot", OVERWORLD).isEmpty(), "确认堵料后清空账本");
        assertTrue(timers.activeIds().isEmpty(), "确认堵料后摘掉计时");
    }

    @Test
    void shortWaitStillProcessingHangsTimerWhenInputDecreased() {
        memory.rememberStation(new RememberedStation("慢速站", "smelt", INPUT_POS, OUTPUT_POS, List.of()));
        backpack.add("minecraft:raw_iron", 4);
        ops.remainingInInputAfterFeed = 4;
        ops.outputAvailable = 0;
        // 原料已从 4 件减到 2 件，说明机器确实在运转，只是 23 秒短等还没出炉。
        ops.peekInputRemaining = 2;

        ItemRequest req = new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), 4, "短等转挂计时");
        SourceContext ctx = obtainContext();
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(req, ctx));
        Action action = source.begin(req, offer, ctx).orElseThrow();

        FakeTick tick = new FakeTick(3000L);
        ActionStatus status = driveUntilTerminal(action, tick, 600);
        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(1, leftCooking.runningFor("minecraft:iron_ingot", OVERWORLD).size());
        assertFalse(timers.activeIds().isEmpty());
    }

    @Test
    void leavesLongCookingFalseWaitsBoundedWithoutInterruptingOuterTask() {
        memory.rememberStation(new RememberedStation("鼓风站", "smelt", INPUT_POS, OUTPUT_POS, List.of()));
        backpack.add("minecraft:raw_iron", 16);
        ops.remainingInInputAfterFeed = 16;
        ops.peekInputRemaining = 8;
        ops.outputAvailable = 16;
        ops.outputReadyAtTick = 5600L;

        // 非 obtain 能力（如 build）通过 leavesLongCooking=false 备大批量烧炼料，不得写留场账本或挂计时打断外层主任务。
        ItemAcquisition engine = new ItemAcquisition(List.of(source), backpack, EMPTY_OFFHAND, tags,
                () -> HERE, Optional.empty(), ItemAcquisition.DEFAULT_MAX_DEPTH,
                CookingLeftBehind.combine(new FurnacesLeftCooking(FurnacesLeftCooking.SetsTimers.NONE), leftCooking));
        ItemRequest req = new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), 16, "建筑备铁锭");
        Action run = engine.need(req, Permissions.DEFAULT, ItemAcquisition.Scope.ALL, null, null);
        ActionStatus status = driveUntilTerminal(run, new FakeTick(5000L), 600);

        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(16, backpack.count("minecraft:iron_ingot"));
        assertTrue(leftCooking.runningFor("minecraft:iron_ingot", OVERWORLD).isEmpty(), "同步备料不留场记账");
        assertTrue(timers.activeIds().isEmpty(), "同步备料不挂计时");
    }

    @Test
    void runningPollFailureClearsLedgerWithoutFalseFeedMessage() {
        memory.rememberStation(new RememberedStation("回取断箱站", "smelt", INPUT_POS, OUTPUT_POS, List.of()));
        leftCooking.leave(new LeftStationBatch("回取断箱站", "smelt", INPUT_POS, OUTPUT_POS, IRON_SMELT.id(),
                "minecraft:iron_ingot", "铁锭", "minecraft:raw_iron", "粗铁",
                6, 6, 6, 1000L, 1400L, null));
        // 首次回取拿到 2 件后转短等轮询，随后出料箱被拆或拒开。
        ops.outputAvailable = 2;
        ops.failSecondTake = Problem.of(Problem.Kind.TARGET_GONE, "出料箱已被拆掉", null);

        ItemRequest req = new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), 6, "回来取货");
        SourceContext ctx = obtainContext();
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(req, ctx));
        Action action = source.begin(req, offer, ctx).orElseThrow();

        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class,
                driveUntilTerminal(action, new FakeTick(1500L), 80));
        assertEquals(Problem.Kind.TARGET_GONE, failed.problem().kind());
        assertFalse(failed.problem().message().contains("已向加工站"), failed.problem().message());
        assertTrue(leftCooking.runningFor("minecraft:iron_ingot", OVERWORLD).isEmpty(), "短等断箱后应清账本");
        assertTrue(timers.activeIds().isEmpty(), "短等断箱后应摘计时");
    }

    @Test
    void runningBatchCollectsWhenNewIngredientUnobtainableAndSkipsMovedOutput() {
        memory.rememberStation(new RememberedStation("鼓风站", "smelt", INPUT_POS, OUTPUT_POS, List.of()));
        leftCooking.leave(new LeftStationBatch("鼓风站", "smelt", INPUT_POS, OUTPUT_POS, IRON_SMELT.id(),
                "minecraft:iron_ingot", "铁锭", "minecraft:raw_iron", "粗铁",
                8, 8, 8, 1000L, 1400L, null));
        ItemNeeds unobtainable = new ItemNeeds() {
            @Override public Action actionFor(ItemRequest request, Permissions permissions) { return null; }
            @Override public boolean knownUnobtainable(WantedItem wanted) { return true; }
        };
        ContainerStationSource unobtainableSource = new ContainerStationSource(recipes, memory, backpack, EMPTY_OFFHAND,
                tags, unobtainable, protection, ops, leftCooking);
        ItemRequest req = new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), 8, "回取在跑批次");

        // 新原料已确认不可得时，仍应放行已在跑批次的出料箱回取报价。
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class,
                unobtainableSource.quote(req, obtainContext()));
        assertTrue(offer.risk().contains("取正在烧炼的批次"), offer.risk());

        // 同名加工站覆盖改了出料箱坐标后，不再拿旧批次去新箱误判堵料。
        WorldPosition movedOutput = new WorldPosition(20, 64, 0, OVERWORLD);
        memory.rememberStation(new RememberedStation("鼓风站", "smelt", INPUT_POS, movedOutput, List.of()));
        assertInstanceOf(SourceQuote.Unavailable.class, unobtainableSource.quote(req, obtainContext()));
    }

    @Test
    void quoteRespectsReadyChestAndBackpackOnlyCountsNonExcludedIngredients() {
        WorkstationRecipe charcoalRecipe = new WorkstationRecipe("minecraft:charcoal_from_smelting",
                WorkstationRecipe.Kind.SMELTING, WantedItem.ofItem("minecraft:charcoal"), 1,
                List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofTag("minecraft:logs_that_burn"), 1)));
        ReadsRecipes combinedRecipes = wanted -> "minecraft:charcoal".equals(wanted.specifier())
                ? List.of(charcoalRecipe) : recipes.recipesProducing(wanted);
        ReadsItemTags woodTags = itemId -> "minecraft:oak_log".equals(itemId)
                ? Set.of("minecraft:logs_that_burn") : tags.tagsOf(itemId);
        // 副手放 4 块粗铁、主背包空；加工站排除橡木原木但主背包只有橡木原木。
        OffhandContents offhandIron = () -> Optional.of(
                new BackpackStack("minecraft:raw_iron", 4, 64, false, false, false, false));
        backpack.add("minecraft:oak_log", 4);
        memory.rememberStation(new RememberedStation("鼓风站", "smelt", INPUT_POS, OUTPUT_POS,
                List.of("minecraft:oak_log")));
        ContainerStationSource stationSource = new ContainerStationSource(combinedRecipes, memory, backpack,
                offhandIron, woodTags, (req, perm) -> null, protection, ops, leftCooking);

        // 同距离下，箱式加工站动作数 5 高于直接开箱取现货（4）。
        ItemRequest ironReq = new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), 4, "烧铁");
        SourceQuote.Offer ironOffer = assertInstanceOf(SourceQuote.Offer.class,
                stationSource.quote(ironReq, obtainContext()));
        double sameDist = Math.sqrt(36.0);
        assertTrue(ironOffer.cost().total() > new AcquisitionCost(sameDist, 4).total(),
                "同距离箱式站不应抢走现成箱子取现货");
        // 副手里的粗铁不计入主背包可投料件数，仍应报缺 4 件。
        List<ItemRequest> missingIron = stationSource.missingIngredients(ironReq, ironOffer, obtainContext());
        assertEquals(1, missingIron.size());
        assertEquals(4, missingIron.getFirst().count());

        // 标签原料命中排除名单的具体物品（oak_log）不计入可投料件数，仍应报缺 4 件。
        ItemRequest charcoalReq = new ItemRequest(WantedItem.ofItem("minecraft:charcoal"), 4, "烧木炭");
        SourceQuote.Offer charcoalOffer = assertInstanceOf(SourceQuote.Offer.class,
                stationSource.quote(charcoalReq, obtainContext()));
        List<ItemRequest> missingLogs = stationSource.missingIngredients(charcoalReq, charcoalOffer, obtainContext());
        assertEquals(1, missingLogs.size());
        assertEquals(4, missingLogs.getFirst().count());
    }

    private static SourceContext obtainContext() {
        return new SourceContext(HERE, Permissions.DEFAULT, null, null, null, List.of(), null, null, true);
    }

    @Test
    void missingOrRefusedInputContainerFailsHonestly() {
        memory.rememberStation(new RememberedStation("坏箱站", "smelt", INPUT_POS, OUTPUT_POS, List.of()));
        backpack.add("minecraft:raw_iron", 4);
        ops.feedFailure = Problem.of(Problem.Kind.REFUSED_BY_GAME, "容器被拒绝打开", null);

        ItemRequest req = new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), 4, "投料遇拒");
        SourceContext ctx = SourceContext.unscoped(HERE, Permissions.DEFAULT);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(req, ctx));
        Action action = source.begin(req, offer, ctx).orElseThrow();

        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class,
                driveUntilTerminal(action, new FakeTick(4000L), 20));
        assertEquals(Problem.Kind.REFUSED_BY_GAME, failed.problem().kind());
        assertTrue(failed.problem().message().contains("容器被拒绝打开"), failed.problem().message());
    }

    private static ActionStatus driveUntilTerminal(Action action, FakeTick tick, int maxSteps) {
        ActionStatus last = ActionStatus.running();
        for (int i = 0; i < maxSteps; i++) {
            last = action.tick(tick);
            if (!(last instanceof ActionStatus.Running)) return last;
            tick.advance(20L);
        }
        return last;
    }

    private static final class FakeTick implements TickContext {
        private long gameTick;
        FakeTick(long start) { this.gameTick = start; }
        void advance(long delta) { gameTick += delta; }
        @Override public long gameTick() { return gameTick; }
        @Override public PlayerContext player() { return null; }
    }

    private static final class FakeTimers implements FurnacesLeftCooking.SetsTimers {
        private int nextId = 1;
        private final Map<Integer, String> active = new HashMap<>();

        @Override public Optional<Integer> set(int afterSeconds, String note, long gameTick) {
            int id = nextId++;
            active.put(id, note);
            return Optional.of(id);
        }

        @Override public void cancel(int timerId) { active.remove(timerId); }

        Set<Integer> activeIds() { return Set.copyOf(active.keySet()); }
    }

    private static final class MutableBackpack implements BackpackView {
        private final Map<String, Integer> counts = new HashMap<>();

        void add(String itemId, int amount) { counts.merge(itemId, amount, Integer::sum); }
        void remove(String itemId, int amount) {
            int left = Math.max(0, counts.getOrDefault(itemId, 0) - amount);
            if (left == 0) counts.remove(itemId);
            else counts.put(itemId, left);
        }
        int count(String itemId) { return counts.getOrDefault(itemId, 0); }

        @Override public List<BackpackStack> stacks() {
            List<BackpackStack> list = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                list.add(new BackpackStack(entry.getKey(), entry.getValue(), 64, false, false, false, false));
            }
            return list;
        }

        @Override public int usedSlots() { return counts.size(); }

        @Override public int totalSlots() { return 36; }
    }

    private static final class FakeOps implements ContainerStationOps {
        private final MutableBackpack backpack;
        int remainingInInputAfterFeed = 4;
        int outputAvailable;
        long outputReadyAtTick;
        int peekInputRemaining;
        Problem feedFailure;
        Problem failSecondTake;
        int takeCalls;

        FakeOps(MutableBackpack backpack) { this.backpack = backpack; }

        @Override public FeedAction feed(WorldPosition input, String stationName, String ingredientItemId, int count,
                Permissions permissions) {
            return new FeedAction() {
                private int delivered;
                @Override public int delivered() { return delivered; }
                @Override public int remainingInInput() { return remainingInInputAfterFeed; }
                @Override public ActionStatus tick(TickContext tick) {
                    if (feedFailure != null) return ActionStatus.failed(feedFailure);
                    int actual = Math.min(count, backpack.count(ingredientItemId));
                    backpack.remove(ingredientItemId, actual);
                    delivered = actual;
                    return ActionStatus.done();
                }
                @Override public Interruptibility interruptibility() { return Interruptibility.WORKING; }
                @Override public String describe() { return "假投料"; }
            };
        }

        @Override public TakeAction takeOutput(WorldPosition output, String stationName, WantedItem result,
                int wantCount, String ingredientItemId, Permissions permissions) {
            int callIndex = ++takeCalls;
            return new TakeAction() {
                private int taken;
                @Override public int taken() { return taken; }
                @Override public OptionalInt remainingIngredient() { return OptionalInt.empty(); }
                @Override public ActionStatus tick(TickContext tick) {
                    if (callIndex >= 2 && failSecondTake != null) {
                        return ActionStatus.failed(failSecondTake);
                    }
                    if (tick.gameTick() >= outputReadyAtTick && outputAvailable > 0) {
                        taken = Math.min(wantCount, outputAvailable);
                        outputAvailable -= taken;
                        backpack.add(result.itemId(), taken);
                    }
                    return ActionStatus.done();
                }
                @Override public Interruptibility interruptibility() { return Interruptibility.WORKING; }
                @Override public String describe() { return "假取货"; }
            };
        }

        @Override public PeekAction peekInput(WorldPosition input, String stationName, String ingredientItemId,
                Permissions permissions) {
            return new PeekAction() {
                @Override public int remainingIngredient() { return peekInputRemaining; }
                @Override public ActionStatus tick(TickContext tick) { return ActionStatus.done(); }
                @Override public Interruptibility interruptibility() { return Interruptibility.WORKING; }
                @Override public String describe() { return "假查进料箱"; }
            };
        }
    }
}
