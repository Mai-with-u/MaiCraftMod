// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.spi.MenuLayoutProof;

/**
 * 压印器界面的布局证明：实机菜单先由父类注册四个升级卡槽（0..3）与角色背包 36 格（4..39），
 * 最后在末尾注册四个工作格（40 顶模板、41 底模板、42 中间输入、43 输出）。
 *
 * <p>这里将容器侧规范化重排为「前四格工作格、后四格升级槽」，使界面读数的容器侧 0..3 与压印器方块实体内部库存 0..3
 * （顶、底、中间、输出）严格对齐；八格全部按机器格核对，避免当普通存取箱乱搬。
 */
public final class Ae2InscriberLayout implements MenuLayoutProof {

    /** 压印器点开的界面。这里是菜单类型的注册 ID，不是方块 ID。类别：游戏事实（AE2 菜单类型注册，InscriberMenu）。 */
    static final String INSCRIBER_MENU = "ae2:inscriber";

    /** 界面容器侧总格数：四个工作格加四个升级格。 */
    private static final int INSCRIBER_SLOTS = 8;
    private static final int WORK_SLOTS = 4;
    private static final int UPGRADE_SLOTS = 4;

    @Override public Set<String> menuTypes() {
        return Set.of(INSCRIBER_MENU);
    }

    @Override public MenuLayout.Layout classify(MenuSlots slots, List<Integer> playerSlots, List<Integer> otherSlots) {
        if (otherSlots.size() != INSCRIBER_SLOTS || playerSlots.isEmpty()) {
            return new MenuLayout.Unsupported("压印器界面的容器侧是 " + otherSlots.size() + " 格而不是 "
                    + INSCRIBER_SLOTS + " 格，证明不了布局");
        }
        int firstPlayer = playerSlots.stream().mapToInt(Integer::intValue).min().orElseThrow();
        int lastPlayer = playerSlots.stream().mapToInt(Integer::intValue).max().orElseThrow();
        List<Integer> upgradeSlots = new ArrayList<>(UPGRADE_SLOTS);
        List<Integer> workSlots = new ArrayList<>(WORK_SLOTS);
        for (int slot : otherSlots) {
            if (slot < firstPlayer) {
                upgradeSlots.add(slot);
            } else if (slot > lastPlayer) {
                workSlots.add(slot);
            }
        }
        // 实机 InscriberMenu 在玩家背包前放 4 个升级槽、在玩家背包后放 4 个工作格；重排让工作格排在 0..3。
        if (upgradeSlots.size() != UPGRADE_SLOTS || workSlots.size() != WORK_SLOTS) {
            return new MenuLayout.Unsupported("压印器界面的升级格（" + upgradeSlots.size() + "）与工作格（"
                    + workSlots.size() + "）位置对不上，证明不了布局");
        }
        List<Integer> orderedContainer = new ArrayList<>(INSCRIBER_SLOTS);
        orderedContainer.addAll(workSlots);
        orderedContainer.addAll(upgradeSlots);
        return new MenuLayout.Supported(List.copyOf(playerSlots), List.copyOf(orderedContainer), Set.copyOf(otherSlots));
    }
}
