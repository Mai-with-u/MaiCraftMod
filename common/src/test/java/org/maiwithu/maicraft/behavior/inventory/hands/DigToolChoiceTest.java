// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.hands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 挑工具：主手、背包与空手放在同一本账上按每格代价挑；掉落敏感的方块不碰改掉落的附魔，
 * 预留的不自动拿来挖，换手要省得比换手代价多，挖不到掉落的候选只在不要掉落时才参与。
 */
class DigToolChoiceTest {

    /** 石头：硬度 1.5、要对的工具、精准采集或时运会改掉落。 */
    private static final DigToolChoice.Block STONE = new DigToolChoice.Block(1.5f, true, true);
    /** 圆石：硬度 2、要对的工具、不敏感。 */
    private static final DigToolChoice.Block COBBLESTONE = new DigToolChoice.Block(2.0f, true, false);
    /** 木板：硬度 2、不要对的工具、不敏感。 */
    private static final DigToolChoice.Block PLANKS = new DigToolChoice.Block(2.0f, false, false);
    /** 泥土：硬度 0.5、不要对的工具。 */
    private static final DigToolChoice.Block DIRT = new DigToolChoice.Block(0.5f, false, false);

    /** 满耐久、无附魔、每格磨 1 的一把工具。 */
    private static DigToolChoice.Candidate pick(int slot, float speed, int maxDurability, double material) {
        return piece(slot, speed, maxDurability, maxDurability, material, 1, 0, false, false, false);
    }

    /** 一件会磨耐久的东西：留多少耐久、每格磨多少、附魔附加价值、改不改掉落、带不带修补、是不是预留的。 */
    private static DigToolChoice.Candidate piece(int slot, float speed, int maxDurability, int durabilityLeft,
            double material, int wearPerBlock, double extraValue, boolean silkOrFortune, boolean mending,
            boolean kept) {
        return new DigToolChoice.Candidate(slot, false, speed, true, maxDurability, durabilityLeft,
                wearPerBlock, material, extraValue, silkOrFortune, mending, kept);
    }

    /** 一把铁剑：挖木板这类只要路通的方块跟空手一样快，磨得比镐狠，挖要够格工具的方块拿不到掉落。 */
    private static DigToolChoice.Candidate sword(int slot) {
        return new DigToolChoice.Candidate(slot, false, 1.0f, false, 250, 250, 2, 10, 0, false, false, false);
    }

    /** 36 格背包：没摆的都是空格。 */
    private static List<DigToolChoice.Candidate> backpack(DigToolChoice.Candidate... tools) {
        List<DigToolChoice.Candidate> slots = new ArrayList<>();
        for (int i = 0; i < 36; i++) slots.add(DigToolChoice.Candidate.emptySlot(i));
        for (DigToolChoice.Candidate tool : tools) slots.set(tool.slot(), tool);
        return slots;
    }

    @Test
    void 主手已是这一趟最便宜的不动() {
        var verdict = DigToolChoice.choose(backpack(pick(0, 4, 131, 1.5)), 0, COBBLESTONE, 10, true);
        assertEquals(new DigToolChoice.Verdict.Keep(), verdict);
    }

    @Test
    void 主手拿剑空手挖木板更便宜_但挖一格不值得为它换手() {
        // 剑挖木板每格 0.08 + 0.6，空手 0.6：一格只省 0.08，三格才省过换手代价 0.2。
        var sword = sword(0);
        assertEquals(new DigToolChoice.Verdict.Keep(), DigToolChoice.choose(backpack(sword), 0, PLANKS, 1, true));
        assertEquals(new DigToolChoice.Verdict.BareHand(),
                DigToolChoice.choose(backpack(sword), 0, PLANKS, 3, true));
    }

    @Test
    void 手上没工具时石镐比木镐便宜() {
        var verdict = DigToolChoice.choose(
                backpack(pick(9, 4, 131, 1.5), pick(10, 2, 59, 1)), 0, COBBLESTONE, 1, true);
        assertEquals(new DigToolChoice.Verdict.Swap(9), verdict);
    }

    @Test
    void 满耐久的钻石镐只挖一格不换_挖一片才换() {
        var slots = backpack(pick(0, 4, 131, 1.5), pick(9, 8, 1561, 50));
        assertEquals(new DigToolChoice.Verdict.Keep(), DigToolChoice.choose(slots, 0, COBBLESTONE, 1, true));
        assertEquals(new DigToolChoice.Verdict.Swap(9), DigToolChoice.choose(slots, 0, COBBLESTONE, 10, true));
    }

    @Test
    void 只剩十点耐久的钻石镐不换上来() {
        var nearlyBroken = piece(9, 8, 1561, 10, 50, 1, 0, false, false, false);
        var verdict = DigToolChoice.choose(backpack(pick(0, 4, 131, 1.5), nearlyBroken), 0, COBBLESTONE, 10, true);
        assertEquals(new DigToolChoice.Verdict.Keep(), verdict);
    }

