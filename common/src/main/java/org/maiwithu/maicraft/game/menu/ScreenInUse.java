// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.menu;

/**
 * 眼前开着的界面此刻有没有动作正在用：动作每刻用到一个界面（打开它、等它画出来、在上面挪东西、等确认），
 * 就记下那一个界面实例和这一刻。走路要按 Esc 收拾挡路的界面时先问这里：同一个界面、这一刻或上一刻刚有人用过，
 * 就不关——落地前把干草块换到快捷栏，背包是这一下自己开的，关掉它两边会来回争到认输。
 *
 * <p>记录不用谁去撤：用它的动作结束或被打断，不再续记，过一刻就不算了，留下的界面照关；界面关掉重开、
 * 服务端或插件自己弹出别的界面，实例对不上，也照关。看上一刻是因为走路收拾界面排在落地备料之前，
 * 同一刻里备料还没来得及续记。
 */
final class ScreenInUse {

    /** 最近一次用到的界面，只拿来比是不是同一个实例。 */
    private Object screen;
    private long tick = Long.MIN_VALUE;

    /** 动作这一刻用到了这个界面；什么都没开着时不记。 */
    void used(Object screen, long tick) {
        if (screen == null) return;
        this.screen = screen;
        this.tick = tick;
    }

    /** current 是不是正有人用着的那一个界面：同一个实例，最近一次用在这一刻或上一刻。 */
    boolean inUse(Object current, long now) {
        return current != null && current == screen && now >= tick && now - tick <= 1;
    }
}
