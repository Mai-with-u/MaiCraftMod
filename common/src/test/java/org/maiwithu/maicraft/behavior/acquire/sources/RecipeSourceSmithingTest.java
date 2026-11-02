// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.acquire.FakeBackpack;
import org.maiwithu.maicraft.behavior.acquire.FakeOffhand;
import org.maiwithu.maicraft.behavior.acquire.FakeTags;
import org.maiwithu.maicraft.behavior.acquire.StubTick;
import org.maiwithu.maicraft.behavior.acquire.cook.ReadsRecipes;
import org.maiwithu.maicraft.behavior.acquire.cook.RecipeRuns;
import org.maiwithu.maicraft.behavior.acquire.cook.SetsUpWorkstation;
import org.maiwithu.maicraft.behavior.acquire.cook.WorkstationRecipe;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.inventory.hands.GearChanges;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.CollectedRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 锻造台这条路：途径单列 smith、算现做；记得的锻造台 64 格内走过去用，没有就报身边摆一张、做完挖回来；
 * 一个模板复制成两个这种净多出来的配方算做法，换颜色这种一件换一件的照旧不算；
 * 要升级的那件背包里没有、身上正穿着时脱下 → 升级 → 穿回原来那一格，那一格被占了留在背包里，升级没成把原件穿回去；
 * 背包里有就不动身上穿的。
 */
class RecipeSourceSmithingTest {

    private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");
    private static final SourceContext CONTEXT = SourceContext.unscoped(WorldPosition.here(0, 64, 0), Permissions.DEFAULT);
    private static final String TEMPLATE = "minecraft:netherite_upgrade_smithing_template";
    private static final String DIAMOND_CHESTPLATE = "minecraft:diamond_chestplate";
    private static final String NETHERITE_INGOT = "minecraft:netherite_ingot";
    private static final WantedItem NETHERITE_CHESTPLATE = WantedItem.ofItem("minecraft:netherite_chestplate");

    @TempDir Path temp;

    private final FakeBackpack backpack = new FakeBackpack(36);
    private final FakeTags tags = new FakeTags();
    /** 身上穿着的：盔甲格 → 那一件。 */
    private final Map<GearSlotName, String> worn = new EnumMap<>(GearSlotName.class);
    /** 备料时回引擎要过什么。 */
    private final List<ItemRequest> asked = new ArrayList<>();
    /** 脱过哪几格、锻造台动手过几次：先后对得上才说明是先脱后升级。 */
    private final List<String> happened = new ArrayList<>();
    /** 锻造台上这一回做不做得成：做不成时动手替身按缺料失败。 */
    private boolean upgradeWorks = true;
    /** 升级途中有别的事往这一格穿上了什么；为空就没有。 */
    private String putOnWhileUpgrading;

    private static WorkstationRecipe chestplateUpgrade() {
        return new WorkstationRecipe("minecraft:netherite_chestplate_smithing", WorkstationRecipe.Kind.SMITHING,
                NETHERITE_CHESTPLATE, 1, List.of(
                        new WorkstationRecipe.IngredientStack(WantedItem.ofItem(TEMPLATE), 1),
                        new WorkstationRecipe.IngredientStack(WantedItem.ofItem(DIAMOND_CHESTPLATE), 1),
                        new WorkstationRecipe.IngredientStack(WantedItem.ofItem(NETHERITE_INGOT), 1)));
    }

    private WorldMemory memory() {
        return new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")), "world-1");
    }

    // 备料替身：记下要了什么，当场做完（不往背包里放东西）。
    private ItemNeeds needs() {
        return (request, permissions) -> {
            asked.add(request);
            return done("备" + request.wanted().describe());
        };
    }

