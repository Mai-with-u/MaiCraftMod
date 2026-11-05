// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 挖一格什么时候算做完：方块在客户端先消失，可这一下挖掘还没结清时，要先让挖掘器收下这一下再报做完；
 * 直接报做完的话，挖下来的那一格进不了结果里的工具现场，磨掉的耐久也没处结算。
 */
class LiveBlockDiggingTest {

    @Test
    void 方块没了但这一下还没结清时先不报做完() {
        assertFalse(LiveBlockDigging.finishable(true, true));
    }

    @Test
    void 方块没了而且这一下已经结清就算做完() {
        assertTrue(LiveBlockDigging.finishable(true, false));
    }

    @Test
    void 方块还在就接着挖_不管有没有挥下去() {
        assertFalse(LiveBlockDigging.finishable(false, true));
        assertFalse(LiveBlockDigging.finishable(false, false));
    }
}
