// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import net.minecraft.client.Minecraft;

/**
 * 确认窗过期时的现场说明：把"原版自己这一下右键的结算结论""窗口内开没开界面""当前游戏模式"
 * 拼成一段话，跟在过期原因后面，让看结果的人不用再猜"那一下到底被谁挡了"。
 * 只拼现场，不做判断、不重发点击。
 */
final class ExpiryContext {

    private ExpiryContext() {}

    /** 过期现场整段话：原版结算结论在前，界面与游戏模式在后。 */
    static String of(Boolean nativeAnswer) {
        StringBuilder text = new StringBuilder(nativeAnswerClause(nativeAnswer));
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.screen != null) {
            text.append("; a menu or screen opened during the window (")
                    .append(minecraft.screen.getClass().getSimpleName())
                    .append(") but the watched fact never turned true");
        } else {
            text.append("; no menu or screen opened");
        }
        if (minecraft != null && minecraft.gameMode != null
                && minecraft.gameMode.getPlayerMode() != null) {
            text.append("; game_mode=").append(minecraft.gameMode.getPlayerMode().getName());
        }
        return text.toString();
    }

    /**
     * 原版自己的结算结论在过期报告里的说法（纯函数，离线可测）：预测就没接受，多半是原版规则
     * 当场拒绝（骑乘被拒、手上的东西这一下没有用法），服务端多半同样拒；预测接受了而确认一直没到，
     * 说明效果丢在了客户端到服务端之间，要对现场另查。没送到原生入口时结论未知，不带这一句。
     */
    static String nativeAnswerClause(Boolean nativeAnswer) {
        if (Boolean.FALSE.equals(nativeAnswer)) {
            return "; the game's own rules settled this click as not accepted (vanilla refused it on sight),"
                    + " so the server most likely refused it too";
        }
        if (Boolean.TRUE.equals(nativeAnswer)) {
            return "; the game's own rules accepted this click when it was sent,"
                    + " so the effect was lost between this client and the server";
        }
        return "";
    }
}
