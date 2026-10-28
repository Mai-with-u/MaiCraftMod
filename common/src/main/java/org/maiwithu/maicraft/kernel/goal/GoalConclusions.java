// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.CloseReason;

import java.util.ArrayList;
import java.util.List;

/**
 * 目标收场时的结论怎么写：sequence 按各步结果合成的结论、还没开始的步骤，
 * 以及目标被取消、被替换、角色没了时的结论句。只拼结论，不碰目标运行与任务。
 */
final class GoalConclusions {

    private GoalConclusions() {}

    /**
     * sequence 的结论：全部做成就是完成；一步都没做成就是失败；其余是部分完成。
     * 没做成的步骤和还没开始的步骤都写进剩下的部分，问题取第一个没做成的步骤的问题。
     * 各步骤已经发生的事实由收尾统一并进来。
     */
    static TaskResult sequenceResult(Goal goal, List<TaskResult> finishedResults, int nextStep) {
        int total = goal.steps().size();
        int done = 0;
        Problem problem = null;
        List<String> remaining = new ArrayList<>();
        for (int i = 0; i < finishedResults.size(); i++) {
            TaskResult stepResult = finishedResults.get(i);
            if (stepResult.status() == TaskResult.Status.DONE) {
                done++;
                continue;
            }
            if (problem == null) problem = stepResult.problem();
            remaining.add(stepLabel(goal, i) + "：" + stepResult.summary());
            remaining.addAll(stepResult.remaining());
        }
        remaining.addAll(stepsNotStarted(goal, nextStep));
        if (done == total) {
            return TaskResult.done("按顺序做完了全部 " + total + " 步");
        }
        TaskResult.Status status = done == 0 && problem != null ? TaskResult.Status.FAILED : TaskResult.Status.PARTIAL;
        return TaskResult.builder(status, done + " / " + total + " 步做成")
                .problem(problem)
                .remaining(remaining)
                .build();
    }

    /** sequence 里从 fromStep 起还没开始的步骤；普通目标没有。 */
    static List<String> stepsNotStarted(Goal goal, int fromStep) {
        List<String> notStarted = new ArrayList<>();
        for (int i = fromStep; i < goal.steps().size(); i++) {
            notStarted.add(stepLabel(goal, i) + "：没有开始");
        }
        return notStarted;
    }

    // 没做成、没开始的步骤按"第几步（能力，目的）"列出，和每一步做完时发的事件叫法一样。
    private static String stepLabel(Goal goal, int index) {
        return goal.steps().get(index).stepLabel(index);
    }

    // 目标没走到自己的结论就被收掉时，结论句按收掉的原因说：被取消、被新任务替换、角色没了，或主任务不要起居打断。
    static String cancelledSummary(CloseReason reason) {
        return switch (reason) {
            case FINISHED -> "目标没有交代结果就结束了";
            case REPLACED -> "目标被新任务替换";
            case CANCELLED -> "目标被取消";
            case ROUTINE_NEEDS_OFF -> "主任务不要起居打断，中止";
            case PLAYER_GONE -> "角色不在了，目标中止";
        };
    }
}
