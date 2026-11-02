// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.looking;

import net.minecraft.util.Mth;

/**
 * 不紧不慢地转头：每刻把要镜头去的方向朝目标挪一段，镜头照常平滑追上，转多快由这里定。
 *
 * <p>游戏接口层的平滑转头是为干活瞄准调的，最快每秒 240 度；闲着看东西用不着那么急，
 * 一下转过去像被人拽了一把。所以这里不直接把目标交出去，而是按每秒多少度一段段地挪，
 * 等镜头真的到了目标附近才算转到位。
 */
final class HeadMotion {

    /** 镜头离目标多近算转到位（度）。类别：玩家常识（看东西不用对准到一个像素）。 */
    static final float ARRIVED_DEGREES = 2.5f;

    private final float degreesPerTick;
    private Float commandedYaw;
    private Float commandedPitch;
    private float targetYaw;
    private float targetPitch;

    /** @param degreesPerSecond 转头速度，每秒多少度 */
    HeadMotion(float degreesPerSecond) {
        this.degreesPerTick = degreesPerSecond / 20f;
    }

    /** 换一个要看的方向；从镜头此刻所在接着转，不跳。 */
    void aim(float yaw, float pitch) {
        targetYaw = Mth.wrapDegrees(yaw);
        targetPitch = Mth.clamp(pitch, -90f, 90f);
    }

    /** 推进一刻：把要镜头去的方向挪一段并发出去；镜头已经到了目标附近返回真。 */
    boolean step(TurnsHead head) {
        if (commandedYaw == null) {
            commandedYaw = head.yaw();
            commandedPitch = head.pitch();
        }
        float[] next = toward(commandedYaw, commandedPitch, targetYaw, targetPitch, degreesPerTick);
        commandedYaw = next[0];
        commandedPitch = next[1];
        head.turnToward(commandedYaw, commandedPitch);
        return arrived(head.yaw(), head.pitch(), targetYaw, targetPitch);
    }

    /** 从这一刻起重新按镜头的实际位置接着转（暂停回来、被别的事转过镜头之后）。 */
    void resync() {
        commandedYaw = null;
        commandedPitch = null;
    }

    /**
     * 从一个方向朝另一个方向挪最多这么多度：左右走近路（跨过正负一百八十度），上下照直；
     * 两个方向按同一个比例挪，斜着转过去，不先左右再上下。纯函数。
     */
    static float[] toward(float fromYaw, float fromPitch, float toYaw, float toPitch, float maxDegrees) {
        float dYaw = Mth.wrapDegrees(toYaw - fromYaw);
        float dPitch = toPitch - fromPitch;
        float distance = (float) Math.sqrt(dYaw * dYaw + dPitch * dPitch);
        if (distance <= maxDegrees || distance == 0f) {
            return new float[] {Mth.wrapDegrees(toYaw), toPitch};
        }
        float share = maxDegrees / distance;
        return new float[] {Mth.wrapDegrees(fromYaw + dYaw * share), fromPitch + dPitch * share};
    }

    /** 镜头到了目标附近没有。纯函数。 */
    static boolean arrived(float yaw, float pitch, float toYaw, float toPitch) {
        return Math.abs(Mth.wrapDegrees(toYaw - yaw)) <= ARRIVED_DEGREES && Math.abs(toPitch - pitch) <= ARRIVED_DEGREES;
    }
}
