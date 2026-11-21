// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.appmek;

import java.util.List;
import java.util.Set;

import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.spi.MenuLayoutProof;

/**
 * 便携化学品存储元件界面的独立布局证明（{@code appmek:portable_chemical_cell}）：
 * 严格核对角色背包一侧恰好为 36 格，容器侧返回空集合（盘内化学品由终端同步表展示，不在普通物品槽位里）；
 * 其余槽位（升级槽、视图元件槽及携带网络工具时动态多出的 9 个工具箱槽）全部排除在外，不受槽位偏移影响。
 */
public final class AppMekPortableCellLayout implements MenuLayoutProof {

    /** 应用机械便携化学品元件的菜单类型注册 ID。 */
    public static final String MENU_PORTABLE_CHEMICAL_CELL = "appmek:portable_chemical_cell";
    private static final int PLAYER_SLOT_COUNT = 36;

    @Override public Set<String> menuTypes() {
        return Set.of(MENU_PORTABLE_CHEMICAL_CELL);
    }

    @Override public MenuLayout.Layout classify(MenuSlots slots, List<Integer> playerSlots, List<Integer> otherSlots) {
        // 二次核对角色侧必须严格为 36 格，防止异常布局误判；其余全部非玩家槽均不作为普通存取容器槽暴露。
        if (playerSlots == null || playerSlots.size() != PLAYER_SLOT_COUNT) {
            int actual = playerSlots == null ? 0 : playerSlots.size();
            return new MenuLayout.Unsupported("便携化学品元件界面的角色侧是 " + actual + " 格而不是 36 格，证明不了两侧怎么分");
        }
        return new MenuLayout.Supported(List.copyOf(playerSlots), List.of(), Set.of());
    }
}
