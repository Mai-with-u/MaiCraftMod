// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.fall;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.survival.SurvivalFakes;
import org.maiwithu.maicraft.behavior.survival.SurvivalSituation;
import org.maiwithu.maicraft.behavior.landing.CatchesFall;
import org.maiwithu.maicraft.behavior.landing.spi.LandingMaterials;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.TestPlayer;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.TestTick;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.scripted;

/**
 * 落地防护：身上没东西、附近也没现成的水就如实说接不住；落点是水不用做事；下面是虚空救不了；
 * 已经没在掉了就安静收场。没带料时先从随身无线终端取一件，取到了再交给落地救援。
 * 真正接住这一掉（拿东西、对准、放、收回）由落地救援负责。
 */
class FallGuardTaskTest {

    private final TestPlayer player = new TestPlayer();
    private final TestTick tick = new TestTick(player);

    private FallGuardTask task(SurvivalSituation... script) {
        FallGuardTask task = new FallGuardTask(scripted(script), context -> null);
        task.start(tick);
        return task;
    }

    private TaskResult finish(FallGuardTask task) {
        return ((TickResult.Finished) task.tick(tick)).result();
    }

    @Test
    void failsHonestlyWhenNothingCanCatchTheFall() {
        TaskResult result = finish(task(SurvivalFakes.calm().feet(80).fallingHard().build()));

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.DANGER, result.problem().kind());
        assertTrue(result.problem().message().contains("水桶"), "问题要写明缺的是哪类东西");
    }

    @Test
    void landingInWaterNeedsNothing() {
        assertEquals(TaskResult.Status.DONE, finish(task(SurvivalFakes.calm().fallingIntoWater().build())).status());
    }

    @Test
    void voidCannotBeCushioned() {
        assertEquals(Problem.Kind.DANGER, finish(task(SurvivalFakes.calm().fallingIntoVoid().build())).problem().kind());
    }

    @Test
    void noLongerFallingEndsQuietly() {
        assertEquals(TaskResult.Status.DONE, finish(task(SurvivalFakes.calm().build())).status());
    }

    @Test
    void takesWaterFromTheWirelessTerminalBeforeCatching() {
        FakeRescue rescue = new FakeRescue();
        // 第一次找落点还没东西接；从终端取到水以后第二次才找得到。
        int[] lookups = {0};
        FallGuardTask task = taskWithTerminal(supplyAction(false),
                context -> lookups[0]++ == 0 ? null : rescue);

        assertTrue(task.tick(tick) instanceof TickResult.Running, "先去找落点，再去终端取水");
        player.nextTick();
        assertTrue(task.tick(tick) instanceof TickResult.Running, "还在从终端取");
        player.nextTick();
        assertTrue(task.tick(tick) instanceof TickResult.Running, "取到了，转去接住这一掉");
        player.nextTick();
        TickResult last = task.tick(tick);

        assertTrue(last instanceof TickResult.Finished);
        assertEquals(TaskResult.Status.DONE, ((TickResult.Finished) last).result().status());
        assertTrue(rescue.done, "取到水以后要交给落地救援真的接住");
    }

    @Test
    void reportsWhenTheWirelessTerminalCannotProvideWater() {
        FallGuardTask task = taskWithTerminal(supplyAction(true), context -> null);

        assertTrue(task.tick(tick) instanceof TickResult.Running);
        player.nextTick();
        TickResult last = task.tick(tick);

        assertTrue(last instanceof TickResult.Finished);
        TaskResult result = ((TickResult.Finished) last).result();
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertTrue(result.problem().message().contains("无线终端现取也没成"), "要说清是从终端取也没成");
        assertTrue(result.problem().message().contains("网络里没有水桶"), "原因原样带出来");
    }

    @Test
    void withoutAWirelessTerminalItStillSaysThereIsNothingToCatchWith() {
        FallGuardTask task = taskWithTerminal(null, context -> null);

        TaskResult result = finish(task);

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertTrue(result.problem().message().contains("水桶"), "问题要写明缺的是哪类东西");
    }

    private FallGuardTask taskWithTerminal(Action supply, Function<PlayerContext, CatchesFall> rescues) {
        LandingMaterials materials = new LandingMaterials() {
            @Override public Optional<Action> fetch(PlayerContext context, List<String> itemIds) {
                return Optional.ofNullable(supply);
            }
            @Override public String describe() {
                return "测试无线终端";
            }
        };
        FallGuardTask task = new FallGuardTask(
                scripted(SurvivalFakes.calm().feet(80).fallingHard().build()), rescues, () -> List.of(materials));
        task.start(tick);
        return task;
    }

    // 取物动作：成功时两刻后取到，失败时第一刻就说网络里没有。
    private static Action supplyAction(boolean fails) {
        return new Action() {
            private int ticks;

            @Override public ActionStatus tick(TickContext context) {
                if (fails) return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "网络里没有水桶"));
                return ++ticks >= 2 ? ActionStatus.done() : ActionStatus.running();
            }

            @Override public String describe() {
                return "测试从终端取水";
            }
        };
    }

    // 落地救援替身：一刻就算接住，不碰真实世界。
    private static final class FakeRescue implements CatchesFall {
        boolean done;

        @Override public boolean prepare(PlayerContext context) { return true; }
        @Override public void tick(PlayerContext context) { done = true; }
        @Override public boolean complete() { return done; }
        @Override public boolean failed() { return false; }
        @Override public String detail() { return "测试接住"; }
        @Override public String itemId() { return "minecraft:water_bucket"; }
        @Override public BlockPos landingFeet() { return BlockPos.ZERO; }
        @Override public boolean touchedDown(PlayerContext context) { return done; }
        @Override public PlayerInput.Movement steering(PlayerContext context, float yaw) {
            return PlayerInput.Movement.STOPPED;
        }
        @Override public void stop(PlayerContext context, String reason) {}
        @Override public List<Change> drainChanges() { return List.of(); }
    }
}
