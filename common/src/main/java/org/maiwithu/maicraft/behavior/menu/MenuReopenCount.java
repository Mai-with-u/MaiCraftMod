// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

/**
 * 界面被意外关掉后的重开账：被打断时请游戏关上的不算意外；意外关掉几次以内回到设施跟前
 * 重开接着做，一再被关就承认这条路走不下去，如实失败。照看炉子、工作站这类要开着界面干的活共用这一份账。
 */
public final class MenuReopenCount {

    /** 意外关掉最多重开几次；再关就是这条路走不下去。 */
    private static final int LIMIT = 3;

    private int unexpected;

    /** 界面又被关掉了一次：还准不准重开。打断时关的不算意外，次数也不计。 */
    public boolean mayReopen(boolean closedByPause) {
        return closedByPause || ++unexpected <= LIMIT;
    }
}
