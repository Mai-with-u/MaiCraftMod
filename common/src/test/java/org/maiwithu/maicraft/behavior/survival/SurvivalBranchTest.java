// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.List;

import org.maiwithu.maicraft.behavior.survival.night.AutoSleep;
import org.maiwithu.maicraft.behavior.survival.fall.EdgeProximityNeed;
import org.maiwithu.maicraft.behavior.survival.hunger.HungerNeed;
import org.maiwithu.maicraft.behavior.survival.night.NightfallNeed;
import org.maiwithu.maicraft.behavior.combat.ThreatAssessment;

import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.kernel.interrupt.ControlLoop;
import org.maiwithu.maicraft.kernel.interrupt.KeepsCompany;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;
import org.maiwithu.maicraft.kernel.task.Urgency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 饥饿与夜晚、贴边的分流：三档阈值、有床睡无床看处境、贴边只在贴着深落差时才算。 */
class SurvivalBranchTest {

    @Test
    void hungerThreeLevels() {
        assertEquals(Urgency.NOW, HungerNeed.assess(new HungerNeed.Facts(0, true, 5)));
        assertEquals(Urgency.SOON, HungerNeed.assess(new HungerNeed.Facts(6, false, 0)));
        assertEquals(Urgency.SOON, HungerNeed.assess(new HungerNeed.Facts(3, false, 5)));
        // 低于满但还有吃的：找空当吃；没吃的且还不急：不插。
        assertEquals(Urgency.LATER, HungerNeed.assess(new HungerNeed.Facts(15, false, 5)));
        assertNull(HungerNeed.assess(new HungerNeed.Facts(15, false, 0)));
        // 趁空当吃要整份补得进去：身上最小是面包（5），饱食度 16 时整份补进去会溢出就先不吃，15 时才吃。
        assertNull(HungerNeed.assess(new HungerNeed.Facts(16, false, 5)));
        assertEquals(Urgency.LATER, HungerNeed.assess(new HungerNeed.Facts(15, false, 5)));
        assertNull(HungerNeed.assess(new HungerNeed.Facts(20, false, 5)));
        // 受了伤、不到回血线（18）：溢出也吃，血才回得来（实机 13 点血、饱食度 17 一直不回血）。
        assertEquals(Urgency.LATER, HungerNeed.assess(new HungerNeed.Facts(17, false, 5, true)));
        assertNull(HungerNeed.assess(new HungerNeed.Facts(18, false, 5, true)), "到了回血线就不用吃");
        // 伤得重（血不到一半）、没有普通食物、不到回血线：趁空当吃一口腐肉过线，或去弄吃的（实机两点血揣着腐肉不回血）。
        HungerNeed.Facts hurt = new HungerNeed.Facts(14, false, 0, true, true, 4);
        assertEquals(Urgency.LATER, HungerNeed.assess(hurt));
        assertTrue(hurt.junkReachesRegen(), "14 加腐肉的 4 刚好到回血线");
        assertFalse(new HungerNeed.Facts(10, false, 0, true, true, 4).junkReachesRegen(), "吃了也不到线：去弄吃的");
        assertNull(HungerNeed.assess(new HungerNeed.Facts(14, false, 0, true, false, 4)), "只是掉了点血不为腐肉停手");
        assertNull(HungerNeed.assess(new HungerNeed.Facts(18, false, 0, true, true, 4)), "到了回血线自己会回");
    }

