// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.function.Supplier;

import baritone.api.IBaritone;
import baritone.behavior.PathingBehavior;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;

import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.survival.SurvivalSituation;
import org.maiwithu.maicraft.behavior.survival.breath.UnderwaterWalkBudget;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 行走段的自我保护闸，每刻走一遍：算路连续超过上限仍没有可走的路线，承认这一段已经死在算路里、
 * 如实收场并把起点身体状态写进问题，调用方能区分「还在算」与「已经没指望」；头潜进水里赶路按
 * 呼吸预算放弃上浮——氧气见底再走只会跟换气临时任务抢身体，深处会把角色憋死。潜泳路线段自己
 * 管氧气、会主动上浮换气，坐着船车不走路，都不归这两道闸管。
 */
final class WalkSurvivalGuards {

    private WalkSurvivalGuards() {}

    /**
     * 一刻的自保检查；收了场返回 true，调用方照常走统一结算。
     * 算路账只记引擎真在算的刻，挪垫料、等移动障碍的空档不算。
     */
    static boolean guard(WalkRunProgress progress, PlanningCeiling ceiling, PlayerContext context,
            LocalPlayer player, PathingBehavior pathing, BlockPos feet,
            boolean onGround, boolean inWater, boolean calculating, boolean submergedTravelActive) {
        return guard(progress, ceiling, context.level(), feet, onGround, inWater, calculating,
                player.isEyeInFluid(FluidTags.WATER), player.isPassenger(), submergedTravelActive,
                () -> SurvivalSituation.read(context));
    }

    /**
     * 自保检查的纯判断里子：头入没入水、坐没坐载具、处境由调用方按需递进来（处境读取会扫落点，只在
     * 真要裁断时才取），世界只用来给算路收场的问题补现场。触发条件、收场与结算与整条闸完全一致。
     */
    static boolean guard(WalkRunProgress progress, PlanningCeiling ceiling, Level world, BlockPos feet,
            boolean onGround, boolean inWater, boolean calculating, boolean headInWater,
            boolean passenger, boolean submergedTravelActive, Supplier<SurvivalSituation> situation) {
        if (progress.done()) return true;
        if (ceiling.observe(progress.report().state() != WalkReport.State.PLANNING, calculating)) {
            progress.fail(PlanningCeiling.timeoutProblem(
                    world, feet, inWater, onGround, ceiling.accruedTicks()), feet);
            return true;
        }
        if (headInWater && !passenger && !submergedTravelActive) {
            SurvivalSituation read = situation.get();
            if (UnderwaterWalkBudget.judge(read) == UnderwaterWalkBudget.Verdict.GIVE_UP_AND_SURFACE) {
                progress.fail(UnderwaterWalkBudget.giveUpProblem(read, feet), feet);
            }
        }
        return progress.done();
    }
}
