// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.interrupt;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.interrupt.ControlLoopTest.FakeNeed;
import org.maiwithu.maicraft.kernel.interrupt.ControlLoopTest.FakeTask;
import org.maiwithu.maicraft.kernel.interrupt.ControlLoopTest.RecordingMain;
import org.maiwithu.maicraft.kernel.interrupt.ControlLoopTest.RoutineOffTask;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.FinishesWithoutControl;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Urgency;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插一下：几秒做完的事压在手上的事上面做完就让回去，主任务从原地接着做；腾不出身子等 3 秒就放弃；
 * 只让立刻的急事打断；不发临时任务事件；起居需求关掉时照插；角色不在了排着的全部收场。
 */
class ControlLoopCutInTest {

    private long tick = 1;

    private ControlLoop.Decision step(ControlLoop loop) {
        return loop.tick(new Tick(tick++));
    }

    @Test
    void cutInRunsOverAWorkingMainTaskAndTheMainTaskResumes() {
        // 角色在砌墙：插一下（点个头）当刻压上去，做完弹出，墙接着砌；主任务没被替换，也没被收尾。
        FakeTask main = new FakeTask("砌墙", Interruptibility.WORKING);
        ControlLoop loop = new ControlLoop(List.of());
        loop.setMainTask(main);
        step(loop);

        FakeTask nod = new FakeTask("点头", Interruptibility.WORKING).finishingAfter(2);
        List<TaskResult> gaveUp = new ArrayList<>();
        assertTrue(loop.cutIn(nod, gaveUp::add));

        step(loop);
        assertSame(nod, loop.currentTask(), "砌墙两下之间就插进来");
        assertSame(nod, loop.cutInRunning());
        assertNull(loop.occupyingTemporaryTask(), "插一下是 LLM 下达的目标，不算生存需求占着身体");
        assertEquals(1, main.pauses, "砌墙的手先停下");

        step(loop);
        step(loop);
        assertSame(main, loop.currentTask(), "点完头回去砌墙");
        assertNull(loop.cutInRunning());
        assertFalse(main.closed, "主任务没被替换");
        assertTrue(gaveUp.isEmpty());
    }