    @Test
    void nightBranching() {
        // 不在可睡时段：不插。
        assertNull(NightfallNeed.branch(new NightfallNeed.Facts(false, false,
                ThreatAssessment.Verdict.WINNABLE, 20, 15), true, true));
        // 能弄到床（附近有、或身上带着一张）：找空当去睡（夜间休息），只在两个动作之间插进来。
        assertEquals(Urgency.LATER, NightfallNeed.branch(new NightfallNeed.Facts(true, false,
                ThreatAssessment.Verdict.WINNABLE, 20, 15), true, true));
        // 身上带着床又露天打不过：照样走夜间休息放下睡，不去备床。
        assertEquals(Urgency.LATER, NightfallNeed.branch(new NightfallNeed.Facts(true, false,
                ThreatAssessment.Verdict.OUTMATCHED, 20, 15), true, true));
        // 弄不到床、安全处（矿道、屋里、照明充足）：接着干。
        assertNull(NightfallNeed.branch(new NightfallNeed.Facts(true, true,
                ThreatAssessment.Verdict.OUTMATCHED, 20, 15), true, false));
        // 弄不到床、露天但打得过：接着干。
        assertNull(NightfallNeed.branch(new NightfallNeed.Facts(true, false,
                ThreatAssessment.Verdict.WINNABLE, 20, 15), true, false));
        // 弄不到床、露天又打不过：去备一张床来睡（跑路做床）；不原地挖坑等天亮，被打交给自卫。
        assertEquals(Urgency.SOON, NightfallNeed.branch(new NightfallNeed.Facts(true, false,
                ThreatAssessment.Verdict.OUTMATCHED, 20, 15), true, false));
        // 今晚不去睡（开关关了、陪着醒着的人）：有床也不去睡；露天打不过也不备床，不占身体。
        assertNull(NightfallNeed.branch(new NightfallNeed.Facts(true, false,
                ThreatAssessment.Verdict.WINNABLE, 20, 15), false, true));
        assertNull(NightfallNeed.branch(new NightfallNeed.Facts(true, false,
                ThreatAssessment.Verdict.OUTMATCHED, 20, 15), false, true));
        assertNull(NightfallNeed.branch(new NightfallNeed.Facts(true, false,
                ThreatAssessment.Verdict.OUTMATCHED, 20, 15), false, false));
    }

    @Test
    void nightWithAutoSleepOffDoesNotGoToBed() {
        // 有人说"晚上别睡"：自动去睡关掉后有床也不去睡；露天打不过也不去备床，挨打交给自卫与战斗引擎。打开后恢复。
        AutoSleep autoSleep = new AutoSleep();
        NightfallNeed.Facts night = new NightfallNeed.Facts(true, false, ThreatAssessment.Verdict.WINNABLE, 20, 15);
        NightfallNeed.Facts danger = new NightfallNeed.Facts(true, false, ThreatAssessment.Verdict.OUTMATCHED, 20, 15);
        NightfallNeed.Facts[] now = {night};
        NightfallNeed need = new NightfallNeed(context -> now[0], context -> true,
                context -> null, context -> null, autoSleep);
        TickContext tick = TickContext.of(0, null);
        assertEquals(Urgency.LATER, need.urgency(tick));
        autoSleep.disable();
        assertNull(need.urgency(tick), "关掉后有床也不去睡");
        now[0] = danger;
        assertNull(need.urgency(tick), "关掉后露天打不过也不备床");
        now[0] = night;
        autoSleep.enable();
        assertEquals(Urgency.LATER, need.urgency(tick));
    }

    /** 陪人的任务替身：只答陪着的人睡没睡，记下被推进了几刻。 */
    private static final class Companion implements Task, KeepsCompany {
        boolean asleep;
        int ticks;

        @Override public boolean companionAsleep() { return asleep; }
        @Override public TickResult tick(TickContext context) {
            ticks++;
            return TickResult.RUNNING;
        }
        @Override public void pause() {}
        @Override public TaskResult close(CloseReason reason) { return TaskResult.done("结束"); }
        @Override public String describe() { return "陪着人"; }
    }

    /** 认身份的任务替身，顺带记下被推进了几刻（当主任务时看它整晚动没动）。 */
    private static final class Named implements Task {
        int ticks;

        @Override public TickResult tick(TickContext context) {
            ticks++;
            return TickResult.RUNNING;
        }
        @Override public void pause() {}
        @Override public TaskResult close(CloseReason reason) { return TaskResult.done("结束"); }
        @Override public String describe() { return "替身"; }
    }

