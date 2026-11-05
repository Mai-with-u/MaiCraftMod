// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.menu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 界面有没有动作在用：同一个界面实例、这一刻或上一刻刚用过才算；隔了两刻没人续用、换成另一个界面、
 * 什么都没开着，都不算，走路照关。
 */
class ScreenInUseTest {

    private final Object backpack = new Object();
    private final ScreenInUse record = new ScreenInUse();

    @Test
    void theSameScreenUsedThisTickOrLastTickIsInUse() {
        // 落地备料第 10 刻开了背包：第 10、11 刻走路收拾界面时都看得到它正被用着。
        record.used(backpack, 10);
        assertTrue(record.inUse(backpack, 10));
        assertTrue(record.inUse(backpack, 11));
    }

    @Test
    void aScreenNobodyRenewsIsNoLongerInUse() {
        // 备料结束、不再续记：隔两刻留下的背包就没人用了，走路照关。
        record.used(backpack, 10);
        assertFalse(record.inUse(backpack, 12));
    }

    @Test
    void anotherScreenInstanceIsNotTheOneInUse() {
        // 关掉重开的背包、服务端自己弹出的界面：不是原来那一个实例，不算有人在用。
        record.used(backpack, 10);
        assertFalse(record.inUse(new Object(), 10));
        assertFalse(record.inUse(null, 10));
    }

    @Test
    void nothingOpenIsNotRecorded() {
        // 用的那一刻什么都没开着：不留记录，之后开的界面不会被误当成有人在用。
        record.used(null, 10);
        assertFalse(record.inUse(backpack, 10));
    }
}
