// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sleep;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 白天结论宽限的判定：/time 改动刚交服务端时客户端的日子同步晚一拍，首次评估先不结论。 */
class SleepModuleDaytimeGraceTest {

    @Test
    void 首次评估先等一拍() {
        assertFalse(SleepModule.daytimeGraceOver(null, 40));
    }

    @Test
    void 恰好隔一拍的复核带着新读数结论() {
        assertTrue(SleepModule.daytimeGraceOver(40L, 41L));
    }

    @Test
    void 同刻的重复评估仍算同一拍() {
        assertFalse(SleepModule.daytimeGraceOver(40L, 40L));
    }

    @Test
    void 中间隔了不止一拍的再等一拍() {
        assertFalse(SleepModule.daytimeGraceOver(40L, 45L));
    }
}
