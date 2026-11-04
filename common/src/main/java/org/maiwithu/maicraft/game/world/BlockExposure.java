// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * 一格方块有没有面露在外面：相邻六格里有一格不是挡光的实心方块（空气、水、玻璃、树叶……），
 * 这一格才可能从某个方向被看见。埋在整片石头里的格子六面全是实心，先在这里筛掉，
 * 昂贵的视线射线只留给有面露出的候选。
 *
 * <p>液体挨着同一种液体的那一面原版不画，看不见：水面以下的水、一池岩浆的内部都算埋着，
 * 只有贴着空气、玻璃这类、或贴着别的液体的那几格露在外面。
 */
public final class BlockExposure {

    private BlockExposure() {}

    /** 判断一格是否至少有一面邻着不挡光的方块；只读世界，不改任何东西。 */
    public static boolean hasExposedFace(BlockGetter level, BlockPos at) {
        BlockState self = level.getBlockState(at);
        FluidState liquid = self.getBlock() instanceof LiquidBlock ? self.getFluidState() : null;
        for (Direction face : Direction.values()) {
            BlockPos next = at.relative(face);
            BlockState neighbor = level.getBlockState(next);
            if (neighbor.isSolidRender(level, next)) continue;
            // 找水、找岩浆时，挨着同一种液体（含泡在水里的海草、海带）的那一面看不见，不算露出。
            if (liquid != null && neighbor.getFluidState().getType().isSame(liquid.getType())) continue;
            return true;
        }
        return false;
    }
}
