// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.ability;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 按能力把一个目标估一遍：单件事问那项能力的估算口，sequence 逐步估（步骤本身是 sequence 的再套一层）。
 * 每一步都按此刻身上和周围的样子单独估，不推演前面几步做完后背包里会多出什么——推演要模拟一遍背包，容易估错。
 * 只问价不动手，在客户端线程上调。
 */
public final class GoalEstimates {

    /**
     * sequence 里一步的估算。
     *
     * @param index    第几步（从 0 数）
     * @param goal     这一步的目标
     * @param estimate 这一步的估算；这一步本身是 sequence 时为 null，看 steps
     * @param steps    这一步本身是 sequence 时它的各步；否则为空
     */
    public record StepEstimate(int index, Goal goal, GoalEstimate estimate, List<StepEstimate> steps) {
        public StepEstimate {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }

        /** 这一步（连同套在里面的各步）有没有因为附近还没看完而估不了的。 */
        public boolean stillLooking() {
            return estimate != null ? estimate.stillLooking() : steps.stream().anyMatch(StepEstimate::stillLooking);
        }
    }

    private final AbilityRegistry registry;

    public GoalEstimates(AbilityRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** 单件事：问那项能力的估算口，没实现的说"这项能力还不估"；估算口出了错也只说估不了，不影响下单。 */
    public GoalEstimate single(Goal goal, TickContext tick) {
        String what = describe(goal);
        if (!(registry.find(goal.ability()).orElse(null) instanceof EstimatesGoal estimates)) {
            return GoalEstimate.notEstimated(what);
        }
        try {
            return estimates.estimate(goal, tick);
        } catch (RuntimeException failed) {
            return GoalEstimate.unknown(what, "估算时出错：" + failed.getMessage());
        }
    }

    /** sequence 从第 from 步（从 0 数）起逐步估到最后一步。 */
    public List<StepEstimate> steps(Goal sequence, int from, TickContext tick) {
        List<StepEstimate> steps = new ArrayList<>();
        for (int index = Math.max(0, from); index < sequence.steps().size(); index++) {
            Goal step = sequence.steps().get(index);
            steps.add(step.steps().isEmpty()
                    ? new StepEstimate(index, step, single(step, tick), List.of())
                    : new StepEstimate(index, step, null, steps(step, 0, tick)));
        }
        return steps;
    }

    /**
     * sequence 第 finished 步（从 0 数）刚做完：后面还没开始的步骤里，此刻不用走路就能做的那几步。
     * 只看这件 sequence 自己写的步骤；套在里面的 sequence 整体没有一个档位，不列。
     */
    public List<StepEstimate> readyAfter(Goal sequence, int finished, TickContext tick) {
        return steps(sequence, finished + 1, tick).stream()
                .filter(step -> step.estimate() != null && step.estimate().effort() == GoalEstimate.Effort.READY)
                .toList();
    }

    /** 估的是什么：能力，写了目的就带上目的。 */
    private static String describe(Goal goal) {
        return goal.purpose() == null || goal.purpose().isBlank()
                ? goal.ability() : goal.ability() + "（" + goal.purpose() + "）";
    }
}
