// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.touhoulittlemaid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** 认领女仆的回话、好感分档：都是纯函数，按游戏事实的边界测。 */
class ReplyClaimTest {

    private static final String PERSONA = "角色设定文件已经自动生成完毕";

    private static ReplyClaim.Claimed claim(List<String> messages, boolean onlyHer, boolean sameName) {
        return ReplyClaim.claim(messages, Set.of("小雪", "酒狐"), "Mai", onlyHer, sameName, PERSONA);
    }

    @Test
    void 第一条以她名字开头的是回话_别的是提示() {
        ReplyClaim.Claimed claimed = claim(List.of("某某加入了游戏", "<小雪> 好的主人\n我这就去"), true, false);
        assertEquals("好的主人\n我这就去", claimed.reply());
        assertEquals(List.of("某某加入了游戏"), claimed.notices());
    }

    @Test
    void 皮肤名也算她的名字() {
        assertEquals("嗯", claim(List.of("<酒狐> 嗯"), false, false).reply());
    }

    @Test
    void 名字对不上但只有她在想_也认作她的回话() {
        assertEquals("你好", claim(List.of("<Wine Fox> 你好"), true, false).reply());
    }

    @Test
    void 名字对不上又有别的在想_不认() {
        ReplyClaim.Claimed claimed = claim(List.of("<Wine Fox> 你好"), false, false);
        assertNull(claimed.reply());
        assertEquals(List.of("<Wine Fox> 你好"), claimed.notices());
    }

    @Test
    void 角色自己的名字不算回话() {
        assertNull(claim(List.of("<Mai> 你好"), true, false).reply());
    }

    @Test
    void 同名的另一只也在想_分不清不认领() {
        ReplyClaim.Claimed claimed = claim(List.of("<小雪> 一", "<小雪> 二"), true, true);
        assertNull(claimed.reply());
        assertTrue(claimed.ambiguous());
        assertEquals(2, claimed.notices().size());
    }

    @Test
    void 只生成了人设_单独认() {
        ReplyClaim.Claimed claimed = claim(List.of(PERSONA), true, false);
        assertTrue(claimed.personaGenerated());
        assertTrue(claimed.answered());
        assertTrue(claimed.notices().isEmpty());
    }

    @Test
    void 只有提示_没回话() {
        ReplyClaim.Claimed claimed = claim(List.of("AI 对话功能已被禁用"), true, false);
        assertFalse(claimed.answered());
        assertEquals(List.of("AI 对话功能已被禁用"), claimed.notices());
    }

    @Test
    void 好感分档的边界() {
        assertEquals(0, Affection.of(63).level());
        assertEquals(1, Affection.of(63).toNext());
        assertEquals(1, Affection.of(64).level());
        assertEquals(1, Affection.of(191).level());
        assertEquals(2, Affection.of(192).level());
        assertEquals(2, Affection.of(383).level());
        assertEquals(3, Affection.of(384).level());
        assertNull(Affection.of(384).toNext(), "满级不给还差几点");
    }
}