    /** 备床任务的替身：身上已经有床，一推就当场做完（照真实备床"开始时身上就有床"那一支）。 */
    private static final class BedAlreadyCarried implements Task {
        @Override public TickResult tick(TickContext context) {
            return TickResult.finished(TaskResult.done("身上已经有床了，今晚的床备好了"));
        }
        @Override public void pause() {}
        @Override public TaskResult close(CloseReason reason) { return TaskResult.done("结束"); }
        @Override public String describe() { return "备床"; }
    }

    @Test
    void createTaskFollowsTheBranchUrgencyChose() {
        // 关掉自动去睡后夜里不能再建夜间休息任务（实机关掉后两夜照睡照跳夜），也不建备床；
        // 开着、附近没床又露天打不过建的是备床任务；开着且有床，建的才是夜间休息。
        AutoSleep autoSleep = new AutoSleep();
        NightfallNeed.Facts danger = new NightfallNeed.Facts(true, false, ThreatAssessment.Verdict.OUTMATCHED, 20, 15);
        Task bed = new Named();
        Task rest = new Named();
        boolean[] restAsked = {false};
        boolean[] bedNearby = {true};
        NightfallNeed need = new NightfallNeed(context -> danger, context -> bedNearby[0],
                context -> {
                    restAsked[0] = true;
                    return rest;
                }, context -> bed, autoSleep);
        TickContext tick = TickContext.of(0, null);
        autoSleep.disable();
        assertNull(need.urgency(tick), "关掉后有床也不去睡，露天打不过也不备床");
        assertNull(need.createTask(tick), "关掉后夜里什么任务都不建");
        assertFalse(restAsked[0], "关掉后不再问夜间休息");
        autoSleep.enable();
        assertEquals(Urgency.LATER, need.urgency(tick));
        assertSame(rest, need.createTask(tick), "打开且有床：建夜间休息");
        bedNearby[0] = false;
        assertEquals(Urgency.SOON, need.urgency(tick), "打开、没床、露天打不过：去备床");
        assertSame(bed, need.createTask(tick), "建的是备床任务");
    }

    /**
     * 审计 A42 的现场：今晚不去睡（开关关了 / 陪着醒着的人）、露天又打不过、身上带着床。
     * 原先夜晚需求每刻按 SOON 建一个备床任务，身上有床当场做完、做成不退避，下一刻又建，主任务整晚一刻都推不动。
     * 现在这两种处境不建备床：控制循环连推十刻，一个备床都不建，主任务十刻都被推进。
     */
    @Test
    void notGoingToBedLeavesTheMainTaskAloneThroughTheNight() {
        NightfallNeed.Facts danger = new NightfallNeed.Facts(true, false, ThreatAssessment.Verdict.OUTMATCHED, 20, 15);
        int[] prepared = {0};

        // 开关关了：主任务是普通的活。
        AutoSleep off = new AutoSleep();
        off.disable();
        NightfallNeed offNeed = new NightfallNeed(context -> danger, context -> true, context -> null,
                context -> {
                    prepared[0]++;
                    return new BedAlreadyCarried();
                }, off);
        ControlLoop offLoop = new ControlLoop(List.of(offNeed));
        Named work = new Named();
        offLoop.setMainTask(work);
        for (int tick = 1; tick <= 10; tick++) offLoop.tick(TickContext.of(tick, null));
        assertEquals(0, prepared[0], "关了自动睡：露天打不过一个备床都不建");
        assertEquals(10, work.ticks, "主任务整晚照常推进");

        // 陪着一个醒着的人：主任务就是陪人的任务。
        NightfallNeed companyNeed = new NightfallNeed(context -> danger, context -> true, context -> null,
                context -> {
                    prepared[0]++;
                    return new BedAlreadyCarried();
                });
        ControlLoop companyLoop = new ControlLoop(List.of(companyNeed));
        Companion company = new Companion();
        companyLoop.setMainTask(company);
        for (int tick = 1; tick <= 10; tick++) companyLoop.tick(TickContext.of(tick, null));
        assertEquals(0, prepared[0], "陪着醒着的人：不走开去备床");
        assertEquals(10, company.ticks, "陪人的任务整晚照常推进");
    }

