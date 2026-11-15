// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import net.minecraft.core.BlockPos;

/**
 * 短途直走判断：靠近换站位时先问一句"这几步路是不是一眼看得见、直着走就能过去"。
 * 三格内平地、沿途一条直线逐格站得稳、不踩不许踩的格，判得过就不进寻路，像玩家那样直接走过去；
 * 判不过不是否决——这一站位照旧交给寻路，快路不通走慢路。纯函数：只从只读视图问世界事实。
 */
public final class NearbyWalks {

    /** 水平距离上限（格）：再远就不是一眼看得见的短途，交给寻路。类别：玩家常识。 */
    static final double MAX_FLAT_DISTANCE = 3.0;

    private NearbyWalks() {}

    /**
     * 从 from 到 to 能不能直着走过去。沿途按一条直线逐格核对（含终点）：
     * 已加载、脚下实心、头顶两格空、不在液体里、旁边没岩浆、不挨着深落差、不受保护、不是这次不许踩的格；
     * 高度差最多下一格或跳一格，跳的那一下由走路自己跳。
     * 中途的格按出发的地面高度核对，终点按它自己的高度核对；格子没加载时不猜，判不过。
     */
    public static boolean passable(BlockPos from, BlockPos to, ApproachWorldView world, ProtectedCells guarded) {
        if (from == null || to == null) return false;
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int dy = to.getY() - from.getY();
        if (Math.sqrt((double) dx * dx + (double) dz * dz) > MAX_FLAT_DISTANCE) return false;
        if (Math.abs(dy) > 1) return false;
        int steps = Math.max(Math.max(Math.abs(dx), Math.abs(dz)), 1);
        for (int i = 1; i <= steps; i++) {
            int x = from.getX() + Math.round(dx * (float) i / steps);
            int z = from.getZ() + Math.round(dz * (float) i / steps);
            int y = i == steps ? to.getY() : from.getY();
            if (!steppable(new BlockPos(x, y, z), world, guarded)) return false;
        }
        return true;
    }

    /** 一格能不能踩着走过去：与站位判据同一条底线，只是不问"够得着、看得见"（那是到了之后的事）。 */
    private static boolean steppable(BlockPos cell, ApproachWorldView world, ProtectedCells guarded) {
        if (!world.isLoaded(cell) || !world.solidFloor(cell) || !world.headroom(cell)) return false;
        if (world.inFluid(cell) || world.lavaBeside(cell)) return false;
        if (StandSpots.besideDeepDrop(cell, world)) return false;
        return !guarded.contains(cell);
    }
}