    @Test
    void cutInWaitsWhileStoppingIsUnsafeAndGivesUpAfterThreeSeconds() {
        // 开着车停不下：插一下等着，不叫手上的事先找安全处停；3 秒还腾不出身子就以"这会儿不行"收场。
        ControlLoopTest.LandingTask driving = new ControlLoopTest.LandingTask("开车");
        ControlLoop loop = new ControlLoop(List.of());
        loop.setMainTask(driving);
        step(loop);

        List<TaskResult> gaveUp = new ArrayList<>();
        loop.cutIn(new FakeTask("蹲两下", Interruptibility.WORKING), gaveUp::add);
        for (int i = 0; i <= CutIns.WAIT_TICKS; i++) {
            step(loop);
            assertSame(driving, loop.currentTask(), "停下不安全时不插");
        }
        assertEquals(0, driving.stopAsks, "不为一个肢体动作叫开着的车先停");
        assertTrue(gaveUp.isEmpty(), "3 秒以内还等着");

        step(loop);
        assertEquals(1, gaveUp.size());
        TaskResult result = gaveUp.get(0);
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.WRONG_TIME, result.problem().kind());
        assertTrue(result.summary().contains("开车"), "说清是手上什么事停不下来：" + result.summary());
    }

    @Test
    void onlyAnImmediateNeedInterruptsACutIn() {
        // 插一下在做时，"尽快"的需求（天黑了去睡）等它做完；"立刻"的（快淹死了）照样打断它。
        ControlLoop soonLoop = new ControlLoop(List.of(FakeNeed.of("夜晚", new Urgency[] {null, Urgency.SOON})));
        soonLoop.setMainTask(new FakeTask("砌墙", Interruptibility.WORKING));
        FakeTask shake = new FakeTask("摇头", Interruptibility.WORKING).finishingAfter(5);
        soonLoop.cutIn(shake, result -> { });
        step(soonLoop);
        assertSame(shake, soonLoop.currentTask(), "这一刻没有别的需求，插一下先上");
        step(soonLoop);
        assertSame(shake, soonLoop.currentTask(), "尽快的需求等插一下做完");

        ControlLoop nowLoop = new ControlLoop(List.of(FakeNeed.of("换气", new Urgency[] {null, Urgency.NOW})));
        nowLoop.setMainTask(new FakeTask("砌墙", Interruptibility.WORKING));
        FakeTask wave = new FakeTask("挥手", Interruptibility.WORKING).finishingAfter(5);
        nowLoop.cutIn(wave, result -> { });
        step(nowLoop);
        assertSame(wave, nowLoop.currentTask());
        step(nowLoop);
        assertFalse(nowLoop.currentTask() == wave, "立刻的急事打断插一下");
        assertSame(wave, nowLoop.cutInRunning(), "被压着的插一下还在栈上，急事处理完接着做");
    }

    @Test
    void aSoonTempTaskAlreadyRunningGoesFirst() {
        // 自卫这类同样"尽快"的临时任务正在跑：插一下排在它后面，不把它挤下去。
        FakeTask defend = new FakeTask("自卫", Interruptibility.WORKING).finishingAfter(3);
        ControlLoop loop = new ControlLoop(List.of(FakeNeed.of("自卫", () -> defend, Urgency.SOON, null)));
        loop.setMainTask(new FakeTask("砌墙", Interruptibility.WORKING));
        step(loop);
        assertSame(defend, loop.currentTask());

        FakeTask nod = new FakeTask("点头", Interruptibility.WORKING).finishingAfter(1);
        loop.cutIn(nod, result -> { });
        step(loop);
        assertSame(defend, loop.currentTask(), "同样急的临时任务在跑，插一下排后面");
    }

    @Test
    void atMostFourWaitAndTheFifthIsRefused() {
        ControlLoop loop = new ControlLoop(List.of());
        for (int i = 0; i < ControlLoop.CUT_IN_QUEUE; i++) {
            assertTrue(loop.cutIn(new FakeTask("动作" + i, Interruptibility.WORKING), result -> { }));
        }
        assertFalse(loop.cutIn(new FakeTask("第五个", Interruptibility.WORKING), result -> { }));
    }

    @Test
    void cutInsGoOneAfterAnotherInOrder() {
        // 一口气下了两个：先下的先做，第二个等第一个做完才轮上，等待时间从轮到它才开始算。
        FakeTask main = new FakeTask("砌墙", Interruptibility.WORKING);
        ControlLoop loop = new ControlLoop(List.of());
        loop.setMainTask(main);
        FakeTask first = new FakeTask("蹲", Interruptibility.WORKING).finishingAfter(CutIns.WAIT_TICKS + 10);
        FakeTask second = new FakeTask("点头", Interruptibility.WORKING).finishingAfter(3);
        List<TaskResult> gaveUp = new ArrayList<>();
        loop.cutIn(first, gaveUp::add);
        loop.cutIn(second, gaveUp::add);

        step(loop);
        assertSame(first, loop.currentTask());
        while (loop.currentTask() == first) {
            step(loop);
        }
        // 第一个弹出的那一刻手上的事接着推进一下，下一刻第二个才插进来。
        step(loop);
        assertSame(second, loop.currentTask(), "第一个做完轮到第二个");
        assertTrue(gaveUp.isEmpty(), "第二个没被第一个耗掉等待时间");
    }

    @Test
    void cutInPublishesNoTemporaryTaskEventsAndKeepsItsResultApart() {
        // 插一下有目标自己的开始、结束事件；临时任务的成对事件不发，结果也不并进主任务。
        RecordingEvents events = new RecordingEvents();
        RecordingMain main = new RecordingMain();
        ControlLoop loop = new ControlLoop(List.of(), events);
        loop.setMainTask(main);
        loop.cutIn(new FakeTask("点头", Interruptibility.WORKING).finishingAfter(1), result -> { });
        step(loop);
        step(loop);
        step(loop);

        assertTrue(events.published.isEmpty(), "不发临时任务事件：" + events.published);
        assertTrue(main.received.isEmpty(), "结果记在插一下自己名下");
    }

    @Test
    void routineNeedsOffStillLetsACutInThrough() {
        // 主目标关掉起居需求（survival_needs=off）：吃饭睡觉不插，LLM 自己下的插一下照插。
        RoutineOffTask main = new RoutineOffTask(Interruptibility.WORKING);
        ControlLoop loop = new ControlLoop(List.of(FakeNeed.always("饥饿", Urgency.SOON)));
        loop.setMainTask(main);
        FakeTask nod = new FakeTask("点头", Interruptibility.WORKING).finishingAfter(3);
        loop.cutIn(nod, result -> { });
        step(loop);
        assertSame(nod, loop.currentTask());
    }

    @Test
    void leavingTheWorldDropsEveryQueuedCutIn() {
        ControlLoopTest.LandingTask driving = new ControlLoopTest.LandingTask("开车");
        ControlLoop loop = new ControlLoop(List.of());
        loop.setMainTask(driving);
        List<TaskResult> gaveUp = new ArrayList<>();
        loop.cutIn(new FakeTask("蹲", Interruptibility.WORKING), gaveUp::add);
        loop.cutIn(new FakeTask("点头", Interruptibility.WORKING), gaveUp::add);

        loop.leaveWorld();
        assertEquals(2, gaveUp.size());
        assertTrue(gaveUp.stream().allMatch(result -> result.status() == TaskResult.Status.CANCELLED));
    }

    @Test
    void cutInThatCanFinishWithoutControlCompletesAfterHalfSecondEvenDuringSelfDefense() {
        // 自卫占着身体时下达公屏话：等半秒（10 刻）还腾不出身子就直接不占身体发出去，status=DONE，自卫不断。
        FakeTask defend = new FakeTask("自卫", Interruptibility.WORKING).finishingAfter(40);
        ControlLoop loop = new ControlLoop(List.of(FakeNeed.of("自卫", () -> defend, Urgency.NOW)));
        loop.setMainTask(new FakeTask("挖矿", Interruptibility.WORKING));
        step(loop);
        assertSame(defend, loop.currentTask());

        DirectCutInTask chat = new DirectCutInTask();
        List<TaskResult> settled = new ArrayList<>();
        assertTrue(loop.cutIn(chat, settled::add));
        for (int i = 0; i <= CutIns.DIRECT_WAIT_TICKS; i++) {
            step(loop);
            assertSame(defend, loop.currentTask(), "半秒内自卫照常推进");
        }
        assertTrue(settled.isEmpty(), "满 10 刻前还给逐字打字留着机会");

        step(loop);
        assertEquals(1, settled.size(), "超过半秒仍腾不出身子：当场不占身体办完");
        assertEquals(TaskResult.Status.DONE, settled.getFirst().status());
        assertSame(defend, loop.currentTask(), "自卫一刻都没被打断");
    }

    private static final class DirectCutInTask extends FakeTask implements FinishesWithoutControl {
        DirectCutInTask() {
            super("说话", Interruptibility.WORKING);
        }

        @Override public TaskResult finishWithoutControl(TickContext context) {
            return TaskResult.done("身体正忙，没有逐字打，这句话直接发出去了");
        }
    }

    private record Tick(long gameTick) implements TickContext {
        @Override public PlayerContext player() {
            return null;
        }
    }

    /** 记下发过的事件。 */
    private static final class RecordingEvents implements TaskEventSink {
        final List<String> published = new ArrayList<>();

        @Override public void publish(TaskEvent.Kind kind, String message) {
            published.add(kind + ":" + message);
        }
    }
}
