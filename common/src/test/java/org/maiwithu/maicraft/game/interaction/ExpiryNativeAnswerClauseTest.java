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
        assertTrue(clause.contains("原版当场就没接受这一下"));
        assertFalse(clause.contains("丢在了客户端到服务端之间"));
    }

    @Test
    void anAcceptedClickPointsAtTheClientServerGap() {
        String clause = ExpiryContext.nativeAnswerClause(true);
        assertTrue(clause.contains("原版发出时接受了这一下"));
        assertTrue(clause.contains("丢在了客户端到服务端之间"));
    }

    @Test
    void anUnsentClickCarriesNoVerdict() {
        assertEquals("", ExpiryContext.nativeAnswerClause(null));
    }

    /** 原版当场拒了时，提交现场命中的拒收条件跟着进收场报文；点击被接受时不带这段，避免把接受读成拒收。 */
    @Test
    void aRefusedEntityClickCarriesTheOnSightConditionsItHit() {
        EntityUseScene sneakHeld = new EntityUseScene(true, 1.5, 3.0, false, null);
        assertTrue(ExpiryContext.of(false, sneakHeld).contains("潜行键还按着"));
        assertFalse(ExpiryContext.of(true, sneakHeld).contains("潜行键还按着"));
    }
}
