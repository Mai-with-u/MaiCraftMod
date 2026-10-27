// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 功能方块：角色被别人的方块封死在小坑里时也不挖的那一类。
 *
 * <p>有方块实体的存着东西、有界面或机器状态（箱子、木桶、熔炉、床、告示牌、旗帜、潜影盒、模组机器……）；
 * 门、活板门、栅栏门是人家的进出口；会发红石信号的（按钮、拉杆、压力板、红石线、中继器、侦测器……）是机关的一部分；
 * 铁轨是一条线路的一段。拆了它们，人家丢的不只是一块方块。都按游戏数据认（方块实体、红石信号、方块类别），
 * 模组的方块同样适用，不写方块 ID 名单。寻路在后台线程上拿自己读到的方块直接问这里。
 */
public final class FunctionalBlocks {

    private FunctionalBlocks() {}

    /** 这一格方块是不是功能方块；读不到方块（null）按是算，拿不准就不挖。 */
    public static boolean functional(BlockState state) {
        if (state == null) return true;
        return state.hasBlockEntity()
                || LivePlayerMadeGuesses.looksPlacedByPlayer(state)
                || state.isSignalSource()
                || state.getBlock() instanceof BaseRailBlock;
    }
}
