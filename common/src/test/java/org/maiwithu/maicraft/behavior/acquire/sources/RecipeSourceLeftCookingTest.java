// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.acquire.FakeBackpack;
import org.maiwithu.maicraft.behavior.acquire.FakeOffhand;
import org.maiwithu.maicraft.behavior.acquire.FakeTags;
import org.maiwithu.maicraft.behavior.acquire.cook.FurnacesLeftCooking;
import org.maiwithu.maicraft.behavior.acquire.cook.LeftCooking;
import org.maiwithu.maicraft.behavior.acquire.cook.RecipeRuns;
import org.maiwithu.maicraft.behavior.acquire.cook.WorkstationRecipe;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 烧炼来源和装炉就走：回来再要同一样东西时先回留着烧的那台、不先去备料；
 * 烧炼一趟最多一炉（一组），能不能装炉就走照这一场的现场交给动手的那一层。
 */
class RecipeSourceLeftCookingTest {

    private static final String DRIED_KELP = "minecraft:dried_kelp";
    private static final WorldPosition FURNACE = new WorldPosition(12, 64, -30, "minecraft:overworld");
    private static final WorldPosition REMEMBERED = new WorldPosition(4, 64, 4, "minecraft:overworld");
    private static final WorkstationRecipe KELP = new WorkstationRecipe("minecraft:dried_kelp_from_smelting",
            WorkstationRecipe.Kind.SMELTING, WantedItem.ofItem(DRIED_KELP), 1,
            List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:kelp"), 1)));

    @TempDir Path temp;

    private final FakeBackpack backpack = new FakeBackpack(36);
    private final List<ItemRequest> asked = new ArrayList<>();
    /** 动手那一层收到的：去哪几台、烧几份、能不能装炉就走。 */
    private List<WorldPosition> stations;
    private int times;
    private boolean leaves;
    private final FurnacesLeftCooking left = new FurnacesLeftCooking(FurnacesLeftCooking.SetsTimers.NONE);
    private WorldMemory memory;

    private RecipeSource source() {
        memory = new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")), "world-1");
        ItemNeeds needs = new ItemNeeds() {
            @Override public Action actionFor(ItemRequest request, Permissions permissions) {
                asked.add(request);
                return new Action() {
                    @Override public ActionStatus tick(TickContext context) {
                        return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "替身不给东西"));
                    }

                    @Override public String describe() {
                        return "弄" + request.wanted().describe();
                    }
                };
            }
        };
        RecipeRuns runs = new RecipeRuns() {
            @Override public Optional<Action> run(WorkstationRecipe recipe, WorldPosition station, int count,
                    Permissions permissions, TaskRecords records) {
                return Optional.empty();
            }

            @Override public Optional<Action> runAtAnyOf(WorkstationRecipe recipe, List<WorldPosition> at, int count,
                    Permissions permissions, TaskRecords records, boolean leavesLongCooking) {
                stations = at;
                times = count;
                leaves = leavesLongCooking;
                return Optional.of(new Action() {
                    @Override public ActionStatus tick(TickContext context) {
                        return ActionStatus.done();
                    }

                    @Override public String describe() {
                        return "烧干海带";
                    }
                });
            }
        };
        RecipeSource source = new RecipeSource(wanted -> wanted.itemId().equals(DRIED_KELP) ? List.of(KELP) : List.of(),
                memory, itemId -> itemId.equals("minecraft:coal") ? 1600 : 0, runs, backpack, new FakeOffhand(),
                new FakeTags(), needs);
        source.watchLeftCooking(left);
        return source;
    }

    private static SourceContext obtainContext() {
        return new SourceContext(WorldPosition.here(0, 64, 0), Permissions.DEFAULT, null, null, null, List.of(), null,
                null, true);
    }

    private void runToEnd(Action action) {
        ActionStatus status = ActionStatus.running();
        for (int tick = 0; tick < 50 && status instanceof ActionStatus.Running; tick++) status = action.tick(null);
        assertInstanceOf(ActionStatus.Done.class, status);
    }

    @Test
    void comingBackGoesToTheLeftFurnaceWithoutPreparingIngredients() {
        RecipeSource source = source();
        left.leave(new LeftCooking(FURNACE, WorkstationRecipe.Kind.SMELTING, KELP.id(), DRIED_KELP, "干海带",
                "minecraft:kelp", "海带", "minecraft:coal", 64, 64, 167, 0, 12800, null));
        // 身上一个海带都没有：回来先取炉子里的，不先去弄原料。
        ItemRequest request = new ItemRequest(WantedItem.ofItem(DRIED_KELP), 231, "按需要拿取");
        SourceContext context = obtainContext();
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(request, context));
        assertEquals(KELP.id(), offer.hint());
        assertTrue(offer.risk().contains("(12, 64, -30)"), offer.risk());
        assertTrue(source.missingIngredients(request, offer, context).isEmpty());

        runToEnd(source.begin(request, offer, context).orElseThrow());
        assertTrue(asked.isEmpty(), "不先去备料：" + asked);
        assertEquals(FURNACE, stations.getFirst());
        assertEquals(64, times);
        assertTrue(leaves);
    }

    @Test
    void oneRunCooksAtMostOneFurnaceLoad() {
        RecipeSource source = source();
        memory.rememberWorkstationUsed(REMEMBERED, "minecraft:furnace", Instant.now());
        backpack.add("minecraft:kelp", 64);
        backpack.add("minecraft:kelp", 64);
        backpack.add("minecraft:kelp", 64);
        backpack.add("minecraft:kelp", 39);
        backpack.add("minecraft:coal", 32);
        ItemRequest request = new ItemRequest(WantedItem.ofItem(DRIED_KELP), 231, "按需要拿取");
        SourceContext context = obtainContext();
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(request, context));

        runToEnd(source.begin(request, offer, context).orElseThrow());
        assertTrue(asked.isEmpty(), "原料和燃料身上都够：" + asked);
        assertEquals(REMEMBERED, stations.getFirst());
        assertEquals(64, times, "一趟一炉：投入口一格放得下一组");
        assertTrue(leaves);
    }
}
