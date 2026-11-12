// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;
import org.maiwithu.maicraft.behavior.inventory.containers.ContainerChooser;
import org.maiwithu.maicraft.behavior.inventory.containers.LootContainerChooser;
import org.maiwithu.maicraft.behavior.inventory.containers.LootsContainer;
import org.maiwithu.maicraft.behavior.inventory.containers.RareLootNotice;
import org.maiwithu.maicraft.behavior.menu.MenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.perception.SceneSign;
import org.maiwithu.maicraft.behavior.permission.ContainerWhose;
import org.maiwithu.maicraft.behavior.permission.StorageUse;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;
import org.maiwithu.maicraft.mcp.tool.ResultJson;

/**
 * 搜刮任务与结果 JSON 结构测试：验证自动挑未开战利品容器、结果细节字段形状、空箱子结论及点名自家或玩家容器只看不拿。
 */
class LootTaskTest {

    @BeforeAll
    static void 引导物品注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final MenuChannel CHANNEL = new MenuChannel() {
        @Override public boolean stillOpen() { return true; }
        @Override public boolean cursorCarrying() { return false; }
        @Override public SlotSnapshot cursor() { return SlotSnapshot.empty(); }
        @Override public boolean click(int slot, int button) { return true; }
        @Override public boolean quickMove(int slot) { return true; }
        @Override public boolean selectTradeOffer(int offerIndex) { return true; }
        @Override public void requestClose() {}
    };

    private static final MenuSlots SLOTS = new MenuSlots() {
        @Override public String menuTypeId() { return "minecraft:generic_9x3"; }
        @Override public int slotCount() { return 63; }
        @Override public boolean playerBacked(int slot) { return slot >= 27; }
        @Override public int playerInventoryIndex(int slot) { return slot - 27 + 9; }
    };

    private static OpenedMenu fakeMenu(List<ItemStack> items) {
        ItemStack[] box = new ItemStack[27];
        for (int i = 0; i < 27; i++) box[i] = i < items.size() ? items.get(i).copy() : ItemStack.EMPTY;
        ItemStack[] player = new ItemStack[36];
        for (int i = 0; i < 36; i++) player[i] = ItemStack.EMPTY;
        boolean[] closed = {false};
        return new OpenedMenu() {
            @Override
            public Optional<MenuContent.Reading> reading() {
                if (closed[0]) return Optional.empty();
                List<Integer> cIds = new ArrayList<>();
                List<SlotSnapshot> cSnaps = new ArrayList<>();
                for (int i = 0; i < 27; i++) {
                    cIds.add(i);
                    cSnaps.add(box[i].isEmpty() ? SlotSnapshot.empty() : SlotSnapshot.of(box[i]));
                }
                List<Integer> pIds = new ArrayList<>();
                List<Integer> pIdx = new ArrayList<>();
                List<SlotSnapshot> pSnaps = new ArrayList<>();
                for (int i = 0; i < 36; i++) {
                    pIds.add(27 + i);
                    pIdx.add(i < 27 ? 9 + i : i - 27);
                    pSnaps.add(player[i].isEmpty() ? SlotSnapshot.empty() : SlotSnapshot.of(player[i]));
                }
                return Optional.of(new MenuContent.Reading(CHANNEL, SLOTS, cIds, pIds, pIdx, cSnaps, pSnaps));
            }

            @Override public boolean busy() { return false; }
            @Override public boolean cursorEmpty() { return true; }
            @Override public SlotSnapshot cursor() { return SlotSnapshot.empty(); }

            @Override
            public boolean quickMove(int slotId) {
                for (int i = 0; i < 36; i++) {
                    if (player[i].isEmpty()) {
                        player[i] = box[slotId].copy();
                        box[slotId] = ItemStack.EMPTY;
                        return true;
                    }
                }
                return true;
            }

            @Override public boolean click(int slotId, int button) { return true; }
            @Override public void noteCursorTakenFrom(int slotId) {}

            @Override
            public Action closing() {
                return new Action() {
                    @Override public ActionStatus tick(TickContext ctx) {
                        closed[0] = true;
                        return ActionStatus.done();
                    }

                    @Override public String describe() { return "关箱"; }
                };
            }

            @Override public void abandon() { closed[0] = true; }
        };
    }

