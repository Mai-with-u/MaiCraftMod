// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import baritone.api.utils.IInputOverrideHandler;
import baritone.api.utils.input.Input;
import baritone.behavior.PathingBehavior;
import org.maiwithu.maicraft.game.player.PlayerInput;

/**
 * 引擎这一刻按下的键合成角色的走路按键：方向、跳跃、潜行和疾跑；路线在手而引擎这一刻没按键时站住、
 * 等引擎接着按，还在算路又没按键时不给按键。
 */
final class EngineKeys {

    private EngineKeys() {}

    /**
     * 这一刻的走路按键；不给按键为 null。
     *
     * @param sprintAllowed 调用方让不让跑
     */
    static PlayerInput.Movement movement(IInputOverrideHandler input, PathingBehavior pathing, boolean sprintAllowed,
                                         boolean routePresent, boolean calculating) {
        float forward = (input.isInputForcedDown(Input.MOVE_FORWARD) ? 1F : 0F)
                - (input.isInputForcedDown(Input.MOVE_BACK) ? 1F : 0F);
        float strafe = (input.isInputForcedDown(Input.MOVE_LEFT) ? 1F : 0F)
                - (input.isInputForcedDown(Input.MOVE_RIGHT) ? 1F : 0F);
        boolean jump = input.isInputForcedDown(Input.JUMP);
        boolean sneak = input.isInputForcedDown(Input.SNEAK);
        boolean sprint = WalkSprint.sprint(input, pathing, sprintAllowed);
        if (forward != 0F || strafe != 0F || jump || sneak) {
            return new PlayerInput.Movement(forward, strafe, jump, sneak, sprint);
        }
        // 路线在手、引擎这一刻没按键：保持站立，镜头照样按住路线朝向。
        return routePresent && !calculating ? PlayerInput.Movement.STOPPED : null;
    }
}
