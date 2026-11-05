// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.level.GameType;

/**
 * 确认窗过期时的现场说明：把"原版自己这一下右键的结算结论""窗口内开没开界面""当前游戏模式"
 * 拼成一段话，跟在过期原因后面，让看结果的人不用再猜"那一下到底被谁挡了"。
 * 只拼现场，不做判断、不重发点击；界面按玩家看到的标题说，不报界面的类名。
 */
final class ExpiryContext {

    private ExpiryContext() {}

    /** 过期现场整段话：读此刻客户端开着的界面与游戏模式，交给纯函数拼句。 */
    static String of(Boolean nativeAnswer, EntityUseScene entityUseScene) {
        Minecraft minecraft = Minecraft.getInstance();
        String openScreen = minecraft == null || minecraft.screen == null ? null : screenTitle(minecraft.screen);
        GameType mode = minecraft == null || minecraft.gameMode == null ? null : minecraft.gameMode.getPlayerMode();
        return describe(nativeAnswer, entityUseScene, openScreen, mode);
    }

    /**
     * 过期现场整段话（纯函数，离线可测）：原版结算结论在前，命中的拒收条件、界面与游戏模式在后。
     *
     * @param openScreen 确认窗里开着的界面标题；没开界面为 null，开了但没有标题为空串
     * @param mode       当前游戏模式；读不到为 null，不带这一句
     */
    static String describe(Boolean nativeAnswer, EntityUseScene entityUseScene, String openScreen, GameType mode) {
        StringBuilder text = new StringBuilder(nativeAnswerClause(nativeAnswer));
        // 原版当场拒了才带拒收条件：点击被接受时带上它会把接受读成拒收。
        if (Boolean.FALSE.equals(nativeAnswer) && entityUseScene != null) {
            text.append(entityUseScene.explain());
        }
        // 开着界面说明这一下可能被界面接走了（开了箱子、交易界面），但要等的那条变化没出现，两件事都如实说。
        if (openScreen == null) {
            text.append("；没有开任何界面");
        } else if (openScreen.isBlank()) {
            text.append("；确认期间开着一个界面，但要等的变化一直没出现");
        } else {
            text.append("；确认期间开着「").append(openScreen).append("」界面，但要等的变化一直没出现");
        }
        if (mode != null) {
            text.append("；游戏模式是").append(modeName(mode));
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
            return "；原版当场就没接受这一下，服务端多半也拒了";
        }
        if (Boolean.TRUE.equals(nativeAnswer)) {
            return "；原版发出时接受了这一下，效果丢在了客户端到服务端之间";
        }
        return "";
    }

    /** 游戏模式按玩家在游戏里看到的叫法说，不报枚举原名。 */
    static String modeName(GameType mode) {
        return switch (mode) {
            case SURVIVAL -> "生存";
            case CREATIVE -> "创造";
            case ADVENTURE -> "冒险";
            case SPECTATOR -> "旁观";
        };
    }

    // 界面按玩家看到的标题说（箱子、熔炉、村民的职业名）；没有标题的界面交回空串。
    private static String screenTitle(Screen screen) {
        return screen.getTitle() == null ? "" : screen.getTitle().getString();
    }
}
