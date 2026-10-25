// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.maiwithu.maicraft.behavior.inventory.containers.KnownContainer;

/** 腾挪计划：合并最先，能存的从最不值钱的存起；不替玩家丢东西，腾不出如实失败并把出路列进建议。 */
class SpacePlannerTest {

    private static final KnownContainer 家门口的箱子 = new KnownContainer("家门口的箱子", WorldPosition.here(12, -60, 208), "minecraft:chest");

    private static BackpackStack stack(String itemId, int count) {
        return new BackpackStack(itemId, count, 64, false, false, false, false);
    }

    private static BackpackStack junk(String itemId, int count) {
        return new BackpackStack(itemId, count, 64, false, false, false, true);
    }

    private static SpacePlanner.Scene scene(List<BackpackStack> stacks, int freeSlots, int needed,
                                            boolean carried, List<KnownContainer> containers) {
        // 有随身背包时给它足够的空格，测试只关心先后顺序。
        return new SpacePlanner.Scene(stacks, freeSlots, needed, Set.of(), true, carried ? 27 : 0, containers);
    }

    @Test
    void 散堆先合并_合并不挪东西() {
        SpacePlanner.Plan plan = SpacePlanner.plan(scene(
                List.of(stack("minecraft:bone", 30), stack("minecraft:bone", 30), stack("minecraft:bone", 10)),
                0, 1, false, List.of()));
        assertEquals(1, plan.moves().size());
        SpaceMove.MergeStacks merge = (SpaceMove.MergeStacks) plan.moves().getFirst();
        assertEquals("minecraft:bone", merge.itemId());
        // 70 个骨头散在三格，合成两格（64+6）：腾出要的一格。
        assertEquals(1, merge.slotsFreed());
        assertNull(plan.problem());
    }

    @Test
    void 合并做不到的格子不计进计划() {
        // 三堆各 40 个：两两都并不进一格（40+40 超过上限），纯合并腾不出格子；
        // 没有随身背包、没有容器、没有网络：计划不出现合并，也不往地上扔，如实失败。
        SpacePlanner.Plan plan = SpacePlanner.plan(scene(
                List.of(junk("minecraft:cobblestone", 40), junk("minecraft:cobblestone", 40),
                        junk("minecraft:cobblestone", 40)),
                0, 1, false, List.of()));
        assertTrue(plan.moves().stream().noneMatch(move -> move instanceof SpaceMove.MergeStacks));
        assertTrue(plan.moves().isEmpty());
        assertEquals(Problem.Kind.INVENTORY_FULL, plan.problem().kind());
        assertTrue(plan.problem().suggestion().contains("minecraft:cobblestone"), plan.problem().suggestion());
    }

    @Test
    void 有随身背包先往里塞() {
        SpacePlanner.Plan plan = SpacePlanner.plan(scene(
                List.of(stack("minecraft:bone", 3)), 0, 1, true, List.of()));
        assertEquals(new SpaceMove.ToCarriedBackpack(stack("minecraft:bone", 3)), plan.moves().getFirst());
    }

    @Test
    void 附近有记得的容器优先存() {
        SpacePlanner.Plan plan = SpacePlanner.plan(scene(
                List.of(junk("minecraft:cobblestone", 64)), 0, 1, false, List.of(家门口的箱子)));
        assertEquals(new SpaceMove.ToKnownContainer(家门口的箱子, junk("minecraft:cobblestone", 64)),
                plan.moves().getFirst());
    }

    @Test
    void 没有地方可存也不往地上扔_如实失败带建议() {
        // 没有随身背包、没有容器、没有网络：宁可失败，也不把玩家的东西扔到地上。
        List<BackpackStack> stacks = List.of(junk("minecraft:cobblestone", 64));
        SpacePlanner.Plan plan = SpacePlanner.plan(scene(stacks, 0, 1, false, List.of()));
        assertTrue(plan.moves().isEmpty());
        assertEquals(Problem.Kind.INVENTORY_FULL, plan.problem().kind());
        assertTrue(plan.problem().suggestion().contains("drop_items"), plan.problem().suggestion());
        assertTrue(plan.problem().suggestion().contains("manage_container"), plan.problem().suggestion());
        // 建议照现场列：背包里真有的圆石出现在可丢候选里，丢哪堆由调用方定。
        assertTrue(plan.problem().suggestion().contains("minecraft:cobblestone"), plan.problem().suggestion());
        assertTrue(plan.problem().suggestion().contains("候选"), plan.problem().suggestion());
    }

