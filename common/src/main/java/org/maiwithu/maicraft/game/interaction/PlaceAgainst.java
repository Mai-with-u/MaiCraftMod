// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DragonEggBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 对着一格放东西时，这一格自己会不会把右键截走：会开界面的（箱子、工作台、机器这类有方块实体或菜单的）、
 * 会被右键开关或按下的（门、活板门、栅栏门、按钮、拉杆）、右键会改它自己的（音符盒调音、中继器调档、
 * 红石线换形、蛋糕被吃、花盆把花取出、堆肥桶倒出骨粉、龙蛋瞬移、重生锚爆炸）。是就要按住潜行再点，
 * 手里的方块才放得下去；施工、照明挑灯座、寻路往上垫脚都按这一条判断。
 */
public final class PlaceAgainst {

    private PlaceAgainst() {
    }

    /** 被点的方块自己有右键行为（开界面、门、按钮、拉杆、调音调档这类）时要潜行再点，不然手里的方块放不下去。 */
    public static boolean needsSneak(Level level, BlockPos clicked, BlockState state) {
        return takesRightClick(state) || state.getMenuProvider(level, clicked) != null;
    }

    /**
     * 不看现场就知道这种方块会截走右键：有方块实体的（箱子、熔炉、比较器、告示牌这类），
     * 和没有方块实体、右键也会开关或改它自己的那些。只靠菜单认的（工作台、砂轮这类）由调用方带上现场再问。
     */
    public static boolean takesRightClick(BlockState state) {
        if (state.hasBlockEntity()) return true;
        var block = state.getBlock();
        return block instanceof DoorBlock || block instanceof TrapDoorBlock || block instanceof FenceGateBlock
                || block instanceof ButtonBlock || block instanceof LeverBlock || block instanceof NoteBlock
                || block instanceof DiodeBlock || block instanceof RedStoneWireBlock || block instanceof CakeBlock
                || block instanceof CandleCakeBlock || block instanceof FlowerPotBlock || block instanceof ComposterBlock
                || block instanceof DragonEggBlock || block instanceof RespawnAnchorBlock;
    }
}
