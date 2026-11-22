// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.cook;

import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 到工作站跟前点开界面的接缝：推进到界面点开并同步完、交出界面读端与已认领的界面。
 * 生产实现由 {@link StationVisit} 走到设施跟前右键打开，离线测试换成直接交出替身界面的实现。
 */
interface StationOpening {

    /** 推进一刻：界面打开并同步完时返回完成，之后通过读端与已认领界面操作。 */
    ActionStatus tick(TickContext tick);

    /** 点开的那份界面的读端；还没点开时为空。 */
    MenuContent menus();

    /** 认领刚刚点开的那份界面，供搬运执行点击与核对；认领不到时为空。 */
    OpenedMenu claimMenu();

    /** 被生存需求打断时暂停尚未完成的走向与点击。 */
    void pause();

    /** 收尾尚未完成的走向与点击；已打开的界面由使用方负责关闭。 */
    void close();
}