    @Test
    void outmatchedWithoutABedPreparerHoldsNoTask() {
        // 备床的落地没接上（还没进世界）：不占身体，不建任何任务，不原地等天亮。
        NightfallNeed.Facts danger = new NightfallNeed.Facts(true, false, ThreatAssessment.Verdict.OUTMATCHED, 20, 15);
        NightfallNeed need = new NightfallNeed(context -> danger, context -> false, context -> null, null);
        TickContext tick = TickContext.of(0, null);
        assertEquals(Urgency.SOON, need.urgency(tick));
        assertNull(need.createTask(tick), "备床接不上就不建任务，接着干活");
    }

    @Test
    void nightWhileKeepingCompanyWaitsForTheCompanionToSleep() {
        // 跟着一个人：他还醒着就不自己走开去睡，露天又打不过也不走开去备床；他躺下了才去附近找床睡。
        NightfallNeed.Facts night = new NightfallNeed.Facts(true, false, ThreatAssessment.Verdict.WINNABLE, 20, 15);
        NightfallNeed.Facts danger = new NightfallNeed.Facts(true, false, ThreatAssessment.Verdict.OUTMATCHED, 20, 15);
        NightfallNeed.Facts[] now = {night};
        NightfallNeed need = new NightfallNeed(context -> now[0], context -> true, context -> null, context -> null);
        TickContext tick = TickContext.of(0, null);
        Companion company = new Companion();
        assertNull(need.urgency(tick, company), "他醒着：不去睡");
        company.asleep = true;
        assertEquals(Urgency.LATER, need.urgency(tick, company), "他睡下了：找空当去睡");
        company.asleep = false;
        now[0] = danger;
        assertNull(need.urgency(tick, company), "他醒着、露天打不过：不走开去备床，挨打交给自卫");
        now[0] = night;
        assertEquals(Urgency.LATER, need.urgency(tick, null), "手上不是陪人的任务：照常去睡");
    }

    @Test
    void edgeOnlyWhenCloseToDeepDrop() {
        assertTrue(EdgeProximityNeed.atRisk(new EdgeProximityNeed.Facts(0.2, 5, new double[] {0, 0, 0})));
        // 离边远：不算。
        assertFalse(EdgeProximityNeed.atRisk(new EdgeProximityNeed.Facts(1.5, 5, new double[] {0, 0, 0})));
        // 边外只是个台阶：不算。
        assertFalse(EdgeProximityNeed.atRisk(new EdgeProximityNeed.Facts(0.2, 2, new double[] {0, 0, 0})));
        // 四面都是崖、附近没有站得住的地方：退无可退，不插。
        assertFalse(EdgeProximityNeed.atRisk(new EdgeProximityNeed.Facts(0.2, 5, null)));
    }

    @Test
    void edgeRetreatOnlyWhenNoTaskHoldsTheBody() {
        // 手上有任务时贴着边是那件事要的姿态，不往回退；闲着才退，免得退一步、走回去、再退一步来回抖。
        EdgeProximityNeed need = new EdgeProximityNeed(
                context -> new EdgeProximityNeed.Facts(0.2, 5, new double[] {1, 64, 1}), spot -> null);
        Task bridging = new Task() {
            @Override public TickResult tick(TickContext context) { return TickResult.RUNNING; }
            @Override public TaskResult close(CloseReason reason) { return null; }
            @Override public void pause() {}
            @Override public String describe() { return "搭桥"; }
        };

        assertNull(need.urgency(null, bridging));
        assertEquals(Urgency.LATER, need.urgency(null, null));
    }
}
