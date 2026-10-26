// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.enchant;

import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * 找台读端：在已加载的现场里找最近的附魔台，并核对某一格此刻摆的是什么。
 * 判定只认现场，记忆与预测不作数；测试换成画好的替身。
 */
interface TableScans {

    /** 一次找台读数：现场里找到的台子位置，以及这次查询扫完了没有；没扫完不算没有。 */
    record Found(List<BlockPos> hits, boolean complete) {
    }

    /** 一格的现场核对：是不是真摆着的附魔台，不是时那格现在是什么。 */
    record At(boolean table, String actual) {
    }

    /**
     * 在角色周围的已加载现场分刻找最近的附魔台。查询前按维度补登记目标方块：
     * 扫描索引只认登记过的方块，漏登记会把"没登记"误报成"附近没有"。
     */
    Found nearest(int chunkRadius, int buildBudget);

    /** 看那一格此刻摆的是什么；挑选与动手前都按它核对，不能拿选台时的旧结论当现状。 */
    At inspect(BlockPos pos);
}
