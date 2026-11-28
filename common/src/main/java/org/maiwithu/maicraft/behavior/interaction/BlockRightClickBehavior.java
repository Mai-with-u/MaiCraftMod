// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 右键会先触发方块自己的行为的方块：开界面的（箱子、熔炉、工作台、织布机）、
 * 门与活板门、栅栏门、按钮、拉杆。手里的东西点上去会被方块截走，倒流体、点火挑支撑面时
 * 要避开这类方块；一次右键算不算改动世界，也从这里分。
 */
public final class BlockRightClickBehavior {

    private BlockRightClickBehavior() {}

    /** 这一格右键会不会先触发方块自己的行为（开门、开界面、按下按钮），手里的东西轮不上。 */
    public static boolean handlesOwnRightClick(ClientLevel level, BlockPos at, BlockState state) {
        if (state.getMenuProvider(level, at) != null) return true;
        var block = state.getBlock();
        return block instanceof DoorBlock || block instanceof TrapDoorBlock || block instanceof FenceGateBlock
                || block instanceof ButtonBlock || block instanceof LeverBlock;
    }
}
