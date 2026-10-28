// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 攻击充能：离下一刀还差几刻按 20 ÷ 攻速算，主手这一刻才换上的不算够格。 */
class AttackChargeTest {

    /** 攻击条充满要几刻：原版是 20 ÷ 攻速。 */
    private static double full(double attackSpeed) {
        return 20.0 / attackSpeed;
    }

    @Test
    void ticksToReadyFollowTheWeaponsAttackSpeed() {
        // 剑 1.6 → 12.5 刻充满，到 0.95 要 12 刻；铁斧 0.9 → 22 刻；重锤 0.6 → 32 刻；空手 4.0 → 5 刻。
        assertEquals(12, AttackCharge.ticksToReady(0f, full(1.6)));
        assertEquals(22, AttackCharge.ticksToReady(0f, full(0.9)));
        assertEquals(32, AttackCharge.ticksToReady(0f, full(0.6)));
        assertEquals(5, AttackCharge.ticksToReady(0f, full(4.0)));
    }

    @Test
    void readyAtNinetyFivePercent() {
        assertEquals(0, AttackCharge.ticksToReady(0.95f, full(1.6)), "到 0.95 就够格");
        assertEquals(0, AttackCharge.ticksToReady(1f, full(1.6)));
        // 剑走到第 11 刻（0.88）还差一刻。
        assertEquals(1, AttackCharge.ticksToReady(11 / 12.5f, full(1.6)));
        // 钻石斧 20 刻充满：从零起正好 19 刻，不因浮点多算一刻。
        assertEquals(19, AttackCharge.ticksToReady(0f, full(1.0)));
    }

    @Test
    void handSwappedThisTickIsNotReadyEvenWithAFullBar() {
        // 这一刻刚换上的：读到的满条是旧东西的，至少还差一刻。
        assertFalse(AttackCharge.handSettled(100, 100));
        assertEquals(1, AttackCharge.ticksToReady(1f, full(1.6), 100, 100));
        // 上一刻就拿着了：按攻击条算。
        assertTrue(AttackCharge.handSettled(99, 100));
        assertEquals(0, AttackCharge.ticksToReady(1f, full(1.6), 99, 100));
        assertEquals(12, AttackCharge.ticksToReady(0f, full(1.6), 99, 100));
        // 早就拿着（没接上换手记账）。
        assertTrue(AttackCharge.handSettled(Long.MIN_VALUE, 100));
    }
}
