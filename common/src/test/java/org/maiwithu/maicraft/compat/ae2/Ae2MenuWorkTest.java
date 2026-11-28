// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 界面动作骨架的收场与等待计时：开不成如实失败、做成或做不下去都先关窗、同步等待上限为 40 刻。 */
class Ae2MenuWorkTest {

    /** 可编程的打开替身：要多少刻开好、开好后交出的界面可指定。 */
    private static final class ScriptedOpening implements MenuOpening {
        private final int ticksToOpen;
        private final OpenedMenu menu;
        private final List<String> events = new ArrayList<>();
        private int ticksLeft;
        boolean paused;

        ScriptedOpening(int ticksToOpen, OpenedMenu menu) {
            this.ticksToOpen = ticksToOpen;
            this.menu = menu;
            this.ticksLeft = ticksToOpen;
        }

        @Override public Optional<OpenedMenu> opened() { return Optional.ofNullable(menu); }

        @Override public ActionStatus tick(TickContext tick) {
            events.add("open");
            return --ticksLeft > 0 ? ActionStatus.running() : ActionStatus.done();
        }

        @Override public void pause() { paused = true; }
        @Override public void close() { events.add("opening-closed"); }
        @Override public String describe() { return "打开测试界面"; }
    }

    /** 记录关窗与撒手的界面替身。 */
    private static final class ScriptedMenu implements OpenedMenu {
        final List<String> events = new ArrayList<>();
        boolean abandoned;

