// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.perception.looking.LooksAfterReading;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.event.EventPublishingGoalRunStore;
import org.maiwithu.maicraft.kernel.event.TaskEventLog;
import org.maiwithu.maicraft.kernel.goal.GoalRunTable;
import org.maiwithu.maicraft.kernel.goal.InMemoryGoalRunStore;
import org.maiwithu.maicraft.kernel.goal.PlayerControlHandover;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.interrupt.ControlLoop;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.kernel.task.TaskInput;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 旁路只读目标跨刻轮询的线程安全与收尾守护测试：验证超时/中断时都在客户端线程显式取消并移除孤儿任务，
 * 轮询期容忍瞬时客户端繁忙并在后续真实游戏刻正常完成。
 */
class ExecuteAsideTest {

    private record Tick(long gameTick) implements TickContext {
        @Override public PlayerContext player() { return null; }
    }

    private record WaitInput(String label) implements TaskInput {
        @Override public String describe() { return label; }
    }

    /** 可控结束时机的离线只读任务替身：记录推进次数与关闭原因。 */
    private static final class HangingReadTask implements Task {
        final AtomicInteger ticks = new AtomicInteger();
        final AtomicReference<CloseReason> closedWith = new AtomicReference<>();
        final AtomicReference<TaskResult> nextResult = new AtomicReference<>();

        @Override public TickResult tick(TickContext context) {
            ticks.incrementAndGet();
            TaskResult ready = nextResult.get();
            return ready != null ? TickResult.finished(ready) : TickResult.RUNNING;
        }

        @Override public void pause() {}

        @Override public TaskResult close(CloseReason reason) {
            closedWith.set(reason);
            return TaskResult.cancelled("只读等待被取消");
        }

        @Override public String describe() {
            return "等待跨刻只读回包";
        }
    }

    /** 只读能力替身：把一次执行映射为受控的 HangingReadTask。 */
    private static final class SlowReadAbility implements AbilityModule {
        private final AbilitySpec spec = new AbilitySpec(
                "maicraft:slow_read", "模拟跨刻等网回包的只读查询",
                AbilityDoc.forAbility("slow_read"), ParamSpecs.EMPTY, Set.of(),
                ExecutionMode.READ_ONLY, Set.of(), List.of(), Listing.LISTED);
        private final HangingReadTask task;

        SlowReadAbility(HangingReadTask task) {
            this.task = task;
        }

        @Override public AbilitySpec spec() { return spec; }

        @Override public void registerTasks(TaskFactories factories) {
            factories.register(WaitInput.class, input -> task);
        }

        @Override public StepDecision decide(StepContext step) {
            if (!step.taskResults().isEmpty()) return new StepDecision.Finish(step.taskResults().getLast());
            return new StepDecision.Run(new WaitInput("等网汇总"));
        }
    }

    private static GoalRunTable newTable(AbilityRegistry registry) {
        return new GoalRunTable(registry,
                new EventPublishingGoalRunStore(new InMemoryGoalRunStore(), new TaskEventLog()),
                (name, pos) -> {}, new ControlLoop(List.of()), new PlayerControlHandover() {
                    @Override public boolean automationOwnsControls() { return true; }
                    @Override public void requestControl() {}
                });
    }

    @Test
    void 旁路目标超时后在客户端线程取消并移出旁路表_不残留孤儿任务() {
        HangingReadTask task = new HangingReadTask();
        AbilityRegistry registry = new AbilityRegistry(new TaskFactories());
        registry.register(new SlowReadAbility(task));
        GoalRunTable table = newTable(registry);
        AtomicInteger tick = new AtomicInteger(1);
        ClientThread direct = new ClientThread() {
            @Override public <T> T call(Function<TickContext, T> work) {
                return work.apply(new Tick(tick.getAndIncrement()));
            }
        };
        ExecuteTool execute = new ExecuteTool(registry, table, direct, LooksAfterReading.NONE, 60, 10);

        JsonObject reply = execute.call(JsonParser.parseString("{\"goal\": {\"ability\": \"slow_read\"}}").getAsJsonObject());
        assertTrue(reply.get("ok").getAsBoolean(), reply::toString);
        JsonObject data = reply.getAsJsonObject("data");
        assertEquals("finished", data.get("state").getAsString(), "超时后必须返回终态，绝不返回 running 半成品");
        assertEquals("cancelled", data.getAsJsonObject("result").get("status").getAsString());
        assertEquals(CloseReason.CANCELLED, task.closedWith.get(), "超时的旁路任务必须在客户端线程显式取消关闭");

        // 再次由客户端主循环推进旁路表：已移出的任务绝不能继续空转。
        int ticksAfterCancel = task.ticks.get();
        table.tickAside(new Tick(tick.getAndIncrement()));
        table.tickAside(new Tick(tick.getAndIncrement()));
        assertEquals(ticksAfterCancel, task.ticks.get(), "取消收尾后不得在旁路表里残留孤儿任务");
    }

