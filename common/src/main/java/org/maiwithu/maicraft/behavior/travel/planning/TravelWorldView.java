// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.planning;

import java.util.OptionalInt;

import org.maiwithu.maicraft.behavior.explore.ColumnSample;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 出行时的现场只读视图：解析目的地需要问的每一件事都在这里问，解析自己不读游戏对象。
 * 真正读角色位置与朝向的实现在游戏接口层，测试里用替身摆出场景。
 */
public interface TravelWorldView {

    /** 角色此刻所在的位置，带维度；维度为 null 表示当前维度。 */
    WorldPosition currentSpot();

    /** 角色此刻的水平朝向，只给东南西北之一；相对的前后左右由解析换算成绝对方向。 */
    Target.Toward currentFacing();

    /**
     * 这一柱列里脚下站得住的落点（干地，脚的高度）：这一片加载着才读得到；
     * 顶上有方块不等于站得上——水面列的顶格在水上、封顶建筑的顶格在屋顶，都作不了落点。
     * 人待在盖了顶的空间里时优先取人所在层的可站格（室内指室内），其余取露天可站的地表。
     * 读不到、或这一柱没有能站的地方（整柱是水）为空，解析方退回"先走到那一柱列"。
     */
    default OptionalInt surfaceFeet(int x, int z) {
        return OptionalInt.empty();
    }

    /**
     * 这一列的顶盖样本（忽略树叶的最高实体或液体遮挡物）：判断脚下露天没有、找露天落脚处用；
     * 没加载、读不到给未加载样本，既不判露天也不判封闭。
     */
    default ColumnSample column(int x, int z) {
        return ColumnSample.unloaded();
    }

    /** 人能不能站在这一格（脚在这格、头在上面一格、脚下踩得住）；没加载、读不到时答能，不凭空放宽目的地。 */
    default boolean standable(int x, int y, int z) {
        return true;
    }

    /**
     * 角色身体此刻在水里吗（泅渡、泡在浅水里都算）：判"到了"与乘船腿的出发点
     * 按这同一份事实判，各处不再另写"脚下是不是水"。读不到角色时答不在。
     */
    default boolean inWater() {
        return false;
    }
}
