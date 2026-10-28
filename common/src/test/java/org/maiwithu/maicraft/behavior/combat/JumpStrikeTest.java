// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 跳劈的起跳时机按刻数掐：离下一刀 7 刻以内起跳，够格正好落在往下落的那几刻里，快慢武器都一样。 */
class JumpStrikeTest {

    private static final BattleMoves.Posture READY_TO_JUMP = new BattleMoves.Posture(true, false, true);

    /** 原版平地起跳：初速 0.42，每刻先减重力 0.08 再乘阻力 0.98；返回第几刻开始往下落、第几刻落回原地。 */
    private static int[] vanillaJump() {
        double y = 0;
        double v = 0.42;
        int firstFalling = -1;
        for (int tick = 1; tick <= 40; tick++) {
            double before = y;
            y += v;
            v = (v - 0.08) * 0.98;
            if (firstFalling < 0 && y < before) firstFalling = tick;
            if (y <= 0) return new int[] {firstFalling, tick};
        }
        throw new AssertionError("跳起来没落地");
    }

    @Test
    void vanillaJumpStartsFallingAtTickSevenAndLandsAtTwelve() {
        int[] jump = vanillaJump();
        assertEquals(JumpStrike.JUMP_LEAD_TICKS, jump[0], "起跳后第 7 刻开始往下落");
        assertEquals(12, jump[1], "第 12 刻落地");
    }

    @Test
    void jumpsSevenTicksBeforeTheNextStrikeWhateverTheWeapon() {
        // 剑、铁斧、重锤：离下一刀 8 刻不跳，7 刻起跳——够格那一刻是起跳后第 7 刻，落在往下落的第 7～11 刻里。
        int[] jump = vanillaJump();
        JumpStrike leap = new JumpStrike();
        assertFalse(leap.decide(READY_TO_JUMP, 8, true, true, () -> false).jumpNow(), "还差 8 刻：不跳");
        assertTrue(leap.decide(READY_TO_JUMP, 7, true, true, () -> false).jumpNow(), "还差 7 刻：起跳");
        int readyAt = JumpStrike.JUMP_LEAD_TICKS;
        assertTrue(readyAt >= jump[0] && readyAt < jump[1], "够格落在往下落的那几刻里");
    }

    @Test
    void fastWeaponJumpsAsSoonAsItLands() {
        // 空手 5 刻就充满：落地就跳，往下落时早已满条。
        JumpStrike leap = new JumpStrike();
        assertTrue(leap.decide(READY_TO_JUMP, 5, true, true, () -> false).jumpNow());
        assertTrue(new JumpStrike().decide(READY_TO_JUMP, 0, true, true, () -> false).jumpNow());
    }

    @Test
    void creepersAndOutOfReachTargetsAreNotLeaptAt() {
        assertFalse(new JumpStrike().decide(READY_TO_JUMP, 3, true, false, () -> false).jumpNow(), "苦力怕不跳");
        assertFalse(new JumpStrike().decide(READY_TO_JUMP, 3, false, true, () -> false).jumpNow(), "够不着不跳");
    }
}