    @Test
    void 工作线程等待期间被中断_先在客户端线程取消旁路目标再恢复中断标记() throws Exception {
        HangingReadTask task = new HangingReadTask();
        AbilityRegistry registry = new AbilityRegistry(new TaskFactories());
        registry.register(new SlowReadAbility(task));
        GoalRunTable table = newTable(registry);
        CountDownLatch launched = new CountDownLatch(1);
        AtomicInteger tick = new AtomicInteger(1);
        Object clientLock = new Object();
        ClientThread synchronizedClient = new ClientThread() {
            @Override public <T> T call(Function<TickContext, T> work) {
                // 模拟真实客户端线程排他访问：若调用线程仍带着未清除的中断位也会被及时发现。
                assertFalse(Thread.currentThread().isInterrupted(), "向客户端线程提交取消前不应携带未清除的中断标记");
                synchronized (clientLock) {
                    T result = work.apply(new Tick(tick.getAndIncrement()));
                    launched.countDown();
                    return result;
                }
            }
        };
        ExecuteTool execute = new ExecuteTool(registry, table, synchronizedClient, LooksAfterReading.NONE, 5_000, 15);

        AtomicReference<JsonObject> replyRef = new AtomicReference<>();
        AtomicBoolean interruptedAfterReturn = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            replyRef.set(execute.call(JsonParser.parseString("{\"goal\": {\"ability\": \"slow_read\"}}").getAsJsonObject()));
            interruptedAfterReturn.set(Thread.currentThread().isInterrupted());
        });
        worker.start();
        assertTrue(launched.await(2, TimeUnit.SECONDS), "旁路目标应已起跑并进入等待");
        worker.interrupt();
        worker.join(2_000);
        assertFalse(worker.isAlive(), "工作线程收到中断后应迅速取消旁路目标并退出");
        assertTrue(interruptedAfterReturn.get(), "收尾完成后必须恢复当前线程的中断标记");

        JsonObject data = replyRef.get().getAsJsonObject("data");
        assertEquals("finished", data.get("state").getAsString());
        assertEquals("cancelled", data.getAsJsonObject("result").get("status").getAsString());
        assertEquals(CloseReason.CANCELLED, task.closedWith.get());

        int ticksAfterCancel = task.ticks.get();
        synchronized (clientLock) {
            table.tickAside(new Tick(tick.getAndIncrement()));
        }
        assertEquals(ticksAfterCancel, task.ticks.get(), "中断取消后不得残留孤儿任务");
    }

    @Test
    void 轮询期间偶发客户端繁忙不中断_随后跨真实游戏刻完成返回终态() {
        HangingReadTask task = new HangingReadTask();
        AbilityRegistry registry = new AbilityRegistry(new TaskFactories());
        registry.register(new SlowReadAbility(task));
        GoalRunTable table = newTable(registry);
        AtomicInteger callCount = new AtomicInteger();
        AtomicInteger tick = new AtomicInteger(1);
        ClientThread flakyClient = new ClientThread() {
            @Override public <T> T call(Function<TickContext, T> work) {
                int index = callCount.incrementAndGet();
                // 第 1 次为启动 runAside；第 2、3 次模拟客户端主线程瞬时繁忙；第 4 次模拟真实刻回包到达。
                if (index == 2 || index == 3) {
                    throw new Busy("客户端主线程这一刻正忙");
                }
                if (index == 4) {
                    task.nextResult.set(TaskResult.done("跨刻收到 ME 网汇总"));
                    table.tickAside(new Tick(tick.getAndIncrement()));
                }
                return work.apply(new Tick(tick.getAndIncrement()));
            }
        };
        ExecuteTool execute = new ExecuteTool(registry, table, flakyClient, LooksAfterReading.NONE, 1_000, 10);

        JsonObject reply = execute.call(JsonParser.parseString("{\"goal\": {\"ability\": \"slow_read\"}}").getAsJsonObject());
        assertTrue(reply.get("ok").getAsBoolean(), reply::toString);
        JsonObject data = reply.getAsJsonObject("data");
        assertEquals("finished", data.get("state").getAsString());
        JsonObject result = data.getAsJsonObject("result");
        assertEquals("done", result.get("status").getAsString());
        assertEquals("跨刻收到 ME 网汇总", result.get("summary").getAsString());
    }
}