    private static LootSeams.World fakeWorld(LootContainerChooser.Candidate candidate, List<ItemStack> contents) {
        OpenedMenu menu = fakeMenu(contents);
        MenuOpening opening = new MenuOpening() {
            @Override public Optional<OpenedMenu> opened() { return Optional.of(menu); }
            @Override public ActionStatus tick(TickContext ctx) { return ActionStatus.done(); }
            @Override public String describe() { return "打开"; }
        };
        return new LootSeams.World() {
            @Override public BlockPos feet() { return new BlockPos(0, 64, 0); }
            @Override public Optional<WorldPosition> seen(String id) { return Optional.of(candidate.container().at()); }
            @Override public Optional<LootSeams.EntityContainer> entityContainer(String id) { return Optional.empty(); }
            @Override public Optional<WorldPosition> landmark(String name) { return Optional.empty(); }
            @Override public List<SceneSign> signs() { return List.of(); }
            @Override public List<LootContainerChooser.Candidate> around(BlockPos c, int r, Set<String> l) {
                return List.of(candidate);
            }
            @Override public boolean scanComplete() { return true; }
            @Override public Optional<LootContainerChooser.Candidate> containerAt(WorldPosition p, Set<String> l) {
                return Optional.of(candidate);
            }
            @Override public Optional<Action> digLid(BlockPos lidCell, Permissions p) { return Optional.empty(); }
            @Override public LootsContainer lootBlock(LootContainerChooser.Entry entry, Permissions p) {
                return new LootsContainer(entry.container().name(), entry.container().blockTypeId(),
                        entry.container().at(), entry.origin(), opening, ItemsToKeep.NOTHING, null, RareLootNotice.NONE);
            }
            @Override public LootsContainer lootEntity(LootSeams.EntityContainer e, Permissions p) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static TaskResult runTask(LootTask task) {
        task.start(TickContext.of(0, null));
        for (long t = 1; t <= 80; t++) {
            TickResult status = task.tick(TickContext.of(t, null));
            if (status instanceof TickResult.Finished) return task.close(CloseReason.FINISHED);
        }
        throw new AssertionError("80 刻内任务未结束");
    }

    @Test
    void 自动搜刮输出完整结构化细节_空箱子返回开了是空的() {
        WorldPosition pos = new WorldPosition(-311, 59, 73, "minecraft:overworld");
        LootContainerChooser.Candidate lootr = new LootContainerChooser.Candidate(
                new ContainerChooser.Candidate("战利品箱", "lootr:lootr_chest", pos, null, true, 5.0,
                        false, true, ContainerChooser.Lid.CLEAR, false),
                new ContainerWhose(ContainerWhose.Kind.NATURAL_LOOT, null, false),
                StorageUse.USABLE);

        LootTask task = new LootTask(new LootInput(null, 24, Permissions.DEFAULT),
                fakeWorld(lootr, List.of(new ItemStack(Items.HEART_OF_THE_SEA, 1), new ItemStack(Items.TNT, 1))));
        TaskResult result = runTask(task);
        assertEquals(TaskResult.Status.DONE, result.status());
        JsonObject details = ResultJson.result(result).getAsJsonObject("details");
        assertEquals("lootr:lootr_chest", details.getAsJsonObject("container").get("block").getAsString());
        assertEquals("natural_loot", details.getAsJsonObject("container").get("whose").getAsString());
        assertFalse(details.getAsJsonObject("container").get("opened_by_self").getAsBoolean());
        assertEquals(2, details.getAsJsonArray("opened").size());
        assertEquals(1, details.getAsJsonArray("taken").size());
        assertEquals(1, details.getAsJsonArray("left").size());
        assertEquals("not_worth", details.getAsJsonArray("left").get(0).getAsJsonObject().get("why").getAsString());
        assertEquals(1, details.getAsJsonArray("rare_finds").size());
        assertFalse(details.get("backpack_full").getAsBoolean());

        // 空箱子：DONE，summary="开了，是空的"。
        LootTask emptyTask = new LootTask(new LootInput(null, 24, Permissions.DEFAULT), fakeWorld(lootr, List.of()));
        TaskResult emptyResult = runTask(emptyTask);
        assertEquals(TaskResult.Status.DONE, emptyResult.status());
        assertEquals("开了，是空的", emptyResult.summary());
    }

    @Test
    void 点名别人放的箱子只看不拿_以NOT_POSSIBLE_HERE收场并附上看见的内容() {
        WorldPosition pos = new WorldPosition(12, 64, 8, "minecraft:overworld");
        LootContainerChooser.Candidate playerChest = new LootContainerChooser.Candidate(
                new ContainerChooser.Candidate("玩家箱", "minecraft:chest", pos, null, true, 3.0,
                        true, true, ContainerChooser.Lid.CLEAR, false),
                new ContainerWhose(ContainerWhose.Kind.PLAYER, "玩家甲", false),
                StorageUse.USABLE);

        LootTask task = new LootTask(new LootInput(new Target.Seen("b1"), 24, Permissions.DEFAULT),
                fakeWorld(playerChest, List.of(new ItemStack(Items.IRON_INGOT, 10))));
        TaskResult result = runTask(task);
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, result.problem().kind());
        assertTrue(result.problem().message().contains("玩家甲"));
        assertTrue(result.problem().message().contains("obtain"));
        JsonObject details = ResultJson.result(result).getAsJsonObject("details");
        assertEquals(1, details.getAsJsonArray("opened").size());
        assertEquals(0, details.getAsJsonArray("taken").size());
    }
}
