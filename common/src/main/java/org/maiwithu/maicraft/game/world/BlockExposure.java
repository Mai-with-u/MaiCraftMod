// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;

/**
 * 一格方块有没有面露在外面：相邻六格里有一格不是挡光的实心方块（空气、水、玻璃、树叶……），
 * 这一格才可能从某个方向被看见。埋在整片石头里的格子六面全是实心，先在这里筛掉，
 * 昂贵的视线射线只留给有面露出的候选。
 */
public final class BlockExposure {

    private BlockExposure() {}

    /** 判断一格是否至少有一面邻着不挡光的方块；只读世界，不改任何东西。 */
    public static boolean hasExposedFace(BlockGetter level, BlockPos at) {
        for (Direction face : Direction.values()) {
            BlockPos next = at.relative(face);
            if (!level.getBlockState(next).isSolidRender(level, next)) {
                return true;
            }
        }
        return false;
    }
}