    // 锻造台动手替身：推进时做得成就把钻石胸甲换成下界合金胸甲放进背包，做不成按缺料失败、背包不动。
    private RecipeRuns runs() {
        return (recipe, station, times, permissions, records) -> Optional.of(new Action() {
            @Override public ActionStatus tick(TickContext context) {
                happened.add("升级" + recipe.result().itemId());
                if (putOnWhileUpgrading != null) worn.put(GearSlotName.CHEST, putOnWhileUpgrading);
                if (!upgradeWorks) return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "锻造台上少一块下界合金锭"));
                backpack.take(DIAMOND_CHESTPLATE, 1);
                backpack.addGear(recipe.result().itemId());
                return ActionStatus.done();
            }
            @Override public String describe() {
                return "在锻造台上升级";
            }
        });
    }

    // 穿脱盔甲替身：脱下把那一格的东西挪进背包，穿上从背包挪回那一格，各记一笔。
    private GearChanges gear() {
        return new GearChanges() {
            @Override public Optional<Action> wear(String itemId, GearSlotName slot) {
                return Optional.of(new Action() {
                    @Override public ActionStatus tick(TickContext context) {
                        if (!backpack.take(itemId, 1)) return failed("背包里没有").tick(context);
                        worn.put(slot, itemId);
                        happened.add("穿上" + slot.paramName() + " " + itemId);
                        return ActionStatus.done();
                    }
                    @Override public String describe() {
                        return "穿上" + slot.paramName();
                    }
                });
            }

            @Override public Optional<Action> takeOff(GearSlotName slot) {
                return Optional.of(new Action() {
                    @Override public ActionStatus tick(TickContext context) {
                        String item = worn.remove(slot);
                        if (item != null) backpack.addGear(item);
                        happened.add("脱下" + slot.paramName());
                        return ActionStatus.done();
                    }
                    @Override public String describe() {
                        return "脱下" + slot.paramName();
                    }
                });
            }
        };
    }

    private RecipeSource smithing(WorldMemory memory, ReadsRecipes recipes) {
        SetsUpWorkstation placer = (blockType, placedAt) -> Optional.empty();
        TakesOffWornGear takesOff = new TakesOffWornGear(
                slot -> Optional.ofNullable(worn.get(slot)).map(item -> new BackpackStack(item, 1, 1, true, false, false, false)),
                gear(), backpack, tags);
        return new RecipeSource(Set.of(WorkstationRecipe.Kind.SMITHING), recipes, memory, itemId -> 0, runs(),
                backpack, new FakeOffhand(), tags, needs(), placer, null, takesOff);
    }

    private RecipeSource smithing() {
        WorldMemory memory = memory();
        memory.rememberWorkstationUsed(new WorldPosition(40, 64, 0, null), "minecraft:smithing_table", NOW);
        return smithing(memory, wanted -> wanted.matches(NETHERITE_CHESTPLATE.itemId(), Set.of())
                ? List.of(chestplateUpgrade()) : List.of());
    }

    private static Action done(String what) {
        return new Action() {
            @Override public ActionStatus tick(TickContext context) {
                return ActionStatus.done();
            }
            @Override public String describe() {
                return what;
            }
        };
    }

    private static Action failed(String why) {
        return new Action() {
            @Override public ActionStatus tick(TickContext context) {
                return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, why));
            }
            @Override public String describe() {
                return why;
            }
        };
    }

    // 做完了才算过；没做完把问题原话带出来，一眼看得出卡在哪。
    private static void assertDone(ActionStatus status) {
        if (status instanceof ActionStatus.Failed failed) {
            throw new AssertionError("没做成：" + failed.problem().message());
        }
        assertInstanceOf(ActionStatus.Done.class, status);
    }

    private static ActionStatus runToEnd(Action action) {
        ActionStatus status = ActionStatus.running();
        for (int tick = 1; tick <= 50 && !(status instanceof ActionStatus.Done || status instanceof ActionStatus.Failed);
                tick++) {
            status = action.tick(new StubTick(tick));
        }
        return status;
    }

    @Test
    void 锻造台一条路_途径是smith_算现做() {
        RecipeSource source = smithing();
        assertEquals(AcquireVia.SMITH, source.via());
        assertEquals("锻造台上升级", source.describe());
        assertTrue(AcquireVia.makes(AcquireVia.SMITH.name()));
    }

    @Test
    void 记得的锻造台在四十格外_走过去用_不再摆一张() {
        backpack.add(TEMPLATE, 1);
        backpack.addGear(DIAMOND_CHESTPLATE);
        backpack.add(NETHERITE_INGOT, 1);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class,
                smithing().quote(new ItemRequest(NETHERITE_CHESTPLATE, 1, "穿"), CONTEXT));
        assertEquals(40, offer.cost().distanceBlocks(), 0.001);
        assertFalse(offer.risk().contains("附近没有"), offer.risk());
    }

    @Test
    void 没记得锻造台_报身边摆一张_做完挖回来() {
        backpack.add(TEMPLATE, 1);
        backpack.addGear(DIAMOND_CHESTPLATE);
        backpack.add(NETHERITE_INGOT, 1);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class,
                smithing(memory(), wanted -> List.of(chestplateUpgrade()))
                        .quote(new ItemRequest(NETHERITE_CHESTPLATE, 1, "穿"), CONTEXT));
        assertTrue(offer.risk().contains("附近没有锻造台"), offer.risk());
        assertTrue(offer.risk().contains("做完挖下来收回背包"), offer.risk());
    }

    @Test
    void 一个模板复制成两个_算做法() {
        WorkstationRecipe copy = new WorkstationRecipe("minecraft:netherite_upgrade_smithing_template",
                WorkstationRecipe.Kind.CRAFTING, WantedItem.ofItem(TEMPLATE), 2, List.of(
                        new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:diamond"), 7),
                        new WorkstationRecipe.IngredientStack(WantedItem.ofItem(TEMPLATE), 1),
                        new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:netherrack"), 1)));
        backpack.add(TEMPLATE, 1);
        backpack.add("minecraft:diamond", 7);
        backpack.add("minecraft:netherrack", 1);
        SourceQuote quote = crafting(copy).quote(new ItemRequest(WantedItem.ofItem(TEMPLATE), 1, "升级"), CONTEXT);
        assertEquals(TEMPLATE, assertInstanceOf(SourceQuote.Offer.class, quote).hint());
    }

    @Test
    void 一件换一件的换颜色_照旧不算做法() {
        WorkstationRecipe recolor = new WorkstationRecipe("minecraft:dye_white_bed", WorkstationRecipe.Kind.CRAFTING,
                WantedItem.ofItem("minecraft:white_bed"), 1, List.of(
                        new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:white_bed"), 1),
                        new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:white_dye"), 1)));
        backpack.add("minecraft:white_bed", 1);
        backpack.add("minecraft:white_dye", 1);
        SourceQuote quote = crafting(recolor).quote(new ItemRequest(WantedItem.ofItem("minecraft:white_bed"), 1, "睡觉"),
                CONTEXT);
        assertInstanceOf(SourceQuote.Unavailable.class, quote);
    }

    // 合成一条路（就地摆台的接缝接着）：只认给定的这一条配方。
    private RecipeSource crafting(WorkstationRecipe recipe) {
        SetsUpWorkstation placer = (blockType, placedAt) -> Optional.empty();
        return new RecipeSource(Set.of(WorkstationRecipe.Kind.CRAFTING),
                wanted -> wanted.matches(recipe.result().itemId(), Set.of()) ? List.of(recipe) : List.of(),
                memory(), itemId -> 0, runs(), backpack, new FakeOffhand(), tags, needs(), placer, null);
    }

    @Test
    void 要升级的那件只穿在身上_脱下升级再穿回原来那一格() {
        CollectedRecords records = new CollectedRecords();
        ActionStatus status = upgradeWornChestplate(records);

        assertDone(status);
        assertEquals(List.of("脱下chest", "升级minecraft:netherite_chestplate",
                "穿上chest minecraft:netherite_chestplate"), happened);
        assertEquals(NETHERITE_CHESTPLATE.itemId(), worn.get(GearSlotName.CHEST));
        assertTrue(asked.stream().noneMatch(ask -> ask.wanted().specifier().equals(DIAMOND_CHESTPLATE)),
                "没去另做一件钻石胸甲：" + asked);
        assertTrue(noteOf(records, DIAMOND_CHESTPLATE).contains("脱下来"), records.changes.toString());
        assertTrue(noteOf(records, NETHERITE_CHESTPLATE.itemId()).contains("穿回身上（chest）"), records.changes.toString());
    }

    @Test
    void 原来那一格被别的占了_升级好的留在背包里照实说() {
        putOnWhileUpgrading = "minecraft:elytra";
        CollectedRecords records = new CollectedRecords();

        assertDone(upgradeWornChestplate(records));
        assertEquals("minecraft:elytra", worn.get(GearSlotName.CHEST));
        assertTrue(backpack.stacks().stream().anyMatch(stack -> stack.itemId().equals(NETHERITE_CHESTPLATE.itemId())));
        String note = noteOf(records, NETHERITE_CHESTPLATE.itemId());
        assertTrue(note.contains("minecraft:elytra") && note.contains("留在背包里"), note);
    }

    @Test
    void 升级没成_把原件穿回去再按那个问题收场() {
        upgradeWorks = false;
        CollectedRecords records = new CollectedRecords();

        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, upgradeWornChestplate(records));
        assertTrue(failed.problem().message().contains("下界合金锭"), failed.problem().message());
        assertEquals(DIAMOND_CHESTPLATE, worn.get(GearSlotName.CHEST));
        assertTrue(records.changes.stream().anyMatch(change -> change.note().contains("原样穿回身上（chest）")),
                records.changes.toString());
    }

    // 身上穿着钻石胸甲、背包里有模板和锭，要一件下界合金胸甲：问价、动手、推到收场。
    private ActionStatus upgradeWornChestplate(CollectedRecords records) {
        backpack.add(TEMPLATE, 1);
        backpack.add(NETHERITE_INGOT, 1);
        worn.put(GearSlotName.CHEST, DIAMOND_CHESTPLATE);
        RecipeSource source = smithing();
        ItemRequest request = new ItemRequest(NETHERITE_CHESTPLATE, 1, "穿");
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(request, CONTEXT));
        Action action = source.begin(request, offer,
                SourceContext.booked(WorldPosition.here(0, 64, 0), Permissions.DEFAULT, records)).orElseThrow();
        return runToEnd(action);
    }

    private static String noteOf(CollectedRecords records, String item) {
        return records.changes.stream().filter(change -> change.what().equals(item))
                .map(Change::note).reduce((first, second) -> first + "；" + second).orElse("");
    }

    @Test
    void 背包里有一件_不动身上穿的() {
        backpack.add(TEMPLATE, 1);
        backpack.addGear(DIAMOND_CHESTPLATE);
        backpack.add(NETHERITE_INGOT, 1);
        worn.put(GearSlotName.CHEST, DIAMOND_CHESTPLATE);
        RecipeSource source = smithing();
        ItemRequest request = new ItemRequest(NETHERITE_CHESTPLATE, 1, "穿");
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(request, CONTEXT));

        assertDone(runToEnd(source.begin(request, offer, CONTEXT).orElseThrow()));
        assertEquals(List.of("升级minecraft:netherite_chestplate"), happened);
        assertEquals(DIAMOND_CHESTPLATE, worn.get(GearSlotName.CHEST));
    }

    @Test
    void 估算时穿在身上的那件不算还要去弄的() {
        backpack.add(TEMPLATE, 1);
        worn.put(GearSlotName.CHEST, DIAMOND_CHESTPLATE);
        RecipeSource source = smithing();
        ItemRequest request = new ItemRequest(NETHERITE_CHESTPLATE, 1, "穿");
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(request, CONTEXT));

        List<ItemRequest> missing = source.missingIngredients(request, offer, CONTEXT);
        assertEquals(List.of(NETHERITE_INGOT), missing.stream().map(ask -> ask.wanted().specifier()).toList());
    }
}
