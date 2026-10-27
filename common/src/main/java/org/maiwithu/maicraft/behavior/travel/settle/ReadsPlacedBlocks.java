// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.settle;

import net.minecraft.core.BlockPos;
import java.util.List;
import org.maiwithu.maicraft.behavior.navigation.OthersBlockBroken;

/**
 * 读垫过的方块的只读接缝：这次走到路上为开路垫上的临时方块，结算时逐格进结果。
 * 垫方块发生在寻路内部，走到入口的报告不带它；由寻路的实现一方实现本接缝。
 * 垫的方块不自动收回，如实报告；要不要收回由玩家决定。
 */
public interface ReadsPlacedBlocks {

    /** 当前这段走到里垫上的方块格子；没垫过给空列表。 */
    List<BlockPos> placedDuringCurrentWalk();

    /** 当前这段走到里每次落地救援的经过（放了什么、收没收回、掉没掉血）；没有给空列表。 */
    default List<String> landingsDuringCurrentWalk() {
        return List.of();
    }

    /** 当前这段走到里每次卡坑自动脱困的经过（怎么脱的、垫了几块、挖开几格）；没有给空列表。 */
    default List<String> trapEscapesDuringCurrentWalk() {
        return List.of();
    }

    /** 当前这段走到里被别人的方块封死在小坑里、挖开的别人的方块（在哪、什么方块、谁放的）；没挖过给空列表。 */
    default List<OthersBlockBroken> othersBlocksBrokenDuringCurrentWalk() {
        return List.of();
    }

    /**
     * 挖掉的一格在结果的变化里怎么写：被困时挖开的别人的方块写明是谁放的，其余写"开路挖掉的"。
     *
     * @param broken 挖掉方块账里的一条："x, y, z 的 方块ID"
     */
    default String breakNote(String broken) {
        for (OthersBlockBroken others : othersBlocksBrokenDuringCurrentWalk()) {
            if (broken.startsWith(others.at() + " 的 ")) return "被困脱身挖开的，" + others.placedBy() + " 放的";
        }
        return "开路挖掉的";
    }

    /** 当前这段走到里为开路挖掉的方块："x, y, z 的 方块ID"；没挖过给空列表。 */
    default List<String> brokenDuringCurrentWalk() {
        return List.of();
    }
}
