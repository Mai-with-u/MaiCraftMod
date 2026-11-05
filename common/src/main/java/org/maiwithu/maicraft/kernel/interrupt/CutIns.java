// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.interrupt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 插一下：LLM 下达的、要控制角色但几秒就做完的事（看着人蹲几下、点个头）。不替换主目标，
 * 压在手上的事上面做完就让回去，手上的事从原地接着做。
 *
 * <p>它挂在控制循环的需求表最后，和生存需求用同一套打断规则：有排着的就报"尽快"——两个动作之间、
 * 正常干活时当刻插，停下不安全时等着；同样急的临时任务（自卫、换气）在跑时排在它们后面，
 * 找空当的（夜里去睡的路上）照插。插进来以后只有立刻要处理的需求能打断它，由控制循环按"谁插的"认。
 *
 * <p>最多等 3 秒腾出身子：等不到就以"这会儿腾不出身子"收场，不为一个肢体动作叫开着车、悬在半空的
 * 手上的事先找安全处停。一次只插一个，几个按下达先后排队，最多排 4 个。它不是起居需求，
 * 主目标关掉起居需求时照插；也不发临时任务事件——它是 LLM 自己下达的目标，开始与结束有目标自己的事件。
 */
final class CutIns implements SurvivalNeed {

    /** 最多排几个：再多就是 LLM 一口气下得太多，前面的还没做完。 */
    static final int MAX_QUEUED = 4;
    /** 排到队头以后最多等多少刻腾出身子：3 秒。 */
    static final long WAIT_TICKS = 60;

    private final Deque<Waiting> queue = new ArrayDeque<>();

    /** 排进队尾；排满了不收，回 false。 */
    boolean offer(Task task, Consumer<TaskResult> giveUp) {
        if (queue.size() >= MAX_QUEUED) return false;
        queue.addLast(new Waiting(Objects.requireNonNull(task, "task"), Objects.requireNonNull(giveUp, "giveUp")));
        return true;
    }

    /** 还排着、没轮上的有几个。 */
    int queued() {
        return queue.size();
    }

    /** 角色不在了、死了：排着的全部以给定的说法收场，一个都不留到下一具身体。 */
    void dropAll(String why) {
        List<Waiting> dropped = new ArrayList<>(queue);
        queue.clear();
        for (Waiting waiting : dropped) {
            waiting.giveUp.accept(TaskResult.cancelled(why + "，这件插一下的事没做"));
        }
    }

    @Override public String name() {
        return "插一下";
    }

    @Override public Urgency urgency(TickContext context) {
        return urgency(context, null);
    }

    // 队头从第一次被问到起计时：前一个插一下还在做时不问，排着的不会被前一个耗掉等待时间。
    @Override public Urgency urgency(TickContext context, Task currentTask) {
        while (!queue.isEmpty()) {
            Waiting head = queue.peekFirst();
            if (head.since == Long.MIN_VALUE) head.since = context.gameTick();
            if (context.gameTick() - head.since <= WAIT_TICKS) return Urgency.SOON;
            queue.removeFirst();
            head.giveUp.accept(tooBusy(currentTask));
        }
        return null;
    }

    // 3 秒都腾不出身子：说清手上是什么事停不下来，LLM 换个时候再做。
    private static TaskResult tooBusy(Task currentTask) {
        String busy = currentTask == null ? "手上的事" : "手上的事（" + currentTask.describe() + "）";
        String message = busy + " 3 秒内腾不出身子（停下不安全，或者正在处理急事），这件插一下的事没做";
        return TaskResult.failed(message, Problem.of(Problem.Kind.WRONG_TIME, message,
                "等手上的事停稳、急事处理完再下"));
    }

    @Override public Task createTask(TickContext context) {
        Waiting head = queue.pollFirst();
        return head == null ? null : head.task;
    }

    // 不是起居需求：主目标开着 survival_needs=off 也照插。
    @Override public boolean routine() {
        return false;
    }

    // 是 LLM 下达的目标，开始与结束由目标自己的事件说。
    @Override public boolean announcesTempTask() {
        return false;
    }

    // 每一件都是新下达的事，没做成也不按住后面的。
    @Override public boolean waitsAfterFailure() {
        return false;
    }

    /** 排着的一件：任务、轮不上时怎么收场、从哪一刻起排在队头（还没问到为 Long.MIN_VALUE）。 */
    private static final class Waiting {
        final Task task;
        final Consumer<TaskResult> giveUp;
        long since = Long.MIN_VALUE;

        Waiting(Task task, Consumer<TaskResult> giveUp) {
            this.task = task;
            this.giveUp = giveUp;
        }
    }
}
