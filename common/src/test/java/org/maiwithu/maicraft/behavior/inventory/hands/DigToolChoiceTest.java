// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.hands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** 挑工具：三态结论、能掉落先于不掉落、最省优先、改掉落的附魔不掺和、预留的不挑、徒手也掉的算时间账。 */
class DigToolChoiceTest {

    private static final DigToolChoice.Block STONE = new DigToolChoice.Block(1.5f, true, true);
    private static final DigToolChoice.Block COBBLESTONE = new DigToolChoice.Block(2.0f, true, false);
    private static final DigToolChoice.Block DIRT = new DigToolChoice.Block(0.5f, false, false);

    /** 一把普通工具。 */
    private static DigToolChoice.Candidate tool(int slot, float speed, boolean drops, int maxDurability) {
        return new DigToolChoice.Candidate(slot, false, speed, drops, true, maxDurability, maxDurability,
                false, false, false);
    }

    /** 36 格背包：没摆的都是空格。 */
    private static List<DigToolChoice.Candidate> backpack(DigToolChoice.Candidate... tools) {
        List<DigToolChoice.Candidate> slots = new ArrayList<>();
        for (int i = 0; i < 36; i++) slots.add(DigToolChoice.Candidate.emptySlot(i));
        for (DigToolChoice.Candidate tool : tools) slots.set(tool.slot(), tool);
        return slots;
    }

    @Test
    void 主手已是合适的就不动() {
        var verdict = DigToolChoice.choose(backpack(tool(0, 4, true, 131)), 0, COBBLESTONE, 10, 0);
        assertEquals(new DigToolChoice.Verdict.Keep(), verdict);
    }

    @Test
    void 徒手挖不掉落_换能掉落的() {
        var verdict = DigToolChoice.choose(backpack(tool(20, 2, true, 59)), 0, COBBLESTONE, 1, 0);
        assertEquals(new DigToolChoice.Verdict.Swap(20), verdict);
    }

    @Test
    void 没有合适的_说清徒手一格要多久() {
        // 圆石徒手：2 × 100 = 200 刻，什么都不掉。
        var verdict = DigToolChoice.choose(backpack(), 0, COBBLESTONE, 1, 0);
        assertEquals(new DigToolChoice.Verdict.None(200), verdict);
    }

    @Test
    void 石镐铁镐同场挑石镐_主手是铁镐也换下来() {
        var stoneAndIron = backpack(tool(1, 4, true, 131), tool(2, 6, true, 250));
        assertEquals(new DigToolChoice.Verdict.Swap(1), DigToolChoice.choose(stoneAndIron, 5, COBBLESTONE, 10, 0));
        assertEquals(new DigToolChoice.Verdict.Swap(1), DigToolChoice.choose(stoneAndIron, 2, COBBLESTONE, 10, 0));
    }

    @Test
    void 无附魔与有附魔同场挑无附魔() {
        var enchanted = new DigToolChoice.Candidate(1, false, 8, true, true, 1561, 1561, false, true, false);
        var verdict = DigToolChoice.choose(backpack(enchanted, tool(2, 6, true, 250)), 5, COBBLESTONE, 10, 0);
        assertEquals(new DigToolChoice.Verdict.Swap(2), verdict);
    }

    @Test
    void 掉落敏感的方块_主手带丝触不换走_背包有丝触也不换上() {
        var silk = new DigToolChoice.Candidate(0, false, 8, true, true, 1561, 1561, true, true, false);
        var plain = tool(1, 6, true, 250);
        assertEquals(new DigToolChoice.Verdict.Keep(), DigToolChoice.choose(backpack(silk, plain), 0, STONE, 10, 0));
        var silkInBackpack = new DigToolChoice.Candidate(9, false, 8, true, true, 1561, 1561, true, true, false);
        assertEquals(new DigToolChoice.Verdict.None(150), DigToolChoice.choose(backpack(silkInBackpack), 0, STONE, 10, 0));
    }

    @Test
    void 不敏感的方块_只有带附魔的也拿来挖() {
        var fortune = new DigToolChoice.Candidate(9, false, 8, true, true, 1561, 1561, true, true, false);
        assertEquals(new DigToolChoice.Verdict.Swap(9), DigToolChoice.choose(backpack(fortune), 0, COBBLESTONE, 1, 0));
    }

    @Test
    void 预留的工具不挑_主手握着的照用() {
        var reservedPick = new DigToolChoice.Candidate(9, false, 8, true, true, 1561, 1561, false, false, true);
        assertEquals(new DigToolChoice.Verdict.None(200), DigToolChoice.choose(backpack(reservedPick), 0, COBBLESTONE, 1, 0));
        var heldReserved = new DigToolChoice.Candidate(0, false, 8, true, true, 1561, 1561, false, false, true);
        assertEquals(new DigToolChoice.Verdict.Keep(), DigToolChoice.choose(backpack(heldReserved), 0, COBBLESTONE, 1, 0));
    }

    @Test
    void 徒手也掉的_挖两下不换锹_连挖一片才换() {
        // 泥土徒手 15 刻、木锹 8 刻：一格只省 7 刻不换；挖十格省 70 刻就换。
        var shovel = tool(9, 2, true, 59);
        assertEquals(new DigToolChoice.Verdict.Keep(), DigToolChoice.choose(backpack(shovel), 0, DIRT, 1, 0));
        assertEquals(new DigToolChoice.Verdict.Swap(9), DigToolChoice.choose(backpack(shovel), 0, DIRT, 10, 0));
        assertEquals(new DigToolChoice.Verdict.None(15), DigToolChoice.choose(backpack(), 0, DIRT, 10, 0));
    }

    @Test
    void 耐久撑不住这一批的不挑() {
        var worn = new DigToolChoice.Candidate(9, false, 6, true, true, 250, 5, false, false, false);
        assertEquals(new DigToolChoice.Verdict.None(200), DigToolChoice.choose(backpack(worn), 0, COBBLESTONE, 1, 10));
    }
}
