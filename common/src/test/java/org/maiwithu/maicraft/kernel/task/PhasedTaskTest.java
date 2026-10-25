// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 分阶段任务的行为：阶段走向、卡住判定、被打断与收尾、结果组装。 */
class PhasedTaskTest {

    enum Phase { APPROACH, LIE_DOWN, DECIDE }

    /** 用一张"阶段 → 动作"表拼出的任务，模拟"走到床边 → 躺下"这类两段流程。 */
    static final class GoToBed extends PhasedTask<Phase> {
        final Map<Phase, ScriptedAction> actions;

        GoToBed(Map<Phase, ScriptedAction> actions, ProgressTracker tracker) {
            super("测试", Phase.APPROACH, tracker);
            this.actions = actions;
        }

        @Override protected Action enter(Phase phase) {
            return actions.get(phase);
        }

        @Override protected Next<Phase> tick(Phase phase, TickContext context) {
            return switch (phase) {
                case APPROACH -> runActionThen(context, () -> Next.go(Phase.LIE_DOWN, "到了"));
                case LIE_DOWN -> runActionThen(context, () -> {
                    recordChange(Change.of(Change.Kind.OTHER, "minecraft:red_bed", 1));
                    return Next.done(TaskResult.done("躺下了"));
                });
                case DECIDE -> Next.stay();
            };
        }

        @Override protected List<String> remaining() {
            return List.of("躺下");
        }
    }

    /** 停下前要先落地的动作替身：被叫先停稳后，落地了才算停稳；没被叫过时按默认说"没在收尾"。 */
    static final class Flying implements Action {
        private final ActionStatus status;
        boolean asked;
        boolean landed;

        Flying(ActionStatus status) {
            this.status = status;
        }

        @Override public ActionStatus tick(TickContext context) {
            return status;
        }

        @Override public boolean stopSafely() {
            asked = true;
            return true;
        }

        @Override public boolean stoppedSafely() {
            return !asked || landed;
        }

        @Override public String describe() {
            return "飞着";
        }
    }

    @Test
    void safeStopCarriesIntoNextPhaseAction() {
        // 收尾途中换了阶段、新阶段又起飞：新动作也得被叫先停稳，落地前不算停好，不能在半空被暂停。
        Flying approach = new Flying(ActionStatus.done());
        Flying lieDown = new Flying(ActionStatus.running());
        var task = new PhasedTask<Phase>("测试", Phase.APPROACH, new ProgressTracker(100, 1000)) {
            @Override protected Action enter(Phase phase) {
                return phase == Phase.APPROACH ? approach : lieDown;
            }

            @Override protected Next<Phase> tick(Phase phase, TickContext context) {
                return phase == Phase.APPROACH ? runActionThen(context, () -> Next.go(Phase.LIE_DOWN, "到了"))
                        : runActionThen(context, () -> Next.done(TaskResult.done("躺下了")));
            }
        };
        task.start(new TestTick(0));
        assertTrue(task.stopSafely());
        task.tick(new TestTick(1));
        assertTrue(lieDown.asked);
        assertFalse(task.stoppedSafely());
        lieDown.landed = true;
        assertTrue(task.stoppedSafely());
    }

    /** 手上拿着几件事的任务：第一个阶段把给的事登记到手上，模拟跟随这类一个阶段里同时推进多件事的走法。 */
    static final class Errands extends PhasedTask<Phase> {
        private final List<Action> given;
        private boolean held;
        /** 换阶段时只带这一件手上的事过去；为 null 表示都不带。 */
        Action kept;
        /** 置位后下一步就换阶段。 */
        boolean go;

        Errands(List<Action> given) {
            super("跑腿", Phase.APPROACH, new ProgressTracker(100, 1000));
            this.given = given;
        }

        @Override protected Action enter(Phase phase) {
            return null;
        }

        @Override protected boolean carriesInto(Phase nextPhase, Action hand) {
            return hand == kept;
        }

        @Override protected Next<Phase> tick(Phase phase, TickContext context) {
            if (!held) {
                held = true;
                for (Action hand : given) {
                    hold(hand);
                }
            }
            if (phase == Phase.APPROACH) return go ? Next.go(Phase.LIE_DOWN, "到了") : Next.stay();
            return Next.done(TaskResult.done("做完了"));
        }

        /** 做到一半又接了一件手上的事。 */
        void takeUp(Action hand) {
            hold(hand);
        }

