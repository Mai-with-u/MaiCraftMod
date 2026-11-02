// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 环顾：站定转头看一圈，约两秒——先转向左前方略低头，再扫到右前方，抬一下头，最后回正。
 *
 * <p>让观众看见角色在"看"：视线闸本来不看朝向，这一圈是演给观众的，转完再报看见了什么，
 * 观众看到的和报的一致。镜头按正常转头速度逐帧转过去，不瞬转。钻洞每走一段看一眼用它；
 * 别的"站着看一圈"也用这一份，不各写一遍。随时可以停：被打断后回来从那时的朝向重新转一圈。
 */
public final class LookAround implements Action {

    /** 一圈转多少刻。 */
    static final int TICKS = 40;

    /** 每一段转到哪：相对开始时朝向的左右偏转（度，负为左）、俯仰（度，正为低头），转到第几刻为止。 */
    private static final float[][] KEYFRAMES = {
            {-80f, 20f, 10},
            {80f, 20f, 26},
            {80f, -10f, 34},
            {0f, 0f, TICKS}};

    private int ticks;
    /** 开始转时的朝向；被打断后清掉，回来按那时的朝向重新转。 */
    private Float startYaw;

    @Override
    public ActionStatus tick(TickContext context) {
        PlayerContext player = context.player();
        LocalPlayer self = player == null ? null : player.localPlayer();
        if (self == null) {
            return ActionStatus.done();
        }
        if (startYaw == null) {
            startYaw = self.getYRot();
        }
        float[] target = KEYFRAMES[KEYFRAMES.length - 1];
        for (float[] frame : KEYFRAMES) {
            if (ticks < frame[2]) {
                target = frame;
                break;
            }
        }
        player.input().requestSmoothLook(startYaw + target[0], target[1], player.clientTick());
        ticks++;
        return ticks >= TICKS ? ActionStatus.done() : ActionStatus.running();
    }

    @Override
    public void pause() {
        // 被打断（自卫、吃东西）回来时人可能已经转了向：从那时的朝向重新转一整圈。
        ticks = 0;
        startYaw = null;
    }

    @Override
    public Interruptibility interruptibility() {
        return Interruptibility.BETWEEN_ACTIONS;
    }

    @Override
    public String describe() {
        return "转头看一圈";
    }
}
