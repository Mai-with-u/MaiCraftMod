// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.function.Supplier;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.calc.NavGoal;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.behavior.survival.SurvivalFakes;
import org.maiwithu.maicraft.behavior.survival.SurvivalSituation;
import org.maiwithu.maicraft.kernel.result.Problem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行走段的呼吸预算闸：头潜进水里赶路、氧气不够浮上去（或已落到换气需求要插空的区间）就放弃这段路
 * 如实收场；潜泳路线段自己管氧气、坐着船车不走路、只有脚泡水头露在外，都不触发这道闸。
 */
class WalkSurvivalGuardsTest {

    private static final BlockPos 出发 = new BlockPos(2, 64, 3);
    private static final BlockPos 目标 = new BlockPos(10, 64, 12);

    private static WalkRunProgress progress() {
        return new WalkRunProgress(GoalCompiler.standOn(目标).goal());
    }

    /** 头在水里、氧气只剩一泡：呼吸预算裁为放弃上浮。 */
    private static Supplier<SurvivalSituation> 快没气了() {
        SurvivalSituation situation = SurvivalFakes.calm().underwater(30).build();
        return () -> situation;
    }

    private static boolean guard(WalkRunProgress progress, PlanningCeiling ceiling,
            boolean headInWater, boolean passenger, boolean submergedTravelActive) {
        return WalkSurvivalGuards.guard(progress, ceiling, null, 出发, true, false, false,
                headInWater, passenger, submergedTravelActive, 快没气了());
    }

    @Test
    void 头入水氧气见底就收场上浮() {
        WalkRunProgress progress = progress();
        assertTrue(guard(progress, new PlanningCeiling(300), true, false, false));
        WalkReport report = progress.report();
        assertEquals(WalkReport.State.FAILED, report.state());
        assertEquals(Problem.Kind.UNREACHABLE, report.problem().kind());
        assertTrue(report.problem().message().contains("氧气只剩 30"), report.problem().message());
        assertTrue(report.problem().message().contains("浮上水面换气"), report.problem().message());
    }

    @Test
    void 头入水但氧气还足就继续走() {
        WalkRunProgress progress = progress();
        SurvivalSituation situation = SurvivalFakes.calm().underwater(300).build();
        boolean done = WalkSurvivalGuards.guard(progress, new PlanningCeiling(300), null, 出发,
                true, false, false, true, false, false, () -> situation);
        assertFalse(done);
        assertEquals(WalkReport.State.PLANNING, progress.report().state());
    }

    @Test
    void 潜泳路线段自己管氧气不触发这道闸() {
        WalkRunProgress progress = progress();
        assertFalse(guard(progress, new PlanningCeiling(300), true, false, true));
        assertEquals(WalkReport.State.PLANNING, progress.report().state());
    }

    @Test
    void 坐着船车头在水里也不收场() {
        WalkRunProgress progress = progress();
        assertFalse(guard(progress, new PlanningCeiling(300), true, true, false));
        assertEquals(WalkReport.State.PLANNING, progress.report().state());
    }

    @Test
    void 只有脚泡水头露在外不触发() {
        WalkRunProgress progress = progress();
        assertFalse(guard(progress, new PlanningCeiling(300), false, false, false));
        assertEquals(WalkReport.State.PLANNING, progress.report().state());
    }

    @Test
    void 已有结论的运行不再动它的账() {
        WalkRunProgress progress = progress();
        Problem 原账 = Problem.of(Problem.Kind.STUCK, "早就收场了", null);
        progress.fail(原账, 出发);
        assertTrue(guard(progress, new PlanningCeiling(300), true, false, false));
        assertEquals(原账, progress.report().problem());
    }

    @Test
    void 算路连续超上限按算路死收场() {
        WalkRunProgress progress = progress();
        // 上限 1 刻：还在算路、这一刻引擎在算，账满即收。
        assertTrue(WalkSurvivalGuards.guard(progress, new PlanningCeiling(1), null, 出发,
                true, false, true, false, false, false, 快没气了()));
        WalkReport report = progress.report();
        assertEquals(WalkReport.State.FAILED, report.state());
        assertTrue(report.problem().message().contains("连续算路"), report.problem().message());
    }

    @Test
    void 上路了算路账清零不收场() {
        WalkRunProgress progress = progress();
        progress.observe(new WalkRunProgress.Observation(出发, true, false, true, false, false));
        assertEquals(WalkReport.State.ON_THE_WAY, progress.report().state());
        assertFalse(WalkSurvivalGuards.guard(progress, new PlanningCeiling(1), null, 出发,
                true, false, true, false, false, false, 快没气了()));
        assertEquals(WalkReport.State.ON_THE_WAY, progress.report().state());
    }
}
