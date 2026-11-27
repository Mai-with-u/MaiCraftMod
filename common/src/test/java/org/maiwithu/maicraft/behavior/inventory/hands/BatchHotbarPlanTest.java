// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.hands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.behavior.inventory.drops.TossesTogether;

/** 投前批量预置快捷栏规划：多料一次分槽不互相顶掉、已在快捷栏零点击、空位不足平滑回退。 */
class BatchHotbarPlanTest {

    private static List<BatchHotbarPlan.Slot> emptyBackpack() {
        List<BatchHotbarPlan.Slot> slots = new ArrayList<>();
        for (int i = 0; i <= BatchHotbarPlan.LAST_MAIN_SLOT; i++) slots.add(BatchHotbarPlan.Slot.empty());
        return slots;
    }

    private static BatchHotbarPlan.Slot movable(String itemId, int count) {
        return new BatchHotbarPlan.Slot(itemId, count, 64, HotbarSwapTarget.Holding.MOVABLE);
    }

    private static BatchHotbarPlan.Slot keep(String itemId, int count) {
        return new BatchHotbarPlan.Slot(itemId, count, 64, HotbarSwapTarget.Holding.KEEP);
    }

    @Test
    void 三样水合转化原料一次规划进不同快捷栏格且保护工具与食物格() {
        // 快捷栏 0..4 放着工具/食物/水桶，5..8 为空；主背包 9..11 各有一组原料，其中触发物只需精确 2 件。
        List<BatchHotbarPlan.Slot> slots = emptyBackpack();
        slots.set(0, keep("minecraft:iron_pickaxe", 1));
        slots.set(1, keep("minecraft:iron_sword", 1));
        slots.set(2, keep("minecraft:bread", 16));
        slots.set(3, keep("minecraft:water_bucket", 1));
        slots.set(4, keep("minecraft:torch", 32));
        slots.set(9, movable("ae2:charged_certus_quartz_crystal", 10));
        slots.set(10, movable("minecraft:redstone", 64));
        slots.set(11, movable("minecraft:quartz", 2));

        List<BatchHotbarPlan.Request> requests = List.of(
                new BatchHotbarPlan.Request("ae2:charged_certus_quartz_crystal", 2, true),
                new BatchHotbarPlan.Request("minecraft:redstone", 2, false),
                new BatchHotbarPlan.Request("minecraft:quartz", 2, false));
        BatchHotbarPlan.Plan plan = BatchHotbarPlan.plan(slots, 0, requests);

        assertEquals(3, plan.hotbarSlots().size());
        Set<Integer> unique = new HashSet<>(plan.hotbarSlots());
        assertEquals(3, unique.size(), "三样原料应分配到三个互不冲突的快捷栏格：" + plan.hotbarSlots());
        for (int assigned : plan.hotbarSlots()) {
            assertTrue(assigned >= 5 && assigned <= 8, "不应覆盖工具/食物/水桶/火把保护格：" + assigned);
        }
        assertFalse(plan.clicks().isEmpty(), "主背包原料需要经背包点击一次预置进快捷栏");
    }

    @Test
    void 原料已在快捷栏且份数合适时零点击直接认领不开背包() {
        List<BatchHotbarPlan.Slot> slots = emptyBackpack();
        slots.set(2, movable("ae2:charged_certus_quartz_crystal", 4));
        slots.set(3, movable("minecraft:redstone", 4));
        slots.set(4, movable("minecraft:quartz", 4));

        List<BatchHotbarPlan.Request> requests = List.of(
                new BatchHotbarPlan.Request("ae2:charged_certus_quartz_crystal", 4, true),
                new BatchHotbarPlan.Request("minecraft:redstone", 4, false),
                new BatchHotbarPlan.Request("minecraft:quartz", 4, false));
        BatchHotbarPlan.Plan plan = BatchHotbarPlan.plan(slots, 2, requests);

        assertEquals(List.of(2, 3, 4), plan.hotbarSlots());
        assertTrue(plan.clicks().isEmpty(), "三样已在快捷栏且份数匹配，不应打开背包：" + plan.clicks());
    }

    @Test
    void 快捷栏空位不足时预置能放下的并对其余项平滑回退不硬拒() {
        // 快捷栏 0..7 全是受保护物品，只有第 8 格空着；3 样原料在主背包中。
        List<BatchHotbarPlan.Slot> slots = emptyBackpack();
        for (int i = 0; i < 8; i++) slots.set(i, keep("minecraft:iron_pickaxe", 1));
        slots.set(9, movable("ae2:charged_certus_quartz_crystal", 2));
        slots.set(10, movable("minecraft:redstone", 2));
        slots.set(11, movable("minecraft:quartz", 2));

        List<BatchHotbarPlan.Request> requests = List.of(
                new BatchHotbarPlan.Request("ae2:charged_certus_quartz_crystal", 2, true),
                new BatchHotbarPlan.Request("minecraft:redstone", 2, false),
                new BatchHotbarPlan.Request("minecraft:quartz", 2, false));
        BatchHotbarPlan.Plan plan = BatchHotbarPlan.plan(slots, 0, requests);

        assertEquals(8, plan.hotbarSlots().get(0), "第 1 样放入唯一空闲快捷栏格 8");
        assertEquals(-1, plan.hotbarSlots().get(1), "第 2 样空位不足返回 -1，由投掷时平滑回退单样换手");
        assertEquals(-1, plan.hotbarSlots().get(2), "第 3 样空位不足返回 -1，不抛错不硬拒");
    }

    @Test
    void 同一种物品分两份投掷时按增量拆进不同快捷栏格互不抢槽() {
        // 配方里触发物一份要两件：先精确扔 2 件，再扔红石 2 件，最后补扔剩余 2 件触发物。
        List<TossesTogether.Toss> tosses = List.of(
                new TossesTogether.Toss("ae2:certus_quartz_crystal", 2, true),
                new TossesTogether.Toss("minecraft:redstone", 2, false),
                new TossesTogether.Toss("ae2:certus_quartz_crystal", 4, false));
        List<BatchHotbarPlan.Request> requests = TossesTogether.stagingRequests(tosses);
        assertEquals(2, requests.get(0).count());
        assertEquals(2, requests.get(2).count(), "同物品第二份按增量只预置补足的 2 件");

        List<BatchHotbarPlan.Slot> slots = emptyBackpack();
        slots.set(9, movable("ae2:certus_quartz_crystal", 10));
        slots.set(10, movable("minecraft:redstone", 10));
        BatchHotbarPlan.Plan plan = BatchHotbarPlan.plan(slots, 0, requests);

        assertEquals(3, new HashSet<>(plan.hotbarSlots()).size(), "两份同物品与红石各自占独立快捷栏格：" + plan.hotbarSlots());
        assertTrue(plan.hotbarSlots().stream().allMatch(slot -> slot >= 0));
    }
}