    @Test
    void 能存的堆从最不值钱的存起() {
        // 圆石攒了两堆（共 74 个，超过一组）才算垃圾；一堆以内的圆石是建材，食物比建材贵，都排在垃圾后面。
        List<BackpackStack> stacks = List.of(
                junk("minecraft:cobblestone", 64),
                junk("minecraft:cobblestone", 10),
                stack("minecraft:cooked_beef", 8));
        // 随身背包只有两格：只塞得下两堆，从垃圾排起。
        SpacePlanner.Scene limited = new SpacePlanner.Scene(stacks, 0, 2, Set.of(), true, 2, List.of());
        assertEquals(List.of(
                new SpaceMove.ToCarriedBackpack(junk("minecraft:cobblestone", 64)),
                new SpaceMove.ToCarriedBackpack(junk("minecraft:cobblestone", 10))),
                SpacePlanner.plan(limited).moves());
    }

    @Test
    void 三废石和泥土排在圆石前面() {
        // 一样都是垃圾：先存安山岩、泥土这类没用的，开局要做石器的圆石排最后。
        List<BackpackStack> stacks = List.of(
                junk("minecraft:cobblestone", 64),
                junk("minecraft:cobblestone", 20),
                junk("minecraft:andesite", 40),
                junk("minecraft:dirt", 30),
                stack("minecraft:rotten_flesh", 5));
        SpacePlanner.Plan plan = SpacePlanner.plan(scene(stacks, 0, 3, true, List.of()));
        assertEquals(List.of(new SpaceMove.ToCarriedBackpack(stack("minecraft:rotten_flesh", 5)),
                new SpaceMove.ToCarriedBackpack(junk("minecraft:dirt", 30)),
                new SpaceMove.ToCarriedBackpack(junk("minecraft:andesite", 40))), plan.moves());
    }

    @Test
    void 贵重品和装备不进计划_建议里也不出现() {
        BackpackStack sword = new BackpackStack("minecraft:iron_sword", 1, 1, true, false, false, false);
        BackpackStack pearl = new BackpackStack("minecraft:ender_pearl", 2, 16, false, false, true, false);
        SpacePlanner.Plan plan = SpacePlanner.plan(scene(List.of(sword, pearl), 0, 2, false, List.of()));
        assertTrue(plan.moves().isEmpty());
        // 只剩装备和贵重品：如实失败，不问"同不同意丢"，建议里也不出现丢了难补的东西。
        assertEquals(Problem.Kind.INVENTORY_FULL, plan.problem().kind());
        assertTrue(!plan.problem().suggestion().contains("iron_sword")
                && !plan.problem().suggestion().contains("ender_pearl"), plan.problem().suggestion());
    }

    @Test
    void 任务留用的东西不算进能存与候选() {
        BackpackStack reserved = stack("minecraft:iron_ingot", 30);
        SpacePlanner.Scene withReserved = new SpacePlanner.Scene(
                List.of(reserved), 0, 1, Set.of("minecraft:iron_ingot"), true, 0, List.of());
        SpacePlanner.Plan plan = SpacePlanner.plan(withReserved);
        // 唯一的物品是任务要用的：不自动动它，也不把它列进可丢候选。
        assertTrue(plan.moves().isEmpty());
        assertEquals(Problem.Kind.INVENTORY_FULL, plan.problem().kind());
        assertTrue(!plan.problem().suggestion().contains("iron_ingot"), plan.problem().suggestion());
    }

