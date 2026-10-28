// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.calc;

import net.minecraft.core.BlockPos;

/**
 * 导航目标估价用的乐观成本：对角步系数、单格成本、升降一格的成本（升降按原版的坠落阻力与重力算），
 * 以及按它们算的到一点的八方向距离下界。各种到达条件的估价共用这一份，数值与内核成本表同源。
 */
public final class HeuristicCosts {

    // ---- 启发式常量(数值与内核成本表同源) ----

    /** 对角步的水平距离系数。 */
    public static final double SQRT_2 = Math.sqrt(2.0);
    /** 加权 A* 沿用的乐观单格成本(≈疾跑单格)。 */
    public static final double COST_HEURISTIC = 3.563;
    /** 升一格的乐观成本(跳跃抛物线差)。 */
    public static final double JUMP_ONE_BLOCK = fallTicks(1.25) - fallTicks(0.25);
    /** 降一格的乐观成本(坠落两格耗时之半)。 */
    public static final double DESCEND_ONE_BLOCK = fallTicks(2) / 2.0;

    /** 坠落第 ticks 刻内的位移(格/刻)，来自原版的坠落阻力与重力取值。 */
    private static double fallVelocity(int ticks) {
        return (Math.pow(0.98, ticks) - 1) * -3.92;
    }

    /** 坠落 distance 格所需刻数：逐刻累减位移，末段线性插值。调用者给有限的非负数。 */
    private static double fallTicks(double distance) {
        if (distance == 0) {
            return 0;
        }
        double remaining = distance;
        int tickCount = 0;
        while (true) {
            double perTick = fallVelocity(tickCount);
            if (remaining <= perTick) {
                return tickCount + remaining / perTick;
            }
            remaining -= perTick;
            tickCount++;
        }
    }

    private HeuristicCosts() {}

    // ---- 共用的八方向水平距离与垂直距离下界 ----

    /**
     * 按横向直走／斜走以及上下高差估算到中心点的费用；这个帮助方法自身不知道目标允许多大的到达范围。
     */
    public static double pointBound(BlockPos goal, BlockPos from) {
        double dx = Math.abs(goal.getX() - from.getX());
        double dz = Math.abs(goal.getZ() - from.getZ());
        // 水平下界为（对角步数 × √2 + 直行步数）× COST_HEURISTIC；此处权重约等于疾跑成本，已经纳入估价，堆键不会再次相乘。
        double horizontal = (Math.min(dx, dz) * SQRT_2 + Math.abs(dx - dz))
                * COST_HEURISTIC;
        // 垂直下界按每格上升 JUMP、下降 DESCEND（fall[2]/2）计价。
        int dy = goal.getY() - from.getY();
        double vertical = dy > 0
                ? dy * JUMP_ONE_BLOCK
                : -dy * DESCEND_ONE_BLOCK;
        return horizontal + vertical;
    }
}