        /** 手上的一件事做完了：任务自己收尾并放下，基类收场时不再收它一次。 */
        void letGoOf(Action hand) {
            hand.close();
            release(hand);
        }
    }

    @Test
    void pauseAndCloseReachEveryHand() {
        // 被生存需求打断：手上的每一样都跟着松手；被取消收场：每一样都结清，只收一次。
        ScriptedAction walk = new ScriptedAction("走路", ActionStatus.running());
        ScriptedAction fight = new ScriptedAction("帮打", ActionStatus.running());
        var task = new Errands(List.of(walk, fight));
        task.start(new TestTick(0));
        task.tick(new TestTick(1));

        task.pause();
        assertEquals(1, walk.pauses, "手上的路跟着暂停");
        assertEquals(1, fight.pauses, "手上的仗跟着暂停");

        TaskResult result = task.close(CloseReason.CANCELLED);
        assertEquals(1, walk.closes, "收场时手上的路结清");
        assertEquals(1, fight.closes, "收场时手上的仗结清");
        assertEquals(TaskResult.Status.CANCELLED, result.status());
    }

    @Test
    void phaseChangeKeepsTheCarriedHandAndClosesTheRest() {
        // 换阶段：说好带过去的那件照原样接着做，其余的当场收尾；被替换真收场时带过来的也要结清。
        ScriptedAction walk = new ScriptedAction("走路", ActionStatus.running());
        ScriptedAction fight = new ScriptedAction("帮打", ActionStatus.running());
        var task = new Errands(List.of(walk, fight));
        task.kept = walk;
        task.start(new TestTick(0));
        task.tick(new TestTick(1));
        task.go = true;
        task.tick(new TestTick(2));

        assertEquals(0, walk.closes, "带进下一阶段的手上的事不收");
        assertEquals(1, fight.closes, "不带的手上的事换阶段就收尾");

        task.close(CloseReason.REPLACED);
        assertEquals(1, walk.closes, "被替换收场时带过来的那件也结清");
    }

    @Test
    void handsJoinedAndFinishedMidPhase() {
        // 做到一半接了一件、做完了一件自己放下：收场时新接的收尾、放下的不再收第二次。
        ScriptedAction walk = new ScriptedAction("走路", ActionStatus.running());
        ScriptedAction board = new ScriptedAction("上车", ActionStatus.running());
        ScriptedAction look = new ScriptedAction("看着他", ActionStatus.running());
        var task = new Errands(List.of(walk));
        task.start(new TestTick(0));
        task.tick(new TestTick(1));
        task.takeUp(board);
        task.takeUp(look);
        task.letGoOf(look);

        task.close(CloseReason.CANCELLED);
        assertEquals(1, walk.closes, "还在手上的收尾");
        assertEquals(1, board.closes, "半路接进来的也算手上的，收场时结清");
        assertEquals(1, look.closes, "已经放下并收尾的不再收一次");
    }

    @Test
    void safeStopAsksEveryHandAndWaitForItToSettle() {
        // 手上悬着一件要先落地的：叫先停稳转给它，落地前任务不报停稳；其余手上的事当场就能停。
        Flying drone = new Flying(ActionStatus.running());
        ScriptedAction look = new ScriptedAction("看着他", ActionStatus.running());
        var task = new Errands(List.of(drone, look));
        task.start(new TestTick(0));
        task.tick(new TestTick(1));

        assertTrue(task.stopSafely(), "悬着的那件要先落地");
        assertTrue(drone.asked);
        assertFalse(task.stoppedSafely(), "还没落地不算停稳");
        drone.landed = true;
        assertTrue(task.stoppedSafely(), "落地了才算停稳");
    }

    @Test
    void handTakenUpWhileStoppingIsAskedToStopSafelyToo() {
        // 收尾途中又接了一件要先落地的（跟随换一趟路刚起跳）：它也得被叫先停稳，落地前任务不报停稳（审计 A48）。
        Flying drone = new Flying(ActionStatus.running());
        var task = new Errands(List.of(drone));
        task.start(new TestTick(0));
        task.tick(new TestTick(1));
        assertTrue(task.stopSafely(), "悬着的那件要先落地");
        Flying jumped = new Flying(ActionStatus.running());
        task.takeUp(jumped);
        assertTrue(jumped.asked, "收尾途中新接的一件也被叫先停稳");
        drone.landed = true;
        assertFalse(task.stoppedSafely(), "新接的那件还没落地");
        jumped.landed = true;
        assertTrue(task.stoppedSafely());
    }

