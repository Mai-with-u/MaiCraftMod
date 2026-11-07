// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.InBedChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.player.Input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 角色输入的离线部分：移动量的取值校验、哪些界面允许继续走路、
 * 转头时的世界方向合成、镜头平滑的几何性质，以及连续蹲着点时续按潜行的判断。
 * 写进真实玩家输入的部分需要活着的客户端对象，只在实机验收里核对。
 */
class LocalPlayerInputTest {

    @Test
    void movementRejectsOutOfRangeImpulses() {
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerInput.Movement(1.5f, 0f, false, false, false), "前后量必须在 -1～1");
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerInput.Movement(0f, Float.NaN, false, false, false), "左右量必须是有限数值");
        assertEquals(0.5f, new PlayerInput.Movement(0.5f, -1f, true, false, true).forward());
    }

    @Test
    void chatDoesNotStopWalkingButBedChatAndMenusDo() {
        // 聊天框提供移动信号而非键盘事件，沿指定路线行走时可以保留；床上的聊天界面必须静止等自然醒。
        assertTrue(LocalPlayerInput.permitsWorldMovement(new ChatScreen("")),
                "普通聊天框不打断走路");
        assertTrue(LocalPlayerInput.permitsWorldMovement(null), "没有界面时可以走路");
        assertFalse(LocalPlayerInput.permitsWorldMovement(new InBedChatScreen()),
                "床上界面必须保持静止");
        assertFalse(LocalPlayerInput.permitsWorldMovement(new PauseScreen(true)), "暂停菜单不允许继续走");
    }

    @Test
    void steeringKeepsWorldDirectionWhenCameraTurns() {
        // 朝向变了以后，移动算法想要的那个世界方向不能跟着镜头转：按键按新朝向重新合成。
        // 世界方向 = -forward*sin(yaw) + strafe*cos(yaw)（东西），forward*cos(yaw) + strafe*sin(yaw)（南北）。
        float oldYaw = 0f;
        float nextYaw = 90f;
        PlayerInput.Movement original = new PlayerInput.Movement(1f, 0f, false, false, false);
        double[] before = worldDirection(original, oldYaw);
        PlayerInput.Movement rotated = LocalPlayerInput.preserveHeading(original, oldYaw, nextYaw);
        double[] after = worldDirection(rotated, nextYaw);
        assertEquals(before[0], after[0], 1e-6, "转头不得旋转原本想要的世界方向（东西分量）");
        assertEquals(before[1], after[1], 1e-6, "转头不得旋转原本想要的世界方向（南北分量）");
    }

    private static double[] worldDirection(PlayerInput.Movement movement, float yaw) {
        double rad = Math.toRadians(yaw);
        return new double[]{
                -movement.forward() * Math.sin(rad) + movement.strafe() * Math.cos(rad),
                movement.forward() * Math.cos(rad) + movement.strafe() * Math.sin(rad)};
    }

    @Test
    void smoothDampAngleTakesTheShorterWayAround() {
        // 从 179° 到 -179° 只差 2°：第一步必须经 180° 折过去（179.46…），而不是反向退回 178.5…。
        float value = 179f;
        float velocity = 0f;
        boolean crossedShortSide = false;
        for (int i = 0; i < 60 && Math.abs(value - (-179f)) > 0.5f; i++) {
            LocalPlayerInput.AxisStep step = LocalPlayerInput.smoothDampAngle(value, -179f, velocity, 0.11f, 240f, 0.05f);
            value = step.value();
            velocity = step.velocity();
            if (value > 180f || value < -179f) crossedShortSide = true;
        }
        assertTrue(crossedShortSide, "左右转头走较短的一边，先越过 180°");
        // 平滑器在连续角度上工作；181° 与 -179° 是同一个朝向。
        assertEquals(181f, value, 0.5f, "沿短边收敛到与目标相同的朝向");
    }

    @Test
    void smoothDampSettlesExactlyOnTarget() {
        // 阻尼每帧只收敛一部分；余差小于容差时直接吸附，调用方的“已对准”判定才能成立。
        LocalPlayerInput.AxisStep far = LocalPlayerInput.smoothDamp(0f, 90f, 0f, 0.11f, 240f, 0.05f);
        assertTrue(far.value() > 0f && far.value() < 90f, "远距离先朝目标推进一段");
        assertTrue(far.velocity() > 0f, "推进时保留角速度供下一帧继续");
        LocalPlayerInput.AxisStep settled = LocalPlayerInput.smoothDamp(89.9f, 90f, 0f, 0.11f, 240f, 0.05f);
        assertEquals(90f, settled.value(), 1e-3, "余差小到不可分辨时吸附到目标");
        assertEquals(0f, settled.velocity(), 1e-3, "吸附后角速度归零");
    }

    @Test
    void keptSneakHoldsWhileStandingStillAndLetsGoOnceSheMoves() {
        // 一段施工里蹲着点完一下：0.5 秒内站着不动就接着蹲，下一下直接接上，不在两下之间起身。
        PlayerInput.Movement still = PlayerInput.Movement.STOPPED;
        LocalPlayerInput.KeptSneak waiting = LocalPlayerInput.keptSneak(still, 110, 105, true, false);
        assertTrue(waiting.keep(), "还没到期、站着不动：接着续");
        assertTrue(waiting.command().sneaking(), "没人要蹲也写出潜行");
        // 要走、要跳：立刻站起来走，不蹲着拖慢走路。
        PlayerInput.Movement walking = new PlayerInput.Movement(1f, 0f, false, false, true);
        LocalPlayerInput.KeptSneak walks = LocalPlayerInput.keptSneak(walking, 110, 105, true, false);
        assertFalse(walks.keep(), "要走就不再续");
        assertFalse(walks.command().sneaking(), "走路照旧站着");
        assertTrue(walks.command().sprinting(), "走路的按键原样写出");
        PlayerInput.Movement jumping = new PlayerInput.Movement(0f, 0f, true, false, false);
        assertFalse(LocalPlayerInput.keptSneak(jumping, 110, 105, true, false).keep(), "要跳就不再续");
        // 到期、开着界面、泡在水里（蹲着会往下沉）：不再续。
        assertFalse(LocalPlayerInput.keptSneak(still, 110, 111, true, false).command().sneaking(), "过了 0.5 秒没接上就起身");
        assertFalse(LocalPlayerInput.keptSneak(still, 110, 105, false, false).keep(), "开着界面不续");
        assertFalse(LocalPlayerInput.keptSneak(still, 110, 105, true, true).keep(), "在水里不续");
    }

    @Test
    void sprintKeyFollowsAutomationOnlyWhileBotInputIsInstalled() {
        // 自动化持有身体：原版读到的疾跑键就是自动化按没按，键盘上按着也不算。
        LocalPlayerInput.BotInput bot = new LocalPlayerInput.BotInput();
        bot.sprintHeld = true;
        assertTrue(LocalPlayerInput.BotInput.sprintKeyDown(bot, false), "自动化按住疾跑键");
        bot.sprintHeld = false;
        assertFalse(LocalPlayerInput.BotInput.sprintKeyDown(bot, true), "自动化松开时不跟键盘走");
        // 人自己操作：身上是键盘输入，读值原样不变。
        Input keyboard = new Input();
        assertTrue(LocalPlayerInput.BotInput.sprintKeyDown(keyboard, true), "人按着疾跑键");
        assertFalse(LocalPlayerInput.BotInput.sprintKeyDown(keyboard, false), "人没按疾跑键");
    }
}
