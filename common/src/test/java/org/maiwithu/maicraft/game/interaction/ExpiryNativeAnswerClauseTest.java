// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 确认窗过期报告里原版结算结论的说法：骑船这类不开界面、不改世界的用法，确认窗等到过期时，
 * 报告要分清「原版规则当场拒了」还是「点击被接受了、效果丢在了客户端到服务端之间」，
 * 没送到原生入口时不冒充有结论。
 */
class ExpiryNativeAnswerClauseTest {

    @Test
    void aRefusedClickSaysTheServerMostLikelyRefusedItToo() {
        String clause = ExpiryContext.nativeAnswerClause(false);
        assertTrue(clause.contains("settled this click as not accepted"));
        assertFalse(clause.contains("lost between"));
    }

    @Test
    void anAcceptedClickPointsAtTheClientServerGap() {
        String clause = ExpiryContext.nativeAnswerClause(true);
        assertTrue(clause.contains("accepted this click"));
        assertTrue(clause.contains("lost between this client and the server"));
    }

    @Test
    void anUnsentClickCarriesNoVerdict() {
        assertEquals("", ExpiryContext.nativeAnswerClause(null));
    }
}
