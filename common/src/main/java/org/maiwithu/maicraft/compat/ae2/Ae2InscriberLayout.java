// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.List;
import java.util.Set;

import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.spi.MenuLayoutProof;

/**
 * 压印器界面的布局证明：角色背包那一侧是普通的 36 格；容器那一侧是八格——上面模板格、下面模板格、
 * 中间输入格、输出格，加四个升级格。输入与输出按机器格核对（放进去的料放好就可能被吃掉、产物放不进去，
 * 以源格确切减少为准，不按放置回显）；两格模板放上去就待着，升级格放上去就装上，也按机器格核对，
 * 不把它们当普通存取格主动搬动。
 */
public final class Ae2InscriberLayout implements MenuLayoutProof {

    /** 压印器点开的界面。这里是菜单类型的注册 ID，不是方块 ID。类别：游戏事实（AE2 菜单类型注册，InscriberMenu）。 */
    static final String INSCRIBER_MENU = "ae2:inscriber";

    /** 界面容器侧的格子数：四个工作格加四个升级格。 */
    private static final int INSCRIBER_SLOTS = 8;

    @Override public Set<String> menuTypes() {
        return Set.of(INSCRIBER_MENU);
    }

    @Override public MenuLayout.Layout classify(MenuSlots slots, List<Integer> playerSlots, List<Integer> otherSlots) {
        // 容器侧恰好八格（四个工作格加四个升级格）：多出来或少了都说明界面的样子对不上，不冒充认得出。
        if (otherSlots.size() != INSCRIBER_SLOTS) {
            return new MenuLayout.Unsupported("压印器界面的容器侧是 " + otherSlots.size() + " 格而不是 "
                    + INSCRIBER_SLOTS + " 格，证明不了布局");
        }
        return new MenuLayout.Supported(List.copyOf(playerSlots), List.copyOf(otherSlots), Set.copyOf(otherSlots));
    }
}
