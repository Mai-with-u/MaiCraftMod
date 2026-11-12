// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.containers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;
import org.maiwithu.maicraft.behavior.menu.MenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.permission.ContainerWhose;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.event.EventFacts;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventLog;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 容器搜刮动作测试：按价值分级从贵到贱搬运、建材与垃圾留下标 not_worth、背包满时优先拿高价值且能并进半堆、
 * 别人或自己放的箱子只看不拿。
 */
class LootsContainerTest {
    @TempDir Path tempDir;

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

    private static final class FakeLootBox implements OpenedMenu {
        final List<ItemStack> box = new ArrayList<>();
        final List<ItemStack> player = new ArrayList<>();
        boolean closed;

        FakeLootBox() {
            for (int i = 0; i < 27; i++) box.add(ItemStack.EMPTY);
            for (int i = 0; i < 36; i++) player.add(ItemStack.EMPTY);
        }

        @Override
        public Optional<MenuContent.Reading> reading() {
            if (closed) return Optional.empty();
            List<Integer> boxIds = new ArrayList<>();
            List<SlotSnapshot> boxSnapshots = new ArrayList<>();
            for (int i = 0; i < box.size(); i++) {
                boxIds.add(i);
                boxSnapshots.add(box.get(i).isEmpty() ? SlotSnapshot.empty() : SlotSnapshot.of(box.get(i)));
            }
            List<Integer> playerIds = new ArrayList<>();
            List<Integer> playerIndices = new ArrayList<>();
            List<SlotSnapshot> playerSnapshots = new ArrayList<>();
            for (int i = 0; i < player.size(); i++) {
                playerIds.add(27 + i);
                playerIndices.add(i < 27 ? 9 + i : i - 27);
                playerSnapshots.add(player.get(i).isEmpty() ? SlotSnapshot.empty() : SlotSnapshot.of(player.get(i)));
            }
            return Optional.of(new MenuContent.Reading(CHANNEL, SLOTS, boxIds, playerIds, playerIndices,
                    boxSnapshots, playerSnapshots));
        }

        @Override public boolean busy() { return false; }
        @Override public boolean cursorEmpty() { return true; }
        @Override public SlotSnapshot cursor() { return SlotSnapshot.empty(); }

        @Override
        public boolean quickMove(int slotId) {
            ItemStack moving = box.get(slotId);
            if (moving.isEmpty()) return true;
            // 按原版快速移动顺序：先并进同种未满堆，再进空格，塞不下的留在箱里。
            for (ItemStack carried : player) {
                if (!carried.isEmpty() && ItemStack.isSameItemSameComponents(carried, moving)
                        && carried.getCount() < carried.getMaxStackSize()) {
                    int add = Math.min(moving.getCount(), carried.getMaxStackSize() - carried.getCount());
                    carried.grow(add);
                    moving.shrink(add);
                    if (moving.isEmpty()) {
                        box.set(slotId, ItemStack.EMPTY);
                        return true;
                    }
                }
            }
            for (int i = 0; i < player.size(); i++) {
                if (player.get(i).isEmpty()) {
                    player.set(i, moving.copy());
                    box.set(slotId, ItemStack.EMPTY);
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
                @Override
                public ActionStatus tick(TickContext context) {
                    closed = true;
                    return ActionStatus.done();
                }

                @Override public String describe() { return "关箱"; }
            };
        }

        @Override public void abandon() { closed = true; }
    }

    private record ImmediateOpen(FakeLootBox box) implements MenuOpening {
        @Override public Optional<OpenedMenu> opened() { return Optional.of(box); }
        @Override public ActionStatus tick(TickContext context) { return ActionStatus.done(); }
        @Override public String describe() { return "打开替身箱"; }
    }

    private static ActionStatus runToEnd(Action action) {
        for (long t = 1; t <= 80; t++) {
            ActionStatus status = action.tick(TickContext.of(t, null));
            if (!(status instanceof ActionStatus.Running)) return status;
        }
        throw new AssertionError("80 刻内没有结束");
    }

