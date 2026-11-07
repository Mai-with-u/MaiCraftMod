// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 对着一格放东西时，这一格自己会不会把右键截走：会开界面的（箱子、工作台、机器这类有方块实体或菜单的）、
 * 会被右键开关或按下的（门、活板门、栅栏门、按钮、拉杆）。是就要按住潜行再点，手里的方块才放得下去；
 * 施工、照明挑灯座、寻路往上垫脚都按这一条判断。
 */
public final class PlaceAgainst {

    private PlaceAgainst() {
    }

    /** 被点的方块自己有右键行为（开界面、门、按钮、拉杆）时要潜行再点，不然手里的方块放不下去。 */
    public static boolean needsSneak(Level level, BlockPos clicked, BlockState state) {
        if (state.hasBlockEntity() || state.getMenuProvider(level, clicked) != null) return true;
        var block = state.getBlock();
        return block instanceof DoorBlock || block instanceof TrapDoorBlock || block instanceof FenceGateBlock
                || block instanceof ButtonBlock || block instanceof LeverBlock;
    }
}
