// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 算路时长上限的逐刻判断：引擎连续算了这么久仍没有可走的路线，就承认这一段已经死在算路里，如实收场。
 * 不加上限时，起点体素状态别扭（泡在水里随波漂、悬空）会让引擎一轮轮重算、路线一轮轮作废，
 * doing 停在「正在算路」数分钟不下结论，调用方分不清「还在算」与「已经没指望」。
 *
 * <p>账只在引擎真的在算时累计；有路线走出来（上路了）就清零；引擎两轮搜索之间的换气空档照旧累计，
 * 不算重新开始。自我修正的重新算路（脱困升级、只读复算后的重算、停稳续走、换目标）各自重新起算，
 * 这些路数自己有次数上限，不需要这里替它们收场。
 */
final class PlanningCeiling {

    /** 连续算路这么多游戏刻（15 秒）仍没有路线就收场：引擎单次搜索自身上限 6 秒，正常算路秒级完成；
     *  一两次自我重算落在 15 秒之内，数分钟的挂起在这之上一个量级。 */
    static final long DEFAULT_LIMIT_TICKS = 300;

    private final long limitTicks;
    private long accrued;

    PlanningCeiling() {
        this(DEFAULT_LIMIT_TICKS);
    }

    PlanningCeiling(long limitTicks) {
        if (limitTicks <= 0) throw new IllegalArgumentException("算路上限必须是正的刻数");
        this.limitTicks = limitTicks;
    }

    /**
     * 一刻算路观察。
     *
     * @param onRoute     这一刻有可走的路线（已上路或已有结论），算路账清零
     * @param calculating 这一刻引擎正在算路，账累计一格
     * @return 账满上限（该收场了）
     */
    boolean observe(boolean onRoute, boolean calculating) {
        if (onRoute) {
            accrued = 0;
            return false;
        }
        if (calculating) {
            accrued++;
        }
        return accrued >= limitTicks;
    }

    /** 一次自我修正的重新算路：上限重新起算。 */
    void restart() {
        accrued = 0;
    }

    long accruedTicks() {
        return accrued;
    }

    /**
     * 算路超时的收场问题：写清等了多久、起点身体是什么状态（在水里、悬空、站在地上）、脚下方块是什么，
     * 调用方据此换起点（先上岸、垫脚）或换目标，而不是把一次死掉的算路当成还在算。
     *
     * @param world 起点所在的世界，用来读脚下方块；读不到现场给 null，就不写这一项
     */
    static Problem timeoutProblem(Level world, BlockPos feet, boolean inWater, boolean onGround, long ticks) {
        String posture = inWater ? "泡在水里" : onGround ? "站在地上" : "悬空";
        String ground = world != null && world.isLoaded(feet)
                ? "，脚下方块 " + world.getBlockState(feet.below()).getBlock() : "";
        return Problem.of(Problem.Kind.STUCK,
                "在 (" + feet.getX() + ", " + feet.getY() + ", " + feet.getZ() + ") 连续算路 "
                        + ticks / 20 + " 秒仍没有可走的路线，不再干等：起点身体" + posture + ground,
                "先让起点站实（走上岸、垫一块脚下方块），或换个起点与目标再试");
    }
}
