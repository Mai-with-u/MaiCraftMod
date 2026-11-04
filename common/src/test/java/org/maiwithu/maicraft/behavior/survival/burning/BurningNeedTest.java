// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.burning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.maiwithu.maicraft.behavior.survival.burning.BurningFakes.Situation.calm;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.interrupt.ControlLoop;
import org.maiwithu.maicraft.kernel.interrupt.RoutineNeedsOff;
import org.maiwithu.maicraft.kernel.interrupt.SurvivalNeedsOff;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;
import org.maiwithu.maicraft.kernel.task.Urgency;

/** 着火报急：岩浆、火里是立刻；陆地着火按烧不烧得死、手边有没有水和药分；有防火效果不报。 */
class BurningNeedTest {

    @Test
    void lavaWithoutFireResistanceIsNow() {
        assertEquals(Urgency.NOW, BurningNeed.assess(calm().inLava().build()));
    }

    @Test
    void fireResistanceKeepsLavaQuietUntilItIsAboutToRunOut() {
        assertNull(BurningNeed.assess(calm().inLava().resistant(200).build()), "防火还剩 10 秒，岩浆伤不着");
        assertEquals(Urgency.NOW, BurningNeed.assess(calm().inLava().resistant(60).build()), "剩 3 秒按没有算");
    }

    @Test
    void standingInFireOrOnALitCampfireIsNowWhenThereIsAStepOut() {
        assertEquals(Urgency.NOW, BurningNeed.assess(calm().inFire(BurningFakes.FEET.east()).build()));
        // 四面都迈不出去：没有能做的，不报（不然建出马上收场的任务每刻空转），火烧不死时就熬着。
        assertNull(BurningNeed.assess(calm().inFire(null).build()));
    }

    @Test
    void burningOnLandWithWaterNearbyIsSoonExceptWhereWaterBoils() {
        assertEquals(Urgency.SOON, BurningNeed.assess(calm().burning(false).water(3, 3).build()));
        assertNull(BurningNeed.assess(calm().burning(false).water(3, 3).nether().build()), "下界不报跳水");
        assertNull(BurningNeed.assess(calm().burning(false).build()), "没水就熬着");
    }

    @Test
    void aLethalBurnIsNowWhenThereIsWaterOrAPotion() {
        assertEquals(Urgency.NOW, BurningNeed.assess(calm().burning(true).potion().build()));
        assertEquals(Urgency.NOW, BurningNeed.assess(calm().burning(true).water(2, 2).build()));
        assertEquals(Urgency.NOW, BurningNeed.assess(calm().burning(true).water(4, 4).build()));
        assertNull(BurningNeed.assess(calm().burning(true).build()), "没药没水，只能熬着，提醒照挂");
    }

    @Test
    void notBurningIsNothing() {
        assertNull(BurningNeed.assess(calm().build()));
    }

    @Test
    void itIsAnImmediateDangerThatNeverWaitsAfterFailing() {
        BurningNeed need = new BurningNeed(context -> null, new BurningFakes.FakeMoves(), new BurningFakes.Events());
        assertTrue(need.reactsToImmediateDanger(), "主目标关了起居类需求也照样自救");
        assertFalse(need.waitsAfterFailure(), "没做成不缓，下一刻按新处境再判");
        assertNull(need.urgency(null), "读不到处境不报");
    }

    @Test
    void suicideKeepsItOutButRoutineNeedsOffLetsItIn() {
        // 寻死这类一个生存需求都不要的任务：泡在岩浆里也不插自救。
        Main suicide = new Main(true, false);
        assertTrue(loopRunning(suicide).currentTask() == suicide, "寻死时不插着火自救");
        // 主目标许可写 survival_needs=off：只停起居，泡在岩浆里照样先出岩浆。
        assertTrue(loopRunning(new Main(false, true)).currentTask() instanceof BurningTask);
    }

    // 泡在岩浆里的角色挂着这个主任务推进几刻。
    private static ControlLoop loopRunning(Main main) {
        BurningNeed need = new BurningNeed(context -> calm().inLava().build(), new BurningFakes.FakeMoves(),
                new BurningFakes.Events());
        ControlLoop loop = new ControlLoop(List.of(need));
        loop.setMainTask(main);
        for (long tick = 0; tick < 3; tick++) loop.tick(TickContext.of(tick, null));
        return loop;
    }

    /** 主任务替身：可以声明不要任何生存需求（寻死），或只关起居类（许可 survival_needs=off）。 */
    private record Main(boolean noNeeds, boolean noRoutine) implements Task, SurvivalNeedsOff, RoutineNeedsOff {
        @Override public TickResult tick(TickContext context) {
            return TickResult.RUNNING;
        }

        @Override public void pause() {}

        @Override public TaskResult close(CloseReason reason) {
            return TaskResult.done("收场");
        }

        @Override public Interruptibility interruptibility(TickContext context) {
            return Interruptibility.WORKING;
        }

        @Override public String describe() {
            return "主任务";
        }

        @Override public boolean survivalNeedsOff() {
            return noNeeds;
        }

        @Override public boolean routineNeedsOff() {
            return noRoutine;
        }
    }
}
