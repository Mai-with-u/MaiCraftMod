// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

/**
 * 挖一格之前的工具安排：主手已经是这一趟最划算的、要换到哪一格、还是腾空主手拿空手挖。
 *
 * <p>挑工具的账在玩家行为层算（{@code behavior.inventory.hands.DigToolChoice}），这里只装结论，
 * 供挖掘器在真正开挖前一步步兑现：换手要切快捷栏或开背包搬东西，都是跨刻的原生动作。
 */
public sealed interface DigToolPlan {

    /** 不动：主手已经是这一趟最划算的，或者手上没有更合适的可换。 */
    record Keep() implements DigToolPlan {}

    /** 换到这一格再挖。 */
    record Hold(int slot) implements DigToolPlan {}

    /** 空手最划算：把手里的东西收起来，腾空主手再挖。 */
    record BareHand() implements DigToolPlan {}

    static DigToolPlan keep() {
        return new Keep();
    }

    static DigToolPlan hold(int slot) {
        return new Hold(slot);
    }

    static DigToolPlan bareHand() {
        return new BareHand();
    }
}
