// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.landing;

import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * 出手窗口：这一刻放不放落地的东西。读只读现场给结论——能不能活、东西在不在手上、
 * 方块类按没按潜行、落点放不放得下、射线点没点对，按这个顺序核，先拒绝的先说，后面的不再看。
 * 放水只回答窗口开没开、把射线交出去：水桶真实落进哪一格由放水那一步按原版规则当场认。
 * 还够不着与对不准分得清：还在高处时射线本来就点不到，不能算对不准。
 */
public final class ReleaseWindow {

    /** 判断的结论：放了、等一等，或如实的拒绝理由。 */
    public enum Step {
        /** 掉得太高，落在干草上也会摔死。 */
        SURVIVAL_REFUSED,
        /** 落地的东西还没拿到手上。 */
        ITEM_NOT_IN_HAND,
        /** 方块类还没按住潜行，点到的方块会被"使用"。 */
        WAIT_FOR_SNEAK,
        /** 落点被占了或被划进保护，放不下了。 */
        CELL_LOST,
        /** 还没够得着落点，等下一刻。 */
        OUT_OF_REACH,
        /** 够得着，但准星没点在要点的那一面。 */
        MISALIGNED,
        /** 到窗口了，放。 */
        RELEASE,
    }

    /** 结论带上这一刻的射线命中：放的时候交给提交那一下，对不准时它说明点到了哪里。 */
    public record Verdict(Step step, BlockHitResult hit) {}

    private ReleaseWindow() {}

    /**
     * 落在干草上活不活得成：只有干草要算（减到五分之一后的伤害），其余办法免伤。
     * 摔落取样与脚高都从现场读。
     */
    public static boolean survivesHay(LandingScene scene, LandingPlan plan) {
        return plan.survives(scene.fallSample(), scene.feetY(), true);
    }

    /** 落点这一刻够不够得着：眼睛到被点的那一面高过触及距离就是还够不着。 */
    public static boolean inReach(LandingScene scene, LandingPlan plan) {
        return scene.eyePosition().y - scene.placementHeight(plan) <= scene.blockReach();
    }

    /**
     * 这一刻放不放：能活、东西在手上、方块类按住了潜行、落点还放得下，才看射线；
     * 射线正点在要点的那一格那一面就放，够得着却点偏算对不准，还够不着就再等一刻。
     */
    public static Verdict judge(LandingScene scene, LandingPlan plan, boolean itemReady,
                                Predicate<BlockPos> protectedCell) {
        if (!survivesHay(scene, plan)) return new Verdict(Step.SURVIVAL_REFUSED, null);
        if (!itemReady) return new Verdict(Step.ITEM_NOT_IN_HAND, null);
        if (plan.aid() != LandingAid.WATER && !scene.sneaking()) return new Verdict(Step.WAIT_FOR_SNEAK, null);
        if (protectedCell.test(plan.cell()) || !scene.canPlaceAid(plan.aid(), plan.cell())) {
            return new Verdict(Step.CELL_LOST, null);
        }
        BlockHitResult hit = scene.sightHit();
        if (plan.aid() == LandingAid.WATER) return new Verdict(Step.RELEASE, hit);
        boolean onTarget = hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(plan.clicked())
                && hit.getDirection() == plan.face();
        if (onTarget) return new Verdict(Step.RELEASE, hit);
        return new Verdict(inReach(scene, plan) ? Step.MISALIGNED : Step.OUT_OF_REACH, hit);
    }
}
