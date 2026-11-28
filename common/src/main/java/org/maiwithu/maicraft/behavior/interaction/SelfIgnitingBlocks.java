// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * 点它本身就能点着的方块：TNT，还没点着且未含水的营火、蜡烛、蜡烛蛋糕；点过之后就是点着状态。
 * 营火（含灵魂营火）点着后按固定原版规则烤食物，它的认法也在这里，全仓一份。
 */
public final class SelfIgnitingBlocks {

    private SelfIgnitingBlocks() {}

    /** 是不是原版营火（含灵魂营火）。 */
    public static boolean isCampfire(Block block) {
        return block instanceof CampfireBlock;
    }

    /** 是不是原版营火（含灵魂营火），按方块 ID 认；手边只有 ID 时用这一个。 */
    public static boolean isCampfire(String blockId) {
        return "minecraft:campfire".equals(blockId) || "minecraft:soul_campfire".equals(blockId);
    }

    // 点它本身就能点着：TNT，还没点着且未含水的营火、蜡烛、蜡烛蛋糕。
    public static boolean ignitesItself(BlockState state) {
        if (state.getBlock() instanceof TntBlock
                || CampfireBlock.canLight(state)
                || CandleBlock.canLight(state)
                || CandleCakeBlock.canLight(state)) {
            return true;
        }
        // 同时按方块类与属性兜底，确保未加载数据包方块标签的离线测试与原版一致识别未点燃且未含水的营火、蜡烛与蜡烛蛋糕。
        boolean ignitableBlock = state.getBlock() instanceof CampfireBlock
                || state.getBlock() instanceof CandleBlock
                || state.getBlock() instanceof CandleCakeBlock;
        if (!ignitableBlock || !state.hasProperty(BlockStateProperties.LIT)
                || state.getValue(BlockStateProperties.LIT)) {
            return false;
        }
        return !state.hasProperty(BlockStateProperties.WATERLOGGED)
                || !state.getValue(BlockStateProperties.WATERLOGGED);
    }

    // 目标本身就是营火、蜡烛或蜡烛蛋糕，且此刻已经点着：不需要再点，也绝不能落回往旁边空气格点火。
    public static boolean alreadyLit(BlockState state) {
        return (state.getBlock() instanceof CampfireBlock
                || state.getBlock() instanceof CandleBlock
                || state.getBlock() instanceof CandleCakeBlock)
                && state.hasProperty(BlockStateProperties.LIT)
                && state.getValue(BlockStateProperties.LIT);
    }
}