    private static TickResult runUntilFinished(Task task, int maxTicks) {
        TickResult tickResult = TickResult.RUNNING;
        for (int tick = 0; tick < maxTicks && tickResult instanceof TickResult.Running; tick++) {
            tickResult = task.tick(new TestTick(tick));
        }
        return tickResult;
    }

    private static TaskResult resultOf(TickResult tickResult) {
        return assertInstanceOf(TickResult.Finished.class, tickResult).result();
    }

    @Test
    void walksThroughPhasesAndAssemblesResult() {
        ScriptedAction approach = new ScriptedAction("走向床边", ActionStatus.progressed(), ActionStatus.done());
        ScriptedAction lieDown = new ScriptedAction("右键床头", ActionStatus.running(), ActionStatus.done());
        var task = new GoToBed(Map.of(Phase.APPROACH, approach, Phase.LIE_DOWN, lieDown), new ProgressTracker(20, 1000));

        TaskResult result = resultOf(runUntilFinished(task, 10));

        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals("躺下了", result.summary());
        assertEquals(1, result.changes().size(), "运行中记下的变化要进结果");
        assertEquals(1, approach.closes, "换阶段时旧动作要收尾");
        assertEquals(1, lieDown.closes, "结束时当前动作要收尾");
    }

    @Test
    void actionFailureCarriesProblemAndKeepsChanges() {
        ScriptedAction approach = new ScriptedAction("走向床边", ActionStatus.done());
        Problem refused = Problem.of(Problem.Kind.REFUSED_BY_GAME, "游戏提示：附近有怪物，不能休息");
        ScriptedAction lieDown = new ScriptedAction("右键床头", ActionStatus.failed(refused));
        var task = new GoToBed(Map.of(Phase.APPROACH, approach, Phase.LIE_DOWN, lieDown), new ProgressTracker(20, 1000));

        TaskResult result = resultOf(runUntilFinished(task, 10));

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(refused, result.problem());
        assertEquals(List.of("躺下"), result.remaining(), "没做完的部分要写进结果");
    }

    @Test
    void failureSummaryCarriesOnlyTheHeadline() {
        // 展开的原因只在问题里一份：一句话结论（也是结束事件的那一句）不再整段抄一遍。
        ScriptedAction approach = new ScriptedAction("走向床边", ActionStatus.done());
        Problem noBed = Problem.of(Problem.Kind.NEED_ITEM, "还要1个床，问过各来源都拿不到；记得的容器：没有；采掘：没有");
        ScriptedAction lieDown = new ScriptedAction("右键床头", ActionStatus.failed(noBed));
        var task = new GoToBed(Map.of(Phase.APPROACH, approach, Phase.LIE_DOWN, lieDown), new ProgressTracker(20, 1000));

        TaskResult result = resultOf(runUntilFinished(task, 10));

        assertEquals("测试没有完成：还要1个床，问过各来源都拿不到", result.summary());
        assertEquals(noBed, result.problem());
    }

