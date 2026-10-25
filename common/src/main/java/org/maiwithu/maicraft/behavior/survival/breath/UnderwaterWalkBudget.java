// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.breath;

/**
 * 水下行走呼吸预算：走路把头潜进水里赶路时，剩下的氧气还允不允许继续走。
 *
 * <p>纯函数：读只读的生存处境（与溺水判断同一条线），回答"继续走 / 放弃并上浮"。
 * 眼睛没在水里、能在水下呼吸时不归它管。走路段不能等换气临时任务来救命——
 * 氧气不够浮上去是硬底线；氧气落到换气需求开始找空当的区间（不到三分之一）还不浮头，
 * 就会变成换气临时任务反复插进来、走路段又把身体按回水下的拉锯，潜得越深余量越小。
 * 到了这两条线，走路段该自己放弃这段路浮上去，把身体交给换气链。
 */
public final class UnderwaterWalkBudget {

    /** 预算的结论，直接回答"这段水下路还能不能继续走"。 */
    public enum Verdict {
        /** 氧气还够，继续走。 */
        KEEP_WALKING,
        /** 氧气见底，放弃这段路，浮上水面换气。 */
        GIVE_UP_AND_SURFACE
    }

    private UnderwaterWalkBudget() {}

    /** 这段水下路此刻还能不能继续走；处境按溺水判断的同一份读法取。 */
    public static Verdict judge(DrowningDanger.View situation) {
        if (!situation.headInWater() || situation.canBreatheUnderwater()) {
            return Verdict.KEEP_WALKING;
        }
        int air = situation.airTicks();
        // 浮不上去是底线；氧气不到三分之一是换气需求要插空的线，潜走不该越过它。
        if (air <= situation.airNeededToSurface() || air * 3 < situation.maxAirTicks()) {
            return Verdict.GIVE_UP_AND_SURFACE;
        }
        return Verdict.KEEP_WALKING;
    }
}
