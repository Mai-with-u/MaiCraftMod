// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.interaction.HoldsSneak;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 走路先下车：坐着的时候先推下车，下成了才把这一刻交给走到；下不来照下车的结论失败，没坐着与普通走到一样。 */
class DismountsBeforeWalksTest {

    /** 记下被推进了几刻、给什么结论的走到替身。 */
    private static final class FakeWalkRun implements WalkRun {
        int ticks;
        boolean paused;
        boolean closed;

        @Override public ActionStatus tick(TickContext tick) { ticks++; return ActionStatus.running(); }
        @Override public void pause() { paused = true; }
        @Override public void close() { closed = true; }
        @Override public String describe() { return "走到替身"; }
        @Override public WalkReport report() { throw new UnsupportedOperationException("替身不报走到情况"); }
        @Override public boolean resume() { return false; }
        @Override public boolean stop() { return false; }
    }

    /** 结论照剧本给的下车动作替身；记下推了几刻。 */
    private static final class ScriptedDismount implements Action {
        final List<ActionStatus> script = new ArrayList<>();
        int ticks;

        @Override public ActionStatus tick(TickContext tick) { return script.get(ticks++); }
        @Override public void pause() { }
        @Override public void close() { }
        @Override public String describe() { return "下车替身"; }
    }

    private static TickContext tick() {
        return new TickContext() {
            @Override public long gameTick() { return 1; }
            @Override public PlayerContext player() { throw new UnsupportedOperationException("替身不读角色"); }
        };
    }

    private static HoldsSneak sneaks() {
        return inner -> inner;
    }

    @Test
    void walksStraightThroughWhenNotRiding() {
        FakeWalkRun inner = new FakeWalkRun();
        DismountsBeforeWalks walks = new DismountsBeforeWalks(
                fakeWalks(inner), riding(false), dismounts(new ScriptedDismount()), sneaks());
        WalkRun run = walks.start((GoalCompiler.Compiled) null, TerrainPermit.WALK_ONLY);
        assertEquals(ActionStatus.running(), run.tick(tick()));
        assertEquals(1, inner.ticks);
    }

    @Test
    void holdsDismountWhileRidingThenHandsOverTheSameTick() {
        FakeWalkRun inner = new FakeWalkRun();
        ScriptedDismount dismount = new ScriptedDismount();
        dismount.script.add(ActionStatus.running());
        dismount.script.add(ActionStatus.done());
        DismountsBeforeWalks walks = new DismountsBeforeWalks(
                fakeWalks(inner), riding(true), dismounts(dismount), sneaks());
        WalkRun run = walks.start((GoalCompiler.Compiled) null, TerrainPermit.WALK_ONLY);
        assertEquals(ActionStatus.running(), run.tick(tick()));
        assertEquals(0, inner.ticks);
        assertEquals("先从载具上下来再走", run.describe());
        // 下成了：同一刻就把走路交回去，从落点（水里或岸上）接着走。
        assertEquals(ActionStatus.running(), run.tick(tick()));
        assertEquals(1, inner.ticks);
    }

    @Test
    void giveUpRideCarriesTheDismountConclusion() {
        FakeWalkRun inner = new FakeWalkRun();
        ScriptedDismount dismount = new ScriptedDismount();
        dismount.script.add(ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "按住潜行两秒还没从载具上下来")));
        DismountsBeforeWalks walks = new DismountsBeforeWalks(
                fakeWalks(inner), riding(true), dismounts(dismount), sneaks());
        WalkRun run = walks.start((GoalCompiler.Compiled) null, TerrainPermit.WALK_ONLY);
        ActionStatus status = run.tick(tick());
        assertTrue(status instanceof ActionStatus.Failed);
        assertEquals(0, inner.ticks);
    }

    @Test
    void pauseAndCloseReachBothParts() {
        FakeWalkRun inner = new FakeWalkRun();
        ScriptedDismount dismount = new ScriptedDismount();
        dismount.script.add(ActionStatus.running());
        dismount.script.add(ActionStatus.done());
        DismountsBeforeWalks walks = new DismountsBeforeWalks(
                fakeWalks(inner), riding(true), dismounts(dismount), sneaks());
        WalkRun run = walks.start((GoalCompiler.Compiled) null, TerrainPermit.WALK_ONLY);
        run.tick(tick());
        run.pause();
        assertTrue(inner.paused);
        run.close();
        assertTrue(inner.closed);
        // 收尾后再推进：下车已收掉、不在乘客位，只剩里面的运行。
        assertEquals(ActionStatus.running(), run.tick(tick()));
    }

    private static WalkTo fakeWalks(FakeWalkRun run) {
        return (target, permit) -> run;
    }

    private static Predicate<TickContext> riding(boolean on) {
        return tickContext -> on;
    }

    private static Function<HoldsSneak, Action> dismounts(ScriptedDismount action) {
        return holds -> action;
    }
}