        @Override public Optional<MenuContent.Reading> reading() { return Optional.empty(); }
        @Override public boolean busy() { return false; }
        @Override public boolean cursorEmpty() { return true; }
        @Override public SlotSnapshot cursor() { return SlotSnapshot.empty(); }
        @Override public boolean quickMove(int slotId) { return true; }
        @Override public boolean click(int slotId, int button) { return true; }
        @Override public void noteCursorTakenFrom(int slotId) {}
        @Override public Action closing() {
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    events.add("closing");
                    return ActionStatus.done();
                }
                @Override public String describe() { return "关闭测试界面"; }
            };
        }
        @Override public void abandon() { abandoned = true; }
    }

    /** 按脚本回答的骨架子类：界面里的每刻弹出脚本里的一个状态，弹完一直等着。 */
    private static class ScriptedWork extends Ae2MenuWork {
        private final List<ActionStatus> script;
        final List<Boolean> elapsedAnswers = new ArrayList<>();
        OpenedMenu seenMenu;
        boolean menuOpenedCalled;
        private int steps;

        ScriptedWork(MenuOpening opening, List<ActionStatus> script) {
            super(opening, "测试界面没打开");
            this.script = script;
        }

        @Override protected void menuOpened(OpenedMenu menu) {
            menuOpenedCalled = true;
            seenMenu = menu;
        }

        @Override protected ActionStatus work(TickContext tick) {
            elapsedAnswers.add(syncWaitElapsed());
            return steps < script.size() ? script.get(steps++) : ActionStatus.running();
        }

        @Override protected String doing() { return "测试界面里的活"; }
    }

    private static ActionStatus progressed() { return ActionStatus.progressed(); }

    private static boolean isProgressed(ActionStatus status) {
        return status instanceof ActionStatus.Running running && running.progressed();
    }

    private static final TickContext TICK = TickContext.of(1L, null);

    @Test
    void 打不开时如实失败并收回打开者() {
        ScriptedOpening opening = new ScriptedOpening(1, null);
        ScriptedWork work = new ScriptedWork(opening, List.of());
        ActionStatus status = work.tick(TICK);
        assertTrue(status instanceof ActionStatus.Failed failed
                && failed.problem().kind() == Problem.Kind.TARGET_GONE
                && failed.problem().message().equals("测试界面没打开"));
        work.close();
        assertTrue(opening.events.contains("opening-closed"));
    }

    @Test
    void 打开者没走完时跟着它的节奏走() {
        ScriptedOpening opening = new ScriptedOpening(3, new ScriptedMenu());
        ScriptedWork work = new ScriptedWork(opening, List.of());
        assertTrue(work.tick(TICK) instanceof ActionStatus.Running);
        assertTrue(work.tick(TICK) instanceof ActionStatus.Running);
        assertFalse(work.menuOpenedCalled);
    }

    @Test
    void 界面开好后先做开窗准备再进界面里的步骤() {
        ScriptedMenu menu = new ScriptedMenu();
        ScriptedWork work = new ScriptedWork(new ScriptedOpening(1, menu), List.of(progressed()));
        work.tick(TICK);
        assertTrue(work.menuOpenedCalled);
        assertEquals(menu, work.seenMenu);
    }

    @Test
    void 同步等待数满四十刻才算等超() {
        ScriptedWork work = new ScriptedWork(new ScriptedOpening(1, new ScriptedMenu()),
                List.of(progressed()));
        work.tick(TICK);
        // 脚本弹完后 work 一直等着，每刻计一次；第 40 次计数为假，第 41 次为真。
        for (int i = 0; i < Ae2MenuWork.SYNC_WAIT_TICKS + 1; i++) work.tick(TICK);
        assertEquals(41, work.elapsedAnswers.size());
        assertFalse(work.elapsedAnswers.get(39));
        assertTrue(work.elapsedAnswers.get(40));
    }

    @Test
    void 做成了先关窗再报完成() {
        ScriptedMenu menu = new ScriptedMenu();
        ScriptedWork work = new ScriptedWork(new ScriptedOpening(1, menu), List.of()) {
            private boolean finished;

            @Override protected ActionStatus work(TickContext tick) {
                super.work(tick);
                if (finished) return ActionStatus.running();
                finished = true;
                return closeWith(null);
            }
        };
        work.tick(TICK);
        assertTrue(isProgressed(work.tick(TICK)));
        ActionStatus closed = work.tick(TICK);
        assertTrue(menu.events.contains("closing"));
        assertTrue(closed instanceof ActionStatus.Done);
    }

    @Test
    void 做不下去也先关窗再按原因失败() {
        ScriptedMenu menu = new ScriptedMenu();
        Problem stuck = Problem.of(Problem.Kind.STUCK, "没等到");
        ScriptedWork work = new ScriptedWork(new ScriptedOpening(1, menu), List.of()) {
            @Override protected ActionStatus work(TickContext tick) {
                return closeWith(stuck);
            }
        };
        work.tick(TICK);
        assertTrue(isProgressed(work.tick(TICK)));
        ActionStatus status = work.tick(TICK);
        assertTrue(menu.events.contains("closing"));
        assertTrue(status instanceof ActionStatus.Failed failed && failed.problem() == stuck);
    }

    @Test
    void 中途撒手请游戏关一次界面() {
        ScriptedMenu menu = new ScriptedMenu();
        ScriptedWork work = new ScriptedWork(new ScriptedOpening(1, menu), List.of(progressed()));
        work.tick(TICK);
        work.close();
        assertTrue(menu.abandoned);
    }

    @Test
    void 界面还没开时撒手只收打开者() {
        ScriptedOpening opening = new ScriptedOpening(2, null);
        ScriptedWork work = new ScriptedWork(opening, List.of());
        work.tick(TICK);
        work.close();
        assertTrue(opening.events.contains("opening-closed"));
    }

    @Test
    void 界面还没开时暂停传导给打开者() {
        ScriptedOpening opening = new ScriptedOpening(2, null);
        ScriptedWork work = new ScriptedWork(opening, List.of());
        work.tick(TICK);
        work.pause();
        assertTrue(opening.paused);
    }

    @Test
    void 描述说界面里正在做的事() {
        ScriptedWork work = new ScriptedWork(new ScriptedOpening(1, new ScriptedMenu()), List.of());
        assertEquals("测试界面里的活", work.describe());
    }
}
