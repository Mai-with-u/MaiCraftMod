// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.interrupt;

import java.util.Objects;

import org.maiwithu.maicraft.kernel.child.ChildTaskRunner;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Urgency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 控制循环运行栈里的一层：持有任务本体、推进它的子任务运行器、来源生存需求（主任务为 null）与打断急迫度。
 */
final class LoopFrame {
    private static final Logger LOG = LoggerFactory.getLogger(LoopFrame.class);

    final Task task;
    final ChildTaskRunner runner = new ChildTaskRunner(ChildTaskRunner.NO_LIMIT);
    final SurvivalNeed from;
    final Urgency urgency;
    boolean started;
    /** 主任务被临时任务留在了半路：不能再推进，等 LLM 明确换任务或解除暂停。 */
    boolean paused;
    /** 为了让不急的需求插进来，已经叫它先到安全的地方再停。 */
    boolean stopAsked;
    /** 从哪一刻起一直停下不安全；此刻能停为 Long.MIN_VALUE。 */
    long unsafeSince = Long.MIN_VALUE;
    /** 主任务在运行期间是否声明过不要起居打断；结束那刻任务自身已转终态，靠它保留交接窗口。 */
    boolean hadRoutineNeedsOff;

    LoopFrame(Task task, SurvivalNeed from, Urgency urgency) {
        this.task = Objects.requireNonNull(task, "task");
        this.from = from;
        this.urgency = urgency;
        this.hadRoutineNeedsOff = from == null && task instanceof RoutineNeedsOff off && off.routineNeedsOff();
    }

    /** 被打断：推进过的经子任务运行器转发暂停，还没推进过的直接告诉任务。 */
    void pause() {
        if (started) {
            runner.pause();
        } else {
            task.pause();
        }
    }

    /** 先到安全的地方再停：只有推进过的任务才可能停下不安全，还没推进过的现在就能停。 */
    boolean stopSafely() {
        return started && runner.stopSafely();
    }

    /** 此刻能不能打断：推进过的问子任务运行器，还没推进过的直接问任务自己。 */
    Interruptibility interruptibility(TickContext context) {
        return started ? runner.interruptibility(context) : task.interruptibility(context);
    }

    /** 按原因收尾并拿结果：推进过的经子任务运行器收尾，还没推进过的直接让任务交代。 */
    TaskResult close(CloseReason reason) {
        if (started) {
            return runner.finished() ? runner.result() : runner.close(reason);
        }
        try {
            return task.close(reason);
        } catch (RuntimeException exception) {
            LOG.warn("还没开始的任务收尾时出错：{}", task.describe(), exception);
            return TaskResult.cancelled("任务还没开始就结束了：" + reason);
        }
    }
}
