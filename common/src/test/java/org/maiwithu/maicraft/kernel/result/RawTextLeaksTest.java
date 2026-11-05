// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 结果文字检查本身：实机抓到的那句漏英文的说明要被认出来，给 LLM 用的名字要放过。 */
class RawTextLeaksTest {

    /** 实机里用东西没生效时 LLM 看到的原话：方块对象串和英文确认记录两处都要报。 */
    @Test
    void theLeakSeenInPlayIsCaughtOnBothCounts() {
        String seen = "目标格提交前是Block{biomesoplenty:black_sand}，现在是黑沙；确认记录：the confirmation window"
                + " expired with no net change in hand item, target block, menu or nearby entities;"
                + " the submitted action did not take effect";
        var leaks = RawTextLeaks.find(seen);
        assertTrue(leaks.stream().anyMatch(leak -> leak.contains("Block{")), "方块对象串要报出来");
        assertTrue(leaks.stream().anyMatch(leak -> leak.contains("confirmation window")), "英文确认记录要报出来");
    }

    /** 注册 ID、MCP 字段名、枚举值是给 LLM 用的名字，连着写也不算英文句子。 */
    @Test
    void namesForTheModelAreNotSentences() {
        RawTextLeaks.assertNone("dig_tool 点名的工具不在身上；手上是 minecraft:diamond_pickaxe minecraft:stone"
                + " biomesoplenty:black_sand；问题 REFUSED_BY_GAME UNREACHABLE STUCK；use_on_trace 见细节");
        RawTextLeaks.assertNone("点击点离眼睛 4.2 格，坐标 (1.5, 64.0, -3.25)，ME 网络");
    }

    @Test
    void objectToStringTracesAreCaught() {
        assertFalse(RawTextLeaks.find("手上是 Item{minecraft:stone}").isEmpty());
        assertFalse(RawTextLeaks.find("界面 net.minecraft.client.gui.screens.ChatScreen@1a2b3c4d").isEmpty());
        assertFalse(RawTextLeaks.find("开着 InventoryScreen 界面").isEmpty(), "界面类名不该进句子");
        assertFalse(RawTextLeaks.find("出错：IllegalStateException").isEmpty(), "异常类名不该进句子");
    }

    @Test
    void anEnglishSentenceIsCaughtButAWordOrTwoIsNot() {
        assertEquals(1, RawTextLeaks.find("确认记录：awaiting authoritative client facts").size());
        assertTrue(RawTextLeaks.find("方块 Stone 与 Oak Door").isEmpty(), "一两个英文词（测试环境的方块名）不算句子");
    }
}
