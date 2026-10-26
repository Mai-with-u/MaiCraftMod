// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.Optional;

import net.minecraft.world.inventory.AbstractContainerMenu;

import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 一次终端界面会话共用的东西：到网络跟前的办法（认终端、走到、点开）、此刻开着的界面与重开终端。
 * 从终端取货与往网络存东西的各段都对着这一份干活；界面被服务端换成哪一页由界面自己跟着换绑，
 * 这里只管「此刻开着的这一份」是谁、什么时候换一份新的。
 */
class TerminalSession {

    private final TerminalAccess access;
    private final Ae2Compat compat;
    private MenuOpening opening;
    private OpenedMenu menu;

    TerminalSession(TerminalAccess access, Ae2Compat compat) {
        this.access = access;
        this.compat = compat;
        this.opening = access.opening();
    }

    TerminalAccess access() {
        return access;
    }

    Ae2Compat compat() {
        return compat;
    }

    MenuOpening opening() {
        return opening;
    }

    OpenedMenu menu() {
        return menu;
    }

    // 此刻开着的界面两侧读数；还没点开、被关掉或被别的界面顶掉时为空。
    Optional<MenuContent.Reading> reading() {
        return menu == null ? Optional.empty() : menu.reading();
    }

    // 此刻游戏里开着的原界面：读网络存货、下单换页的确认都对着它；角色不在世界里时为 null。
    // 离线测试换掉这里，对着替身的页核对。
    AbstractContainerMenu rawPage(PlayerContext player) {
        return player == null || player.localPlayer() == null ? null : player.localPlayer().containerMenu;
    }

    // 点开并认下刚打开的界面；取货在它上面再加换页跟随与搬运执行。
    void bindMenu() {
        menu = opening.opened().orElseThrow();
    }

    // 重开终端（下单开工后 AE2 退回终端）：关掉此刻的界面，下一次打开重新来。
    void reopen() {
        if (menu != null) menu.abandon();
        opening = access.opening();
        menu = null;
    }

    // 中途撒手时请游戏关一次此刻开着的界面（被打断、被取消）：光标上的东西由原版的关闭流程还回背包。
    void abandonMenu() {
        if (menu != null) menu.abandon();
    }
}
