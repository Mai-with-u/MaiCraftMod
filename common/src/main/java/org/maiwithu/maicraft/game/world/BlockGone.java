// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 一格方块是不是已经空了：成了空气，或者只剩挖之前泡着的水。
 *
 * <p>挖水底下的方块时，原版先把方块去掉，水随即补回这一格；只认空气的话这一格永远等不到"空"，
 * 挖下来的那一格既报不了完成，也结算不了工具现场。挖方块和认"这一格空了"的地方共用这一份判断。
 */
public final class BlockGone {

    private BlockGone() {
    }

    /**
     * 这一格现在是不是已经空了；世界没加载或读不到状态时不算空。
     *
     * <p>客户端世界的 hasChunkAt 永远答"加载着"，没加载的格读出来是空气、地表落在世界最底层；
     * 按区块真加载没加载判，免得把还没加载的一格当成挖空了。
     */
    public static boolean gone(Level level, BlockPos cell) {
        if (level == null || cell == null || !level.isLoaded(cell)) return false;
        return gone(level.getBlockState(cell));
    }

    /** 给了这一格现在的样子时，判它是不是已经空了。 */
    public static boolean gone(BlockState now) {
        return now != null && (now.isAir() || now.getBlock() instanceof LiquidBlock);
    }
}