    @Test
    void 拿走稀罕与有用物品并留下建材与垃圾_触发里程碑事件() throws InterruptedException {
        WorldMemory memory = new WorldMemory(new DocumentStore(tempDir.resolve("state.db")), "w1");
        TaskEventLog log = new TaskEventLog();
        RareLootNotice notice = new RareLootNotice(null, id -> id.startsWith("lootr:"), memory, new TaskEventSink() {
            @Override public void publish(TaskEvent.Kind kind, String message) { log.publish(kind, message); }
            @Override public void publishInAction(TaskEvent.Kind kind, String message, EventFacts facts) {
                log.appendForGoal(kind, 1259L, "maicraft:loot", message, facts);
            }
        });

        FakeLootBox box = new FakeLootBox();
        box.box.set(0, new ItemStack(Items.COBBLESTONE, 12)); // building -> not_worth
        box.box.set(1, new ItemStack(Items.IRON_INGOT, 15));  // useful -> 拿
        box.box.set(2, new ItemStack(Items.HEART_OF_THE_SEA, 1)); // precious -> 拿 + rare_finds
        box.box.set(3, new ItemStack(Items.ROTTEN_FLESH, 4)); // junk -> not_worth

        WorldPosition pos = new WorldPosition(-311, 59, 73, "minecraft:overworld");
        LootsContainer loots = new LootsContainer("战利品箱", "lootr:lootr_chest", pos,
                new ContainerWhose(ContainerWhose.Kind.NATURAL_LOOT, null, false),
                new ImmediateOpen(box), ItemsToKeep.NOTHING, memory, notice);

        assertInstanceOf(ActionStatus.Done.class, runToEnd(loots));
        LootsContainer.Report report = loots.report().orElseThrow();
        assertFalse(report.backpackFull());
        assertFalse(report.nonLootRefused());
        assertEquals(4, report.opened().size());
        assertEquals(2, report.taken().size());
        assertEquals(2, report.left().size());
        assertTrue(report.left().stream().allMatch(item -> "not_worth".equals(item.why())));
        assertEquals(1, report.rareFinds().size());
        assertEquals("minecraft:heart_of_the_sea", report.rareFinds().getFirst().item());
        assertEquals(1, report.rareFinds().getFirst().taken());

        List<TaskEvent> events = log.read(null, 0, OptionalLong.empty(), 10, 0).events();
        assertEquals(1, events.size());
        assertEquals(TaskEvent.Kind.RARE_LOOT_FOUND, events.getFirst().kind());
    }

    @Test
    void 背包剩一格加半堆时按优先级先拿预留与稀罕物并合并同种半堆_其余标no_room() {
        FakeLootBox box = new FakeLootBox();
        // 把角色背包填到只剩 1 个空格，且第 0 格已有 54 个铁锭（还能再并进 10 个）。
        box.player.set(0, new ItemStack(Items.IRON_INGOT, 54));
        for (int i = 1; i < 35; i++) {
            box.player.set(i, new ItemStack(Items.DIRT, 64));
        }
        // 箱子里有食物（面包）、有用的（铁锭 15 个）、稀罕贵重（海洋之心 1 个）。
        box.box.set(0, new ItemStack(Items.BREAD, 8));
        box.box.set(1, new ItemStack(Items.IRON_INGOT, 15));
        box.box.set(2, new ItemStack(Items.HEART_OF_THE_SEA, 1));

        WorldPosition pos = new WorldPosition(10, 50, 10, "minecraft:overworld");
        LootsContainer loots = new LootsContainer("地牢箱", "minecraft:chest", pos,
                new ContainerWhose(ContainerWhose.Kind.NO_RECORD, null, false),
                new ImmediateOpen(box), ItemsToKeep.NOTHING, null, RareLootNotice.NONE);

        assertInstanceOf(ActionStatus.Done.class, runToEnd(loots));
        LootsContainer.Report report = loots.report().orElseThrow();
        assertTrue(report.backpackFull());
        // 唯一空格先装下 precious 的海洋之心，铁锭并进已有半堆 10 个剩 5 个，面包完全没地方放剩 8 个。
        assertEquals(2, report.taken().size());
        assertTrue(report.taken().stream().anyMatch(t -> t.item().equals("minecraft:heart_of_the_sea") && t.count() == 1));
        assertTrue(report.taken().stream().anyMatch(t -> t.item().equals("minecraft:iron_ingot") && t.count() == 10));
        assertTrue(report.left().stream().anyMatch(l -> l.item().equals("minecraft:iron_ingot")
                && l.count() == 5 && "no_room".equals(l.why())));
        assertTrue(report.left().stream().anyMatch(l -> l.item().equals("minecraft:bread")
                && l.count() == 8 && "no_room".equals(l.why())));
    }

    @Test
    void 别人放的箱子只看不拿并标明非战利品容器() {
        FakeLootBox box = new FakeLootBox();
        box.box.set(0, new ItemStack(Items.HEART_OF_THE_SEA, 1));

        WorldPosition pos = new WorldPosition(5, 64, 5, "minecraft:overworld");
        LootsContainer loots = new LootsContainer("玩家箱", "minecraft:chest", pos,
                new ContainerWhose(ContainerWhose.Kind.PLAYER, "玩家甲", false),
                new ImmediateOpen(box), ItemsToKeep.NOTHING, null, RareLootNotice.NONE);

        assertInstanceOf(ActionStatus.Done.class, runToEnd(loots));
        LootsContainer.Report report = loots.report().orElseThrow();
        assertTrue(report.nonLootRefused());
        assertTrue(report.taken().isEmpty());
        assertEquals(1, report.opened().size());
        assertEquals(1, box.box.get(0).getCount());
    }
}
