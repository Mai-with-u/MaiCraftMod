// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation;

import java.util.Optional;

import net.minecraft.core.BlockPos;

/**
 * 走到进展：盯一段走到运行有没有真在走。走到运行在路上不逐刻自报进展（动作状态只说「还在走」），
 * 长路的任务若只按 progressed 记进展就永远攒不出记录，人走得好好的也会被停滞判定按卡住收掉；
 * 进展要从走到情况报的脚位读——脚挪了格子就是真前进了一步。真走不动时这里不刷新，停滞判定照常收场。
 *
 * <p>自卫走回打点、点名战斗回倒下点捡掉落共用这一份判断；换了新的一趟走路从头看，
 * 第一格报到的脚位也算前进（起了路、人站到了出发格）。
 */
public final class WalkProgress {

    /** 盯着的这段走到；换了一段路（新起的运行）时脚位从头看。 */
    private WalkRun walk;
    /** 最近一次报到的脚位：脚位挪了格子才算前进了一步。 */
    private BlockPos lastFeet;

    /**
     * 记一刻：还是同一段路时，报的脚位挪了格子就返回新脚位；脚位没动、或还没报到（正在算路）时为空。
     * 换了一段路则重看，第一格报到的脚位就返回。
     */
    public Optional<BlockPos> note(WalkRun walk) {
        if (this.walk != walk) {
            this.walk = walk;
            lastFeet = null;
        }
        BlockPos feet = walk.report().feet();
        if (feet == null || feet.equals(lastFeet)) return Optional.empty();
        lastFeet = feet;
        return Optional.of(feet);
    }
}
