// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 看一眼用的界面：眼前开着什么界面，和把顺手开的界面关掉。容器、背包按原版关（光标上的东西还回背包），
 * 配方查看器这类不是容器的界面按它自己的关法关（EMI 的配方页退回垫着的背包，下一刻再关背包）。
 */
public final class GlanceScreens {

    /** 等原版关容器界面的期限（刻）。 */
    private static final int CLOSE_TICKS = 20;

    private GlanceScreens() {}

    /** 眼前开着界面没有（聊天框、暂停菜单也算）。 */
    public static boolean anyOpen() {
        return Minecraft.getInstance().screen != null;
    }

    /** 关掉眼前的界面：容器与背包交给原版关，关的过程由菜单入口逐刻推进；别的界面按它自己的关法关。 */
    public static void close(PlayerContext player) {
        Screen screen = Minecraft.getInstance().screen;
        LocalPlayer self = player == null ? null : player.localPlayer();
        if (screen == null || self == null) return;
        if (screen instanceof AbstractContainerScreen<?> || self.containerMenu != self.inventoryMenu) {
            // 上一次关界面还没结清就不再叫：同一个界面不请原版关两次。
            if (player.menuActions().hasPendingTransaction()) return;
            try {
                player.menuActions().closeForTaskBoundary(player, CLOSE_TICKS, "看完一眼关上顺手开的界面");
            } catch (RuntimeException notNow) {
                // 这一刻交不出关界面（交互机会已用掉）：下一刻再关；要用世界的动作自己也会先关界面。
            }
            return;
        }
        screen.onClose();
    }
}
