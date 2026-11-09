// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.acquire.FakeBackpack;
import org.maiwithu.maicraft.behavior.acquire.FakeOffhand;
import org.maiwithu.maicraft.behavior.acquire.FakeTags;
import org.maiwithu.maicraft.behavior.acquire.FixedSpot;
import org.maiwithu.maicraft.behavior.acquire.StubTick;
import org.maiwithu.maicraft.behavior.acquire.cook.ReadsRecipes;
import org.maiwithu.maicraft.behavior.acquire.cook.RecipeRuns;
import org.maiwithu.maicraft.behavior.acquire.cook.WorkstationRecipe;
import org.maiwithu.maicraft.behavior.acquire.sources.ContainerSource;
import org.maiwithu.maicraft.behavior.acquire.sources.RecipeSource;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.inventory.containers.ContainerChooser;
import org.maiwithu.maicraft.behavior.inventory.containers.SpotsContainers;
import org.maiwithu.maicraft.behavior.permission.Protection;
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
 * 走不到跟前的箱子只问一次：实机 11-03 23:27 泡在水坑里拿火把，唯一一只没开过的箱子在下方深处走不到，
 * 火把的每一种做法、每一样原料都去问它、各走一遍、各在说明里抄一遍经过，说明写到几万字。
 * 这里搭同样的形状：几种做法各要一样原料，原料都只可能在那只箱子里。核对：箱子只走一次，
 * 后面的原料不再去问；说明里这只箱子合成一句、经过只出现一次，原料种数从 2 到 12，说明不跟着按经过的长度涨。
 */
class UnreachableContainerShortageTest {

    private static final WorldPosition CHEST_AT = new WorldPosition(10, 20, 5, null);
    /** 靠近收场时那一长段经过：试过的站位、各为什么走不通。 */
    private static final String APPROACH_STORY = "走不到箱子跟前：从 0, 64, 0 附近到不了方块 10, 20, 5："
            + "试过 49 个位置" + "（这条路走不通：水下一口气游不过去）".repeat(60);

    private static final Protection NOBODY_OWNS = new Protection((dimension, x, y, z) -> Optional.empty(),
            List::of, name -> Optional.empty(), "self");

    @TempDir Path temp;

    private final FakeBackpack backpack = new FakeBackpack(36);
    private final FakeOffhand offhand = new FakeOffhand();
    private final FakeTags tags = new FakeTags();
    /** 开箱取货真的动了几次身。 */
    private int walks;

    // 火把有几种做法：第 i 种要一件燃料 i；燃料哪个来源都做不出来，只可能在那只箱子里。
    private List<WorkstationRecipe> recipes(int kinds) {
        List<WorkstationRecipe> all = new ArrayList<>();
        for (int i = 0; i < kinds; i++) {
            String light = "minecraft:light_" + i;
            tags.put(light, "minecraft:lights");
            all.add(new WorkstationRecipe(light, WorkstationRecipe.Kind.CRAFTING, WantedItem.ofItem(light), 1,
                    List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:fuel_" + i), 1)), true));
        }
        return all;
    }

    // 找容器的读端：只有一只没开过的箱子，扫完了。
    private static SpotsContainers oneUnopenedChest() {
        ContainerChooser.Candidate chest = new ContainerChooser.Candidate("chest（10, 20, 5）", "minecraft:chest",
                CHEST_AT, null, true, 45, false, true, ContainerChooser.Lid.CLEAR, false);
        return new SpotsContainers() {
            @Override public List<ContainerChooser.Candidate> around(BlockPos center, int radius) {
                return List.of(chest);
            }

            @Override public boolean scanComplete() {
                return true;
            }

            @Override public Optional<ContainerChooser.Candidate> at(WorldPosition position) {
                return Optional.of(chest);
            }
        };
    }

    // 开箱取货：每次都走不到跟前，按靠近收场的那一长段经过失败。
    private Optional<Action> unreachableTake() {
        walks++;
        return Optional.of(new Action() {
            @Override public ActionStatus tick(TickContext context) {
                return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE, APPROACH_STORY, null));
            }

            @Override public String describe() {
                return "走向箱子";
            }
        });
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

        @Override public Optional<Action> runInInventory(WorkstationRecipe recipe, int times, TaskRecords records) {
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    backpack.add(recipe.result().itemId(), recipe.resultCount() * times);
                    return ActionStatus.done();
                }

                @Override public String describe() {
                    return "做" + recipe.result().describe();
                }
            });
        }
    }

    private String failureWith(int kinds) {
        List<WorkstationRecipe> recipes = recipes(kinds);
        ItemAcquisition[] engine = new ItemAcquisition[1];
        ItemNeeds needs = new ItemNeeds() {
            @Override public Action actionFor(ItemRequest request, Permissions permissions) {
                return engine[0].actionFor(request, permissions);
            }

            @Override public boolean knownUnobtainable(WantedItem wanted) {
                return engine[0].knownUnobtainable(wanted);
            }
        };
        ReadsRecipes reads = wanted -> recipes.stream()
                .filter(recipe -> wanted.matches(recipe.result().itemId(), tags.tagsOf(recipe.result().itemId())))
                .toList();
        RecipeSource crafting = new RecipeSource(reads, new WorldMemory(
                new DocumentStore(temp.resolve("state-" + kinds + ".sqlite")), "world-1"),
                itemId -> 0, new Crafts(), backpack, offhand, tags, needs);
        ContainerSource chest = new ContainerSource(oneUnopenedChest(), tags, NOBODY_OWNS,
                (container, wants, permissions) -> unreachableTake());
        engine[0] = new ItemAcquisition(List.of(chest, crafting), backpack, offhand, tags, new FixedSpot(0, 64, 0),
                Optional.empty(), ItemAcquisition.DEFAULT_MAX_DEPTH);
        Action need = engine[0].need(new ItemRequest(WantedItem.ofTag("minecraft:lights"), 1, "照明"),
                Permissions.DEFAULT);
        for (int tick = 1; tick <= 4000; tick++) {
            ActionStatus status = need.tick(new StubTick(tick));
            if (!(status instanceof ActionStatus.Running)) {
                return assertInstanceOf(ActionStatus.Failed.class, status).problem().message();
            }
        }
        throw new AssertionError("四千刻还没定局");
    }

    @Test
    void unreachableChestIsWalkedToOnceAndToldOnce() {
        String message = failureWith(12);
        // 只走了一趟：后面的原料不再去问那只箱子。
        assertEquals(1, walks, message);
        // 说明里这只箱子合成一句，经过只出现一次。
        assertEquals(1, count(message, "记得的箱子 10, 20, 5 走不到"), message);
        assertEquals(1, count(message, APPROACH_STORY), message);
        // 问到它的：火把那一层加上十二样燃料。
        assertTrue(message.contains("问了 13 种东西"), message);
    }

    @Test
    void messageDoesNotGrowByTheApproachStoryPerIngredient() {
        // 原料从 2 种涨到 12 种：多出来的只是每种做法、每样原料自己的一句，不再每样各抄一遍那一长段经过。
        String few = failureWith(2);
        walks = 0;
        String many = failureWith(12);
        int perKind = (many.length() - few.length()) / 10;
        assertTrue(perKind < APPROACH_STORY.length() / 2,
                "每多一种原料说明多 " + perKind + " 字，经过本身 " + APPROACH_STORY.length() + " 字：" + many);
        assertEquals(1, count(few, APPROACH_STORY), few);
    }

    private static int count(String text, String part) {
        int found = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) found++;
        return found;
    }
}
