// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.looking;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.StandingFacing;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 转头的接缝：读镜头此刻朝哪、站定时朝哪，请求镜头转向一个方向，开始与结束"只为看东西转头"。
 *
 * <p>偏航 0 朝南、顺时针增大；俯仰正数是低头。只在当刻有效：每刻按那一刻的上下文取一份。
 * 生产实现接在角色的视角输入与站定朝向上，离线测试换成记账的替身。
 */
public interface TurnsHead {

    /** 镜头此刻的偏航。 */
    float yaw();

    /** 镜头此刻的俯仰。 */
    float pitch();

    /** 角色站定时朝哪（只为看东西转头时不跟着头走）。 */
    float standingYaw();

    /** 开始只为看东西转头：记下站定朝向。 */
    void hold();

    /** 不再为看东西转头：镜头停在哪就是哪。 */
    void letGo();

    /** 本刻要镜头朝这里转；下一刻不再要就停在当时转到的地方。 */
    void turnToward(float yaw, float pitch);

    /** 按这一刻的角色上下文取一份；不在世界里时为 null。 */
    static TurnsHead of(TickContext context) {
        PlayerContext player = context == null ? null : context.player();
        return player == null || player.localPlayer() == null ? null : new Live(player);
    }

    /** 生产实现：读本地玩家的镜头，经角色的视角输入转头，站定朝向记在控制边界那一份上。 */
    final class Live implements TurnsHead {
        private final PlayerContext player;

        Live(PlayerContext player) {
            this.player = player;
        }

        @Override public float yaw() {
            return player.localPlayer().getYRot();
        }

        @Override public float pitch() {
            return player.localPlayer().getXRot();
        }

        @Override public float standingYaw() {
            return player.facingYaw();
        }

        @Override public void hold() {
            StandingFacing facing = player.standingFacing();
            if (facing != null) facing.hold(yaw());
        }

        @Override public void letGo() {
            StandingFacing facing = player.standingFacing();
            if (facing != null) facing.letGo(yaw());
        }

        @Override public void turnToward(float yaw, float pitch) {
            // 人按 F8 拿回了角色就不碰镜头；转头请求只在这一刻有效，下一刻不续就停住。
            if (player.input() == null || !player.input().automationOwnsControls()) return;
            player.input().requestSmoothLook(yaw, pitch, player.clientTick());
        }
    }
}
