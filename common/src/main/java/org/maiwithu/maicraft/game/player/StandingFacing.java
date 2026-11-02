// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import net.minecraft.util.Mth;

/**
 * 站定朝向：角色只为看东西转头时，"朝哪边"仍按站定时的方向算。
 *
 * <p>观察里的前后左右、方向目标的"往前""往左"都按角色朝哪边算。闲着时转头看人、看箱子，
 * 或者站着环顾一圈，都只是转头：LLM 刚读到"羊在右前方"，接着下达"往前 20 格"，走的应当是它读观察时的那个前方。
 * 所以开始为看东西转头时记下此刻镜头的偏航，转头期间读朝向的都拿它。
 *
 * <p>收手时镜头可能还停在看过去的方向（新目标在转头半路进来）：在别的事把镜头转走之前，朝向仍按站定的算，
 * 新目标第一刻解析"往前"拿到的就是读观察时那个前方；镜头偏开收手那一刻的方向超过一度，就说明有别的事接管了镜头，
 * 回到镜头此刻的朝向。只管左右：相对方位只按偏航算，抬头低头不影响。
 *
 * <p>只在客户端线程上读写；换了身体（重生、过门、换世界）由控制边界清掉。
 */
public final class StandingFacing {

    /** 收手后镜头偏开收手那一刻的方向超过多少度，算别的事接管了镜头。类别：玩家常识（手一动就不是站着看了）。 */
    static final float MOVED_AWAY_DEGREES = 1.0f;

    /** 站定朝向；没在为看东西转头时为 null。 */
    private Float standingYaw;
    /** 收手那一刻镜头的偏航；还在转头看时为 null。 */
    private Float leftAtYaw;

    /**
     * 开始为看东西转头：还没记过站定朝向就记下此刻镜头的偏航；已经在记着（连着看几样、收手后镜头没动又接着看）就不改。
     *
     * @param cameraYaw 镜头此刻的偏航
     */
    public void hold(float cameraYaw) {
        facing(cameraYaw);
        if (standingYaw == null) standingYaw = Mth.wrapDegrees(cameraYaw);
        leftAtYaw = null;
    }

    /**
     * 收手：不再为看东西转头。镜头停在哪就是哪，站定朝向留着，直到别的事把镜头转走。
     *
     * @param cameraYaw 镜头此刻的偏航
     */
    public void letGo(float cameraYaw) {
        if (standingYaw != null && leftAtYaw == null) leftAtYaw = Mth.wrapDegrees(cameraYaw);
    }

    /**
     * 角色此刻朝哪边：在为看东西转头、或收手后镜头还没被别的事转走时是站定朝向，否则就是镜头此刻的偏航。
     *
     * @param cameraYaw 镜头此刻的偏航
     */
    public float facing(float cameraYaw) {
        if (standingYaw == null) return cameraYaw;
        if (leftAtYaw != null && Math.abs(Mth.wrapDegrees(cameraYaw - leftAtYaw)) > MOVED_AWAY_DEGREES) {
            // 收手后镜头被别的事转走了：站定朝向作废，此后按镜头算。
            standingYaw = null;
            leftAtYaw = null;
            return cameraYaw;
        }
        return standingYaw;
    }

    /** 此刻是不是在为看东西转头（收手后的那一段不算）。 */
    public boolean holding() {
        return standingYaw != null && leftAtYaw == null;
    }

    /** 换了身体：上一具身体站定朝哪与这一具无关。 */
    void forget() {
        standingYaw = null;
        leftAtYaw = null;
    }
}
