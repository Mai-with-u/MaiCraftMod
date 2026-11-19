// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.List;
import java.util.Set;

import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.spi.MenuLayoutProof;

/**
 * ME 驱动器界面的布局证明：容器侧固定为 10 个存储元件槽（0..9），随后是标准的 36 格角色背包。
 *
 * <p>放进驱动器槽位的元件不会被瞬间消耗转化，因此十个槽按普通容器槽核对，
 * 让 use 点开驱动器时既能列出全部十槽元件明细，也能把手中存储元件插进空槽。
 */
public final class Ae2DriveLayout implements MenuLayoutProof {

    /** ME 驱动器点开的界面。这里是菜单类型的注册 ID，不是方块 ID。类别：游戏事实（AE2 菜单类型注册，DriveMenu）。 */
    static final String DRIVE_MENU = "ae2:drive";

    /** ME 驱动器的存储元件槽数。 */
    private static final int DRIVE_SLOTS = 10;

    @Override public Set<String> menuTypes() {
        return Set.of(DRIVE_MENU);
    }

    @Override public MenuLayout.Layout classify(MenuSlots slots, List<Integer> playerSlots, List<Integer> otherSlots) {
        // 核对容器侧恰好为 10 个存储元件槽且角色侧存在，对不上时不冒充认得出布局。
        if (otherSlots.size() != DRIVE_SLOTS || playerSlots.isEmpty()) {
            return new MenuLayout.Unsupported("驱动器界面的容器侧是 " + otherSlots.size() + " 格而不是 "
                    + DRIVE_SLOTS + " 格，证明不了布局");
        }
        return new MenuLayout.Supported(List.copyOf(playerSlots), List.copyOf(otherSlots), Set.of());
    }
}
