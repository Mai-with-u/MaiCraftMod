// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 取货一趟的外壳按钩子推进：没开界面就开，开了建一轮取货；总期限到点按各家的收场收，
 * 被打断时停打开那侧、关掉开着的界面，被收尾时先撒手再关；结算的结论由各家的钩子给出。
 */
class MenuTakeRunTest {

    /** 替身界面：读数永远为空（一轮一推就照界面没了收场），记录被撒手与被关上的次数。 */
    private static final class FakeMenu implements OpenedMenu {
        int abandons;
        int closeTicks;

        @Override public Optional<MenuContent.Reading> reading() { return Optional.empty(); }
        @Override public boolean busy() { return false; }
        @Override public boolean cursorEmpty() { return true; }
        @Override public SlotSnapshot cursor() { return SlotSnapshot.empty(); }
        @Override public boolean quickMove(int slotId) { return false; }
        @Override public boolean click(int slotId, int button) { return false; }
        @Override public void noteCursorTakenFrom(int slotId) {}

        @Override public Action closing() {
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    closeTicks++;
                    return ActionStatus.done();
                }

                @Override public String describe() { return "关上替身界面"; }
            };
        }

        @Override public void abandon() { abandons++; }
    }

    /** 只有刻号的推进现场。 */
    private static final class Ticks implements TickContext {
        @Override public long gameTick() { return 1000; }

        @Override public PlayerContext player() {
            throw new IllegalStateException("替身外壳不应碰到角色对象");
        }
    }

    /**
     * 钩子都可调的替身外壳：打开一段按脚本走，各钩子记下被调的次数；
     * 结论由构造时给的结算来源算（自算账的按一轮收场记的数，会话确认的按记下的确认件数）。
     */
    private static final class FakeRun extends MenuTakeRun {
        final FakeMenu menu = new FakeMenu();
        /** 打开脚本：每刻弹一条走向；空了回还在做。 */
        final List<Opening> script = new ArrayList<>();
        long limit = Long.MAX_VALUE;
        boolean counting = true;
        /** 各钩子被调的次数，测试据此断言外壳在什么时机调了谁。 */
        int menuOpenedCalls;
        int timeouts;
        int pauseOpeningCalls;
        int closeOpeningCalls;
        int roundClosedCalls;
        boolean extraUnsafe;
        Interruptibility idle = Interruptibility.WORKING;
        /** 会话确认的结算来源：收场时记下的确认件数；自算账的来源不用它。 */
        int confirmedTotal;
        /** 自算账的结算来源：一轮收场时算出来的数；-1 是还没算。 */
        int selfSettledTotal = -1;
        /** 这一趟最终怎么交回：由各家的收场钩子写。 */
        ActionStatus outcome;
        /** 结算按哪种来源：真是按会话逐笔确认过的件数，假是收场自己算账。 */
        boolean settleBySession;
        /** 被打断回来的这一刻要办的交接；非 null 时外壳把它原样交回。 */
        ActionStatus resumeStatus;
        int resumeCalls;

        @Override protected Opening openSegment(TickContext tick) {
            return script.isEmpty() ? new MenuTakeRun.Opening.Proceed(ActionStatus.running())
                    : script.remove(0);
        }

        @Override protected MenuTakeRound newRound(OpenedMenu opened) {
            return new MenuTakeRound(opened, List.of(), new MenuTakeRound.Endings() {
                @Override public void rememberBeforeClose(Optional<MenuContent.Reading> lastReading) {
                }

                // 两种结算来源各是一家的差异点：按会话确认的拿确认件数定成败，自算账的按自己记的账照常收场。
                @Override public ActionStatus finishAfterMenuGone(boolean tookAnything) {
                    selfSettledTotal = 2;
                    outcome = settleBySession && confirmedTotal <= 0
                            ? ActionStatus.failed(Problem.of(Problem.Kind.NOT_FOUND, "一件没取到", null))
                            : ActionStatus.done();
                    return outcome;
                }

                @Override public ActionStatus finishAfterClosed(boolean tookAnything) {
                    return finishAfterMenuGone(tookAnything);
                }
            });
        }

        @Override protected ActionStatus resumeFromPause() {
            resumeCalls++;
            return resumeStatus;
        }

        @Override protected Optional<MenuContent.Reading> currentReading() {
            return menu.reading();
        }

        @Override protected long limitTicks() { return limit; }

        @Override protected boolean limitCounting() { return counting; }

        @Override protected ActionStatus onTimeout(TickContext tick) {
            timeouts++;
            return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "取货超时", null));
        }

        @Override protected void menuOpened() { menuOpenedCalls++; }

        @Override protected void pauseOpening() { pauseOpeningCalls++; }

        @Override protected void closeOpening() { closeOpeningCalls++; }

        @Override protected void roundClosed() { roundClosedCalls++; }

        @Override protected boolean extraUnsafeToStop() { return extraUnsafe; }

        @Override protected Interruptibility whenIdle() { return idle; }

        @Override public String describe() { return "从替身界面取货"; }
    }

    private static void advance(Action take, int ticks) {
        Ticks tick = new Ticks();
        for (int i = 0; i < ticks; i++) take.tick(tick);
    }

    @Test
    void 没开界面就开_界面认下来建一轮_下一刻起推一轮() {
        FakeMenu opened = new FakeMenu();
        FakeRun run = new FakeRun();
        run.script.add(new MenuTakeRun.Opening.Proceed(ActionStatus.progressed()));
        run.script.add(new MenuTakeRun.Opening.MenuReady(opened));
        advance(run, 2);
        assertTrue(run.menuOpenedCalls >= 1, "界面认下来后外壳叫了各家的准备钩子");

        // 一轮一推就照界面没了收场：结论由各家的收场钩子给出，外壳原样交回。
        ActionStatus status = run.tick(new Ticks());
        assertInstanceOf(ActionStatus.Done.class, status, "结论由各家的收场钩子给出，外壳原样交回");
        assertEquals(2, run.selfSettledTotal, "自算账的来源算出了自己的账");
    }

    @Test
    void 总期限到点_按各家的收场收_不再接着开界面() {
        FakeRun run = new FakeRun();
        run.limit = 3;
        run.script.add(new MenuTakeRun.Opening.Proceed(ActionStatus.running()));
        ActionStatus status = null;
        for (int i = 0; i < 5 && status == null; i++) {
            ActionStatus current = run.tick(new Ticks());
            if (current instanceof ActionStatus.Failed) status = current;
        }
        assertInstanceOf(ActionStatus.Failed.class, status, "期限到点按各家的超时收场失败");
        assertEquals(1, run.timeouts, "超时收场只走一次");
    }

    @Test
    void 从点开界面才开始数期限的_没点开前不超时() {
        FakeRun run = new FakeRun();
        run.limit = 2;
        run.counting = false;
        advance(run, 10);
        assertEquals(0, run.timeouts, "还没开界面就不数期限，不会被走路的时间拖到超时");
    }

    @Test
    void 被打断_停打开那侧_开着的界面请游戏关上() {
        FakeRun run = new FakeRun();
        run.script.add(new MenuTakeRun.Opening.MenuReady(run.menu));
        advance(run, 1);
        run.pause();
        assertEquals(1, run.pauseOpeningCalls, "打开那侧被叫停");
        assertEquals(1, run.menu.abandons, "开着的界面被请游戏关上一次");
    }

    @Test
    void 界面还没点开就被打断_收手不炸() {
        FakeRun run = new FakeRun();
        run.pause();
        run.close();
        assertEquals(0, run.menu.abandons, "没有开着的界面就不用关");
    }

    @Test
    void 被收尾_一轮先撒手_打开那侧一并收尾_关上后再交记账() {
        FakeRun run = new FakeRun();
        run.script.add(new MenuTakeRun.Opening.MenuReady(run.menu));
        advance(run, 1);
        run.close();
        assertEquals(1, run.closeOpeningCalls, "打开那侧被收尾");
        assertEquals(1, run.menu.abandons, "没收完的一轮被撒手，界面被请游戏关上");
        assertEquals(1, run.roundClosedCalls, "撒手之后各家的记账钩子被叫到");
    }

    @Test
    void 一轮已经收场再收尾_不再撒手一遍() {
        FakeRun run = new FakeRun();
        run.script.add(new MenuTakeRun.Opening.MenuReady(run.menu));
        advance(run, 2);
        run.close();
        assertEquals(0, run.menu.abandons, "已经收完的一轮收尾时不再撒手、不再去点关闭");
    }

    @Test
    void 结算按会话确认的_一件没确认就按失败交回() {
        FakeMenu opened = new FakeMenu();
        FakeRun run = new FakeRun();
        run.settleBySession = true;
        run.confirmedTotal = 0;
        run.script.add(new MenuTakeRun.Opening.MenuReady(opened));
        advance(run, 2);
        assertInstanceOf(ActionStatus.Failed.class, run.outcome, "确认件数是零，按会话确认的来源如实失败");
    }

    @Test
    void 结算按会话确认的_确认到货就按完成交回() {
        FakeMenu opened = new FakeMenu();
        FakeRun run = new FakeRun();
        run.settleBySession = true;
        run.confirmedTotal = 3;
        run.script.add(new MenuTakeRun.Opening.MenuReady(opened));
        advance(run, 2);
        assertInstanceOf(ActionStatus.Done.class, run.outcome, "确认到货，按会话确认的来源交完成");
    }

    @Test
    void 结算自算账的_界面没了也按自己记的账照常收场() {
        FakeMenu opened = new FakeMenu();
        FakeRun run = new FakeRun();
        run.confirmedTotal = 0;
        run.script.add(new MenuTakeRun.Opening.MenuReady(opened));
        advance(run, 2);
        assertInstanceOf(ActionStatus.Done.class, run.outcome, "自算账的来源不看确认件数，按自己的账收场");
        assertEquals(2, run.selfSettledTotal);
    }

    @Test
    void 被打断回来的这一刻先办交接_不数期限也不开界面() {
        FakeRun run = new FakeRun();
        run.resumeStatus = ActionStatus.progressed();
        run.limit = 0;
        assertEquals(ActionStatus.progressed(), run.tick(new Ticks()), "交接的状态原样交回");
        assertEquals(0, run.timeouts, "办交接的一刻不数期限");
        assertTrue(run.script.isEmpty(), "办交接的一刻不走打开那段");
    }

    @Test
    void 停不停_手上停不得的活优先_空当按各家交() {
        FakeRun run = new FakeRun();
        run.extraUnsafe = true;
        assertEquals(Interruptibility.UNSAFE_TO_STOP, run.interruptibility(), "光标收放还在等确认时停不得");

        run.extraUnsafe = false;
        run.idle = Interruptibility.BETWEEN_ACTIONS;
        assertEquals(Interruptibility.BETWEEN_ACTIONS, run.interruptibility(), "空当按各家交，等成品的空当能插事");
    }
}
