// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.List;
import java.util.Set;

import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.spi.MenuLayoutProof;

/**
 * 陨石箱界面的布局证明：容器侧固定为 36 个普通存储槽（9×4），随后是标准的 36 格角色背包。
 *
 * <p>陨石箱是纯储物容器，没有加工消耗或特殊输出槽，因此全部 36 格按普通容器槽核对，
 * 供翻箱取货、存放与查看内容统一使用。
 */
public final class Ae2SkyChestLayout implements MenuLayoutProof {

    /** 陨石箱点开的界面。这里是菜单类型的注册 ID，不是方块 ID。类别：游戏事实（AE2 菜单类型注册，SkyChestMenu）。 */
    static final String SKY_CHEST_MENU = "ae2:skychest";

    /** 陨石箱的存储槽数（9 列 4 行）。 */
    private static final int SKY_CHEST_SLOTS = 36;

    @Override public Set<String> menuTypes() {
        return Set.of(SKY_CHEST_MENU);
    }

    @Override public MenuLayout.Layout classify(MenuSlots slots, List<Integer> playerSlots, List<Integer> otherSlots) {
        // 核对容器侧恰好为 36 个存储槽且角色侧存在，对不上时不冒充认得出布局。
        if (otherSlots.size() != SKY_CHEST_SLOTS || playerSlots.isEmpty()) {
            return new MenuLayout.Unsupported("陨石箱界面的容器侧是 " + otherSlots.size() + " 格而不是 "
                    + SKY_CHEST_SLOTS + " 格，证明不了布局");
        }
        return new MenuLayout.Supported(List.copyOf(playerSlots), List.copyOf(otherSlots), Set.of());
    }
}