    @Test
    void 零碎先存_有用的也存_腾不出时建议里都有() {
        // 实机：背包里链条、蜘蛛眼、生羊肉、树苗——腾两格存蜘蛛眼和生羊肉，链条也能存；
        // 都存不了时建议把零碎和有用的都列为候选，取舍交调用方。
        List<BackpackStack> stacks = List.of(
                stack("minecraft:chain", 32),
                stack("minecraft:spider_eye", 1),
                new BackpackStack("minecraft:mutton", 2, 64, false, true, false, false),
                junk("minecraft:spruce_sapling", 3));
        SpacePlanner.Plan plan = SpacePlanner.plan(scene(stacks, 0, 2, true, List.of()));
        assertEquals(List.of(new SpaceMove.ToCarriedBackpack(stack("minecraft:spider_eye", 1)),
                new SpaceMove.ToCarriedBackpack(new BackpackStack("minecraft:mutton", 2, 64, false, true, false, false))),
                plan.moves());

        SpacePlanner.Plan nothingStorable = SpacePlanner.plan(scene(stacks, 0, 2, false, List.of()));
        assertTrue(nothingStorable.moves().isEmpty());
        String suggestion = nothingStorable.problem().suggestion();
        assertTrue(suggestion.contains("minecraft:spider_eye") && suggestion.contains("minecraft:chain"), suggestion);
    }

    @Test
    void 有用的能存就存() {
        SpacePlanner.Plan plan = SpacePlanner.plan(scene(List.of(stack("minecraft:chain", 32)), 0, 1, false,
                List.of(家门口的箱子)));
        assertEquals(new SpaceMove.ToKnownContainer(家门口的箱子, stack("minecraft:chain", 32)), plan.moves().getFirst());
    }

    @Test
    void 身上的终端连着网络就先存进网络() {
        // 整理背包往 AE 里放：附近有箱子也先存网络，有用的、零碎都存。
        List<BackpackStack> stacks = List.of(stack("minecraft:chain", 32), stack("minecraft:spider_eye", 1));
        SpacePlanner.Scene scene = new SpacePlanner.Scene(stacks, 0, 2, Set.of(), true, 0, List.of(家门口的箱子), true);
        // 两样攒成一步：终端打开一次存完再关。
        assertEquals(List.of(new SpaceMove.ToNetwork(List.of(stack("minecraft:spider_eye", 1),
                stack("minecraft:chain", 32)))), SpacePlanner.plan(scene).moves());
    }

    @Test
    void 随身背包满了接着存箱子() {
        // 随身背包只剩一格：第一堆放进去，第二堆存进记得的箱子；没有别的路也不丢。
        List<BackpackStack> stacks = List.of(junk("minecraft:dirt", 64), junk("minecraft:gravel", 64),
                junk("minecraft:sand", 64));
        SpacePlanner.Scene scene = new SpacePlanner.Scene(stacks, 0, 3, Set.of(), false, 1,
                List.of(new KnownContainer("门口的箱子", WorldPosition.here(1, 64, 1), "minecraft:chest")));
        SpacePlanner.Plan plan = SpacePlanner.plan(scene);
        List<SpaceMove> moves = plan.moves();
        assertTrue(moves.get(0) instanceof SpaceMove.ToCarriedBackpack);
        assertTrue(moves.get(1) instanceof SpaceMove.ToKnownContainer);
        assertEquals(2, moves.size());
        // 要三格只腾得出两格：如实失败，建议里给出那只记得的箱子的位置。
        assertEquals(Problem.Kind.INVENTORY_FULL, plan.problem().kind());
        assertTrue(plan.problem().suggestion().contains("门口的箱子")
                && plan.problem().suggestion().contains("1,64,1"), plan.problem().suggestion());
        assertTrue(plan.problem().suggestion().contains("drop_items"), plan.problem().suggestion());
    }

    @Test
    void 有网络时建议里提存网络() {
        // 网络能存但背包里只剩贵重品：不自动动它，失败建议里仍指出可以往网络里存别的。
        SpacePlanner.Scene scene = new SpacePlanner.Scene(
                List.of(new BackpackStack("minecraft:ender_pearl", 2, 16, false, false, true, false)),
                0, 1, Set.of(), false, 0, List.of(), true);
        SpacePlanner.Plan plan = SpacePlanner.plan(scene);
        assertTrue(plan.problem().suggestion().contains("存储网络"), plan.problem().suggestion());
        assertTrue(!plan.problem().suggestion().contains("ender_pearl"), plan.problem().suggestion());
    }
}
