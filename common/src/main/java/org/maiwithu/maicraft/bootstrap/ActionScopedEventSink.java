// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.maiwithu.maicraft.kernel.event.EventFacts;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventLog;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.goal.GoalRun;
import org.maiwithu.maicraft.kernel.goal.GoalRunState;
import org.maiwithu.maicraft.kernel.goal.GoalRunStore;
import org.maiwithu.maicraft.kernel.goal.GoalRunTable;
import org.maiwithu.maicraft.kernel.result.TaskResult;

/**
 * 把任务事件流接上目标运行上下文：动了别人放的东西、开出稀罕战利品这类由角色动作引起的事件，
 * 手上有正在推进的目标且没被生存需求临时任务借走身体时，自动挂在那个目标与当前步骤能力上；
 * 目标收段或暂停存盘前先冲刷连拆账，保证动别人方块的事件排在步骤或目标结束事件之前。
 */
final class ActionScopedEventSink implements TaskEventSink {

    private final TaskEventLog log;
    private final Supplier<GoalRunTable> goals;

    private ActionScopedEventSink(TaskEventLog log, Supplier<GoalRunTable> goals) {
        this.log = Objects.requireNonNull(log, "log");
        this.goals = Objects.requireNonNull(goals, "goals");
    }

    static TaskEventSink of(TaskEventLog log, Supplier<GoalRunTable> goals) {
        return new ActionScopedEventSink(log, goals);
    }

    /** 目标离开进行中（一步做完、整件结束、暂停或提问）存盘前，先把连拆同一个人的那一段事件冲刷出去。 */
    static GoalRunStore flushing(WorldScope scope, GoalRunStore delegate) {
        return new GoalRunStore() {
            @Override public long nextId() { return delegate.nextId(); }
            @Override public void save(GoalRun run) {
                if (run.state() != GoalRunState.RUNNING) {
                    scope.othersNotice().flushNow();
                }
                delegate.save(run);
            }
            @Override public List<GoalRun> unfinished() { return delegate.unfinished(); }
            @Override public TaskResult settling(GoalRun run, TaskResult result) {
                scope.othersNotice().flushNow();
                return delegate.settling(run, result);
            }
        };
    }

    @Override
    public void publish(TaskEvent.Kind kind, String message) {
        log.publish(kind, message);
    }

    @Override
    public void publish(TaskEvent.Kind kind, String message, EventFacts facts) {
        log.publish(kind, message, facts);
    }

    @Override
    public void publishInAction(TaskEvent.Kind kind, String message, EventFacts facts) {
        emit(currentGoal(), kind, message, facts);
    }

    @Override
    public TaskEventSink captureAction() {
        Optional<GoalRunTable.ActiveGoal> captured = currentGoal();
        return new TaskEventSink() {
            @Override public void publish(TaskEvent.Kind kind, String message) {
                log.publish(kind, message);
            }
            @Override public void publish(TaskEvent.Kind kind, String message, EventFacts facts) {
                log.publish(kind, message, facts);
            }
            @Override public void publishInAction(TaskEvent.Kind kind, String message, EventFacts facts) {
                emit(captured, kind, message, facts);
            }
        };
    }

    private Optional<GoalRunTable.ActiveGoal> currentGoal() {
        GoalRunTable table = goals.get();
        return table == null ? Optional.empty() : table.activeGoal();
    }

    private void emit(Optional<GoalRunTable.ActiveGoal> active, TaskEvent.Kind kind, String message, EventFacts facts) {
        if (active.isPresent()) {
            log.appendForGoal(kind, active.get().goalRunId(), active.get().ability(), message, facts);
        } else {
            log.publish(kind, message, facts);
        }
    }
}
