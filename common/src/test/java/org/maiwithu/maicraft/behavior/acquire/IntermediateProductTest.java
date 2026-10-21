// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.permission.GuessesPlayerMade;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 备料链上的中间产物（例如为做床就地摆的工作台）清点原料时，身上的成品料也算数：
 * 身上有三块木板、工作台要四块，备料只补缺的那一块（拿一根原木做成），不把身上的三块丢开再去弄四块新的。
 */
class IntermediateProductTest {

    private static final Permissions PERMISSIONS = Permissions.DEFAULT;

    @TempDir Path temp;

    private final FakeBackpack backpack = new FakeBackpack(36);
    private final FakeOffhand offhand = new FakeOffhand();
    private final FakeTags tags = new FakeTags();
    /** 备料递归进来的内部需求，按先后：核对中间产物按身上的现料补缺，不整份重新去弄。 */
    private final List<String> asked = new ArrayList<>();

    @Test
    void 工作台备料_身上的木板算数_只补缺的那一块() {
        // 要白床、身上三羊毛三木板：床本身不缺料，缺的是先弄一张工作台（四块板，身上有三块）。
        // 备工作台的料时身上的三块木板要算进 #minecraft:planks，只再弄一块——拿一根原木做成，
        // 不去弄四块新的（实机 216：身上有板却去记忆箱子拿原木做整份，卡死在半路）。
        tags.put("minecraft:white_bed", "minecraft:beds");
        tags.put("minecraft:oak_planks", "minecraft:planks");
        backpack.add("minecraft:white_wool", 3);
        backpack.add("minecraft:oak_planks", 3);
        List<WorkstationRecipe> recipes = List.of(
                new WorkstationRecipe("minecraft:white_bed", WorkstationRecipe.Kind.CRAFTING,
                        WantedItem.ofItem("minecraft:white_bed"), 1,
                        List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:white_wool"), 3),
                                new WorkstationRecipe.IngredientStack(WantedItem.ofTag("minecraft:planks"), 3))),
                new WorkstationRecipe("minecraft:crafting_table", WorkstationRecipe.Kind.CRAFTING,
                        WantedItem.ofItem("minecraft:crafting_table"), 1,
                        List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofTag("minecraft:planks"), 4)), true),
                new WorkstationRecipe("minecraft:oak_planks", WorkstationRecipe.Kind.CRAFTING,
                        WantedItem.ofItem("minecraft:oak_planks"), 4,
                        List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:oak_log"), 1)),
                        true));

        ItemAcquisition[] engine = new ItemAcquisition[1];
        WorldMemory memory = new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")), "world-1");
        ItemNeeds needs = new ItemNeeds() {
            @Override public Action actionFor(ItemRequest request, Permissions permissions) {
                asked.add(request.wanted().specifier() + "×" + request.count() + "（" + request.purpose() + "）");
                return engine[0].actionFor(request, permissions);
            }
            @Override public boolean knownUnobtainable(WantedItem wanted) {
                return engine[0].knownUnobtainable(wanted);
            }
        };
        SetsUpWorkstation placer = (blockType, placedAt) -> Optional.of(new Action() {
            @Override public ActionStatus tick(TickContext context) {
                // 真的摆台会把位置记进世界记忆：动手那一步按它认新设施的位置。
                WorldPosition at = new WorldPosition(0, 64, 1, null);
                placedAt.accept(at);
                memory.rememberWorkstationUsed(at, blockType, Instant.now());
                return ActionStatus.done();
            }
            @Override public String describe() {
                return "摆" + blockType;
            }
        });
        RecipeSource crafting = new RecipeSource(Set.of(WorkstationRecipe.Kind.CRAFTING), wanted -> recipes.stream()
                        .filter(recipe -> wanted.matches(recipe.result().itemId(), tags.tagsOf(recipe.result().itemId())))
                        .toList(),
                memory, itemId -> 0, new Crafts(), backpack, offhand, tags, needs, placer, permission());        // 世界里只有原木挖得到；要"任意木板"时附近没有现成的板可拿，只能现做。
        ItemSource mine = new ItemSource() {
            @Override public String describe() {
                return "采掘";
            }
            @Override public AcquireVia via() {
                return AcquireVia.MINE;
            }
            @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
                if (request.wanted().matches("minecraft:oak_log", tags.tagsOf("minecraft:oak_log"))) {
                    return new SourceQuote.Offer("采掘", request.count(), new AcquisitionCost(5, 2), null);
                }
                return new SourceQuote.Unavailable("采掘", "附近没有" + request.wanted().describe());
            }
            @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer,
                    SourceContext context) {
                return Optional.of(new Action() {
                    @Override public ActionStatus tick(TickContext tick) {
                        backpack.add(request.wanted().specifier(), request.count());
                        return ActionStatus.done();
                    }
                    @Override public String describe() {
                        return "挖" + request.wanted().describe();
                    }
                });
            }
        };
        engine[0] = new ItemAcquisition(List.of(mine, crafting), backpack, offhand, tags,
                new FixedSpot(0, 64, 0), Optional.empty(), ItemAcquisition.DEFAULT_MAX_DEPTH);

        ActionStatus status = runToSettlement(engine[0].need(
                new ItemRequest(WantedItem.ofItem("minecraft:white_bed"), 1, "备床"), PERMISSIONS));

        assertInstanceOf(ActionStatus.Done.class, status);
        // 中间产物（工作台）的备料按身上的现料补缺：只补一块板（一根原木做成），不整份要四块。
        assertTrue(asked.contains("#minecraft:planks×1（为「做minecraft:crafting_table」备原料）"),
                "工作台备料该只补缺的一块板：" + asked);
        assertTrue(asked.contains("minecraft:oak_log×1（为「做minecraft:oak_planks」备原料）"),
                "补的那块板拿一根原木做成：" + asked);
        assertTrue(asked.stream().noneMatch(line -> line.startsWith("#minecraft:planks×4")),
                "身上的木板不该被丢开再要整份：" + asked);
        assertEquals(1, countOf("minecraft:white_bed"), "床做出来了");
    }

    private int countOf(String itemId) {
        return backpack.stacks().stream().filter(stack -> stack.itemId().equals(itemId))
                .mapToInt(stack -> stack.count()).sum();
    }

    private ActionStatus runToSettlement(Action action) {
        for (int tick = 1; tick <= 2000; tick++) {
            ActionStatus status = action.tick(new StubTick(tick));
            if (!(status instanceof ActionStatus.Running)) return status;
        }
        throw new AssertionError("两千刻还没定局，备料在原地打转");
    }

    /** 野外的、谁的都不是。 */
    private PermissionCheck permission() {
        ReadsCreatureSituation wild = uuid -> Optional.of(
                new ReadsCreatureSituation.CreatureSituation(false, false, false, false, false, false));
        return new PermissionCheck(new Protection((dimension, x, y, z) -> Optional.empty(), List::of,
                name -> Optional.empty(), GuessesPlayerMade.NOTHING, "self"), wild);
    }

    /** 替身：在背包合成格里做，扣原料、加成品。 */
    private final class Crafts implements RecipeRuns {
        @Override public Optional<Action> run(WorkstationRecipe recipe, WorldPosition station, int times,
                Permissions permissions, TaskRecords records) {
            return runInInventory(recipe, times, records);
        }

        @Override public boolean craftsInInventory() {
            return true;
        }

        // 就地摆的台子在记忆里找最新一台：测试替身不认位置，照样在背包合成格里做。
        @Override public Optional<Action> runAtRememberedStation(WorkstationRecipe recipe, int times,
                Permissions permissions, TaskRecords records) {
            return runInInventory(recipe, times, records);
        }

        @Override public Optional<Action> runInInventory(WorkstationRecipe recipe, int times,
                TaskRecords records) {
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    for (WorkstationRecipe.IngredientStack ingredient : recipe.ingredients()) {
                        String id = ingredient.item().isTag() ? "minecraft:oak_planks" : ingredient.item().itemId();
                        backpack.take(id, ingredient.count() * times);
                    }
                    backpack.add(recipe.result().itemId(), recipe.resultCount() * times);
                    return ActionStatus.done();
                }
                @Override public String describe() {
                    return "做" + recipe.result().describe();
                }
            });
        }
    }
}
