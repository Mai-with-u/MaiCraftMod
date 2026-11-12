// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.backpack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.menu.MenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuClick;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;

/** 随身背包存放点击规划的纯判断测试：挑源格避开正打开的包与精妙背包自身，放入先并堆再占空槽并把尾数放回源格。 */
class BackpackStorePlanTest {

    private static final MenuChannel DUMMY_CHANNEL = new MenuChannel() {
        @Override public boolean stillOpen() { return true; }
        @Override public boolean cursorCarrying() { return false; }
        @Override public SlotSnapshot cursor() { return SlotSnapshot.empty(); }
        @Override public boolean click(int slot, int button) { return true; }
        @Override public boolean quickMove(int slot) { return true; }
        @Override public boolean selectTradeOffer(int offerIndex) { return false; }
        @Override public void requestClose() { }
    };

    private static final MenuSlots DUMMY_SLOTS = new MenuSlots() {
        @Override public String menuTypeId() { return "sophisticatedbackpacks:backpack"; }
        @Override public int slotCount() { return 63; }
        @Override public boolean playerBacked(int slot) { return slot >= 27; }
        @Override public int playerInventoryIndex(int slot) { return slot >= 27 ? slot - 27 : -1; }
    };

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 选源格自动跳过正在打开的主背包那只包与精妙背包物品() {
        SlotSnapshot flint64 = SlotSnapshot.of(new ItemStack(Items.FLINT, 64));
        SlotSnapshot flint12 = SlotSnapshot.of(new ItemStack(Items.FLINT, 12));
        MenuContent.Reading reading = new MenuContent.Reading(
                DUMMY_CHANNEL, DUMMY_SLOTS,
                List.of(0),
                List.of(27, 28, 29),
                List.of(0, 1, 2),
                List.of(SlotSnapshot.empty()),
                List.of(flint64, flint12, flint64));

        // 精妙背包物品本身绝不选作源格。
        assertEquals(-1, BackpackStorePlan.pickSourceIndex(reading, "sophisticatedbackpacks:backpack", 1,
                BackpackSlot.Location.mainSlot(0), id -> true, id -> id.startsWith("sophisticatedbackpacks:")));

        // 正在打开的是主背包第 0 格：第 0 格跳过，并在剩余两格里挑件数更少的第 1 格（12 件）优先腾空。
        int picked = BackpackStorePlan.pickSourceIndex(reading, "minecraft:flint", 32,
                BackpackSlot.Location.mainSlot(0), id -> id.equals("minecraft:flint"),
                id -> id.startsWith("sophisticatedbackpacks:"));
        assertEquals(1, picked);
    }

    @Test
    void 规划放入点击先并同组件未满堆再放空槽_跳过记忆筛选槽并把尾数放回源格() {
        SlotSnapshot halfFlint = SlotSnapshot.of(new ItemStack(Items.FLINT, 40));
        SlotSnapshot cobble = SlotSnapshot.of(new ItemStack(Items.COBBLESTONE, 32));
        SlotSnapshot fullFlintSource = SlotSnapshot.of(new ItemStack(Items.FLINT, 64));
        MenuContent.Reading reading = new MenuContent.Reading(
                DUMMY_CHANNEL, DUMMY_SLOTS,
                List.of(0, 1, 2, 3),
                List.of(27),
                List.of(0),
                // 0 号槽圆石、1 号槽 40 个燧石（还能并 24 个）、2 号空槽被记忆筛选锁死、3 号纯空槽。
                List.of(cobble, halfFlint, SlotSnapshot.empty(), SlotSnapshot.empty()),
                List.of(fullFlintSource));

        // 2 号槽设了别的物品记忆筛选（canStoreInSlot=false）：先并进 1 号槽 24 个，再把剩 40 个放进 3 号槽。
        BackpackStorePlan.PlannedMove move = BackpackStorePlan.planClicks(
                reading, 0, slotId -> slotId != 2).orElseThrow();
        assertEquals(27, move.sourceSlotId());
        assertEquals(List.of(
                new MenuClick(27, false, 0, true),
                new MenuClick(1, false, 0, false),
                new MenuClick(3, false, 0, false)), move.clicks());

        // 若 2、3 号空槽都被锁死不可放，只能往 1 号半满槽并入 24 个：最后补一下左键把剩 40 个放回 27 号源槽。
        BackpackStorePlan.PlannedMove partial = BackpackStorePlan.planClicks(
                reading, 0, slotId -> slotId == 1).orElseThrow();
        assertEquals(List.of(
                new MenuClick(27, false, 0, true),
                new MenuClick(1, false, 0, false),
                new MenuClick(27, false, 0, false)), partial.clicks());

        // 整只包过滤拒收：返回空计划，一下都不点。
        assertTrue(BackpackStorePlan.planClicks(reading, 0, slotId -> false).isEmpty());
    }
}
