// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import baritone.api.utils.BetterBlockPos;

/**
 * 坑边格沿起点的支撑格探测（纯判断）：当角色身体碰撞箱搭在 1×1 坑边、游戏判着地
 * （{@code onGround == true}）但体素脚位下方是空气时，从周围八个水平邻格里按碰撞箱投影重合范围
 * 挑出离角色最近的真实可行走支撑格，避免排进原格自身或被前四个截断漏掉对角与邻格支撑。
 */
public final class PitEdgeSupport {

    /** 碰撞箱边缘接触容差（格）：覆盖原版站在格沿极细微压边时的浮点余量。 */
    private static final double EDGE_TOLERANCE = 0.02;

    private PitEdgeSupport() {}

    /**
     * 在角色脚位周围八个水平邻格里找出碰撞箱真正踩着、且可站立通行的支撑格；找不到时退回脚位自身。
     *
     * @param feet            角色当前体素脚位（其正下方不可行走）
     * @param playerX         角色水平 X 坐标
     * @param playerZ         角色水平 Z 坐标
     * @param bbWidth         角色碰撞箱宽度（原版玩家为 0.6）
     * @param walkableSupport 判断候选格是否满足"脚下可站、身体两格可穿"
     */
    public static BetterBlockPos selectSupport(BetterBlockPos feet, double playerX, double playerZ,
            double bbWidth, Predicate<BetterBlockPos> walkableSupport) {
        List<BetterBlockPos> neighbors = new ArrayList<>(8);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                neighbors.add(new BetterBlockPos(feet.x + dx, feet.y, feet.z + dz));
            }
        }
        // 按邻格水平中心到角色真实位置的距离升序排，优先落回身体压得最实的那一格。
        neighbors.sort(Comparator.comparingDouble(pos -> {
            double dx = (pos.x + 0.5) - playerX;
            double dz = (pos.z + 0.5) - playerZ;
            return dx * dx + dz * dz;
        }));
        // 角色半宽加方块半宽才是水平碰撞箱能搭上的最大中心距；任一轴超出都不可能压在这块方块顶面。
        double reach = 0.5 + Math.max(0.0, bbWidth * 0.5) + EDGE_TOLERANCE;
        for (BetterBlockPos candidate : neighbors) {
            double xDist = Math.abs((candidate.x + 0.5) - playerX);
            double zDist = Math.abs((candidate.z + 0.5) - playerZ);
            if (xDist > reach || zDist > reach) continue;
            if (walkableSupport.test(candidate)) return candidate;
        }
        return feet;
    }
}