    @Test
    void stuckWhenActionMakesNoRealProgress() {
        ScriptedAction approach = new ScriptedAction("走向床边", ActionStatus.running());
        var task = new GoToBed(Map.of(Phase.APPROACH, approach), new ProgressTracker(5, 1000));

        TaskResult result = resultOf(runUntilFinished(task, 20));

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.STUCK, result.problem().kind());
    }

    @Test
    void bouncingBetweenPhasesIsNotProgress() {
        // 在两个阶段之间来回跳、却没有任何动作报告进展：这是原地打转，必须判为卡住。
        var task = new PhasedTask<Phase>("来回跳", Phase.APPROACH, new ProgressTracker(5, 1000)) {
            @Override protected Action enter(Phase phase) {
                return null;
            }

            @Override protected Next<Phase> tick(Phase phase, TickContext context) {
                return Next.go(phase == Phase.APPROACH ? Phase.LIE_DOWN : Phase.APPROACH, "换个阶段试试");
            }
        };

        TaskResult result = resultOf(runUntilFinished(task, 20));

        assertEquals(Problem.Kind.STUCK, result.problem().kind());
    }

    @Test
    void progressShowsRecentPhasesAndHowLongWithoutRealProgress() {
        // 在两个阶段之间来回跳：面板要看得出最近走过哪几个阶段，也要看得出多久没有真实进展、多久算卡住。
        var task = new PhasedTask<Phase>("来回跳", Phase.APPROACH, new ProgressTracker(100, 1000)) {
            @Override protected Action enter(Phase phase) {
                return null;
            }

            @Override protected Next<Phase> tick(Phase phase, TickContext context) {
                return Next.go(phase == Phase.APPROACH ? Phase.LIE_DOWN : Phase.APPROACH, "换个阶段试试");
            }
        };
        for (int tick = 0; tick < 7; tick++) {
            task.tick(new TestTick(tick));
        }

        TaskProgress progress = task.currentProgress().orElseThrow();

        assertEquals(5, progress.recentPhases().size(), "只回看最近五次换阶段");
        assertEquals(new TaskProgress.PhaseChange("APPROACH", "LIE_DOWN", "换个阶段试试"), progress.recentPhases().getLast());
        assertEquals("LIE_DOWN", progress.phase());
        assertEquals("开始", progress.lastProgress());
        assertEquals(7, progress.ticksSinceProgress(), "换阶段本身不算进展");
        assertEquals(100, progress.stuckAfterTicks());
        assertEquals(1000, progress.maxTicks());
    }

    @Test
    void finishedTaskHasNoProgressToShow() {
        ScriptedAction approach = new ScriptedAction("走向床边", ActionStatus.done());
        ScriptedAction lieDown = new ScriptedAction("右键床头", ActionStatus.done());
        var task = new GoToBed(Map.of(Phase.APPROACH, approach, Phase.LIE_DOWN, lieDown), new ProgressTracker(20, 1000));

        runUntilFinished(task, 10);

        assertTrue(task.currentProgress().isEmpty(), "结束了就没有进展可说");
    }

    @Test
    void pauseReachesActionAndCloseReportsWhatHappened() {
        ScriptedAction approach = new ScriptedAction("走向床边", ActionStatus.progressed());
        var task = new GoToBed(Map.of(Phase.APPROACH, approach), new ProgressTracker(20, 1000));
        task.tick(new TestTick(0));
        // 走到一半已经挪动了位置：被替换时这条变化也要出现在结果里。
        task.recordChange(Change.of(Change.Kind.MOVED, "minecraft:player", 1));

        task.pause();
        TaskResult result = task.close(CloseReason.REPLACED);

        assertEquals(1, approach.pauses, "被打断时要暂停动作");
        assertEquals(1, approach.closes, "被替换时要收尾动作");
        assertEquals(TaskResult.Status.CANCELLED, result.status());
        assertTrue(result.summary().contains("替换"));
        assertEquals(1, result.changes().size(), "被替换也要如实交代已经发生的变化");
        assertEquals(List.of("躺下"), result.remaining());
    }

    @Test
    void asksTheRunningActionWithThisMomentsScene() {
        // 包着子任务的动作按这一刻的现场说能不能停（子任务手上那一下在等游戏确认）：分阶段任务照它说的报，子类不用改写。
        Action waiting = new Action() {
            @Override public ActionStatus tick(TickContext context) {
                return ActionStatus.running();
            }

            @Override public Interruptibility interruptibility(TickContext context) {
                return Interruptibility.UNSAFE_TO_STOP;
            }

            @Override public String describe() {
                return "等游戏确认放方块";
            }
        };
        PhasedTask<Phase> task = new PhasedTask<>("测试", Phase.APPROACH, new ProgressTracker(20, 1000)) {
            @Override protected Action enter(Phase phase) {
                return waiting;
            }

            @Override protected Next<Phase> tick(Phase phase, TickContext context) {
                return Next.stay();
            }
        };
        task.start(new TestTick(0));

        assertEquals(Interruptibility.UNSAFE_TO_STOP, task.interruptibility(new TestTick(1)),
                "手上的动作看现场说停不得，任务就停不得");
    }

    @Test
    void reportsBetweenActionsWhenNoActionIsRunning() {
        var task = new GoToBed(Map.of(), new ProgressTracker(20, 1000));
        task.start(new TestTick(0));

        assertEquals(Interruptibility.BETWEEN_ACTIONS, task.interruptibility(new TestTick(0)),
                "没有进行中的动作时处在两个动作之间，不急的事可以插进来");
        assertTrue(task.describe().startsWith("测试：APPROACH"));
    }
}
