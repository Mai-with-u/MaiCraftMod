// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.burning;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.navigation.util.BlockPassability;

/**
 * 从客户端世界读着火自救要看的格子：岩浆、水、火，身体穿不穿得过，顶面踩不踩得住。
 * 没加载的格子按穿不过、踩不住算，不往看不见的地方走。
 */
final class LevelCells implements BurningSteps.Cells {

    private final Level level;

    LevelCells(Level level) {
        this.level = level;
    }

    @Override public boolean lava(BlockPos cell) {
        return level.isLoaded(cell) && level.getFluidState(cell).is(FluidTags.LAVA);
    }

    @Override public boolean water(BlockPos cell) {
        return level.isLoaded(cell) && level.getFluidState(cell).is(FluidTags.WATER) && open(cell);
    }

    // 火、灵魂火都在原版的火方块标签里；营火只有点着时才烫人。
    @Override public boolean fire(BlockPos cell) {
        if (!level.isLoaded(cell)) return false;
        BlockState state = level.getBlockState(cell);
        return state.is(BlockTags.FIRE) || CampfireBlock.isLitCampfire(state);
    }

    @Override public boolean open(BlockPos cell) {
        return level.isLoaded(cell) && level.getBlockState(cell).getCollisionShape(level, cell).isEmpty();
    }

    // 踩得住：有碰撞；岩浆块、仙人掌这类寻路也绕开的危险方块和点着的营火站上去要伤人，不算。
    @Override public boolean firm(BlockPos cell) {
        if (!level.isLoaded(cell)) return false;
        BlockState state = level.getBlockState(cell);
        return !state.getCollisionShape(level, cell).isEmpty() && !BlockPassability.isHazard(level, cell)
                && !CampfireBlock.isLitCampfire(state);
    }
}
