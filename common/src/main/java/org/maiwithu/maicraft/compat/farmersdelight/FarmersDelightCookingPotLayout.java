// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.farmersdelight;

import java.util.List;
import java.util.Set;

import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.spi.MenuLayoutProof;

/**
 * 厨锅界面的布局证明：角色背包那一侧是普通的 36 格；容器那一侧是九格——六格食材格、
 * 餐品显示格、碗输入格、成品输出格。食材放进格子待煮、餐做好才动，都不像普通箱子存取，
 * 九格全部按机器格核对（源格确切减少才算数，不按放置回显）。
 */
public final class FarmersDelightCookingPotLayout implements MenuLayoutProof {

    /** 厨锅点开的界面。这里是菜单类型的注册 ID，不是方块 ID。类别：游戏事实（农夫乐事菜单类型注册，CookingPotMenu）。 */
    static final String COOKING_POT_MENU = "farmersdelight:cooking_pot";

    /** 界面容器侧的格子数：六格食材、餐品显示、碗输入、成品输出。 */
    private static final int COOKING_POT_SLOTS = 9;

    @Override public Set<String> menuTypes() {
        return Set.of(COOKING_POT_MENU);
    }

    @Override public MenuLayout.Layout classify(MenuSlots slots, List<Integer> playerSlots, List<Integer> otherSlots) {
        // 容器侧恰好九格：多出来或少了都说明界面的样子对不上，不冒充认得出。
        if (otherSlots.size() != COOKING_POT_SLOTS) {
            return new MenuLayout.Unsupported("厨锅界面的容器侧是 " + otherSlots.size() + " 格而不是 "
                    + COOKING_POT_SLOTS + " 格，证明不了布局");
        }
        return new MenuLayout.Supported(List.copyOf(playerSlots), List.copyOf(otherSlots), Set.copyOf(otherSlots));
    }
}