    @Test
    void 金镐再快也换铁镐() {
        var verdict = DigToolChoice.choose(backpack(pick(0, 12, 33, 15), pick(9, 6, 250, 10)), 0, COBBLESTONE, 10,
                true);
        assertEquals(new DigToolChoice.Verdict.Swap(9), verdict);
    }

    @Test
    void 掉落敏感的方块_主手带着精准采集又对路就不换走() {
        var silk = piece(0, 8, 1561, 1561, 50, 1, DigToolChoice.DROP_CHANGING_VALUE, true, false, false);
        assertEquals(new DigToolChoice.Verdict.Keep(), DigToolChoice.choose(backpack(silk), 0, STONE, 10, true));
    }

    @Test
    void 掉落敏感的方块_背包里的精准镐也不换上来() {
        var silk = piece(9, 8, 1561, 1561, 50, 1, DigToolChoice.DROP_CHANGING_VALUE, true, false, false);
        var plain = pick(10, 4, 131, 1.5);
        assertEquals(new DigToolChoice.Verdict.Swap(10), DigToolChoice.choose(backpack(silk, plain), 0, STONE, 1, true));
    }

    @Test
    void 主手拿不对的工具_挖不到掉落时不看省多少直接换() {
        var sword = sword(0);
        var verdict = DigToolChoice.choose(backpack(sword, pick(9, 4, 131, 1.5)), 0, COBBLESTONE, 1, true);
        assertEquals(new DigToolChoice.Verdict.Swap(9), verdict);
    }

    @Test
    void 唯一够格的镐被预留着_说没有够格的() {
        // 圆石徒手：2 × 100 = 200 刻，什么都不掉。
        var reserved = piece(9, 4, 131, 131, 1.5, 1, 0, false, false, true);
        assertEquals(new DigToolChoice.Verdict.None(200), DigToolChoice.choose(backpack(reserved), 0, COBBLESTONE, 1,
                true));
    }

    @Test
    void 主手握着被预留的镐照用() {
        var held = piece(0, 4, 131, 131, 1.5, 1, 0, false, false, true);
        assertEquals(new DigToolChoice.Verdict.Keep(), DigToolChoice.choose(backpack(held), 0, COBBLESTONE, 1, true));
    }

    @Test
    void 空手最便宜而手正空着就不动() {
        assertEquals(new DigToolChoice.Verdict.Keep(), DigToolChoice.choose(backpack(), 0, DIRT, 10, true));
    }

    @Test
    void 挖一片泥土_木锹比空手便宜就换来用() {
        // 泥土徒手 15 刻，木锹 8 刻加一点磨损：一格省 0.05，一片十格才省过换手代价。
        var verdict = DigToolChoice.choose(backpack(pick(9, 2, 59, 1)), 0, DIRT, 10, true);
        assertEquals(new DigToolChoice.Verdict.Swap(9), verdict);
    }

    @Test
    void 效率附魔的镐只挖一格不换手_挖一片才换() {
        // 效率 III 的石镐：速度 4 + 3²+1 = 14。
        var efficient = pick(9, 14, 131, 1.5);
        var slots = backpack(pick(0, 6, 250, 10), efficient);
        assertEquals(new DigToolChoice.Verdict.Keep(), DigToolChoice.choose(slots, 0, COBBLESTONE, 1, true));
        assertEquals(new DigToolChoice.Verdict.Swap(9), DigToolChoice.choose(slots, 0, COBBLESTONE, 10, true));
    }

    @Test
    void 带经验修补但只剩十点耐久的木镐不换上来() {
        // 磨损按 0.2 折：1 ÷ 10 × 1 × 0.2 = 0.02，时间 30 刻；还是比石镐贵。
        var mending = piece(9, 2, 59, 10, 1, 1, 0, false, true, false);
        var verdict = DigToolChoice.choose(backpack(pick(0, 4, 131, 1.5), mending), 0, COBBLESTONE, 10, true);
        assertEquals(new DigToolChoice.Verdict.Keep(), verdict);
    }

    @Test
    void 磨到底的工具不当候选() {
        var broken = piece(9, 8, 1561, 0, 50, 1, 0, false, false, false);
        assertEquals(new DigToolChoice.Verdict.Keep(), DigToolChoice.choose(backpack(broken), 0, DIRT, 10, false));
    }

    @Test
    void 只要路通时_挖不到掉落的候选也能参与() {
        // 清障不要掉落：主手拿剑挖圆石一格 2.08，空手 2.0，一片十格省 0.8，值得腾空手。
        var sword = sword(0);
        assertEquals(new DigToolChoice.Verdict.BareHand(),
                DigToolChoice.choose(backpack(sword), 0, COBBLESTONE, 10, false));
    }

    @Test
    void 认不出材料的工具按五算() {
        assertEquals(DigToolChoice.UNKNOWN_MATERIAL_VALUE, LiveDigToolRule.materialValue("mymod:copper_pickaxe"));
        assertEquals(1.5, LiveDigToolRule.materialValue("minecraft:stone_pickaxe"));
        assertEquals(50, LiveDigToolRule.materialValue("minecraft:diamond_axe"));
    }
}
