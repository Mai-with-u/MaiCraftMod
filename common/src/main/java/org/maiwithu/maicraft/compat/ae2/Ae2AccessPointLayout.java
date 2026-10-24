// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.List;
import java.util.Set;

import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.spi.MenuLayoutProof;

/**
 * 无线访问点界面的布局证明：角色背包那一侧是普通的 36 格；容器那一侧是三格工作格
 * （信号增强格、绑定格、产出格）。把终端放进绑定格后它会从原格消失、以绑定副本的样子出现在产出格，
 * 三格都按机器格核对（源格确切减少才算数，不按放置回显）。增强格不是绑定用的，点击判定照机器格走，不主动点它。
 */
public final class Ae2AccessPointLayout implements MenuLayoutProof {

    /**
     * 证明的菜单类型：无线访问点点开的界面。这里是菜单类型的注册 ID，不是方块 ID。
     * 类别：游戏事实（AE2 菜单类型注册，WirelessAccessPointMenu）。
     */
    static final String ACCESS_POINT_MENU = "ae2:wireless_access_point";

    @Override public Set<String> menuTypes() {
        return Set.of(ACCESS_POINT_MENU);
    }

    @Override public MenuLayout.Layout classify(MenuSlots slots, List<Integer> playerSlots, List<Integer> otherSlots) {
        // 容器侧恰好三格（增强、绑定、产出）：多出来或少了都说明这个界面的样子对不上，不冒充认得出。
        if (otherSlots.size() != 3) {
            return new MenuLayout.Unsupported("访问点界面的工作格是 " + otherSlots.size() + " 格而不是 3 格，证明不了布局");
        }
        return new MenuLayout.Supported(List.copyOf(playerSlots), List.copyOf(otherSlots), Set.copyOf(otherSlots));
    }
}
