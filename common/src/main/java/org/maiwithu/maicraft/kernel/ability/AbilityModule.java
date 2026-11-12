// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.ability;

import org.maiwithu.maicraft.kernel.goal.AbilityHooks;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 能力模块：一个能力的全部知识都从这里进入；内核和 MCP 入口只通过它认识能力。
 *
 * <p>每个能力包只有这一个类对外公开（{@code public}），其余类都只在包内可见。
 * 启动时用一份明确的清单登记模块，新增能力时在清单里加一行。
 */
public interface AbilityModule {

    /** 这个能力的规格：ID、用途、能力说明、参数、目标对象种类、执行方式、需要的模组。 */
    AbilitySpec spec();

    /**
     * 这一次的目标按哪种执行方式跑。默认就是规格里声明的那一种；同一个能力按参数做的事轻重不同时
     * （公屏说一句话几秒做完，对一只生物说话要走过去、站着等回话），由能力按这次的目标答。
     * 下达时内核据此决定插一下还是换主目标，只认答出来的执行方式，不认能力、不看参数。
     * 必须便宜、没有副作用，只看目标本身。
     */
    default ExecutionMode modeFor(Goal goal) {
        return spec().mode();
    }

    /**
     * 看现场，决定当前这一步下一件做什么。
     * 必须便宜、没有副作用，每刻都可能被重复调用；信息不全时返回 {@link StepDecision#NOT_READY}。
     */
    StepDecision decide(StepContext step);

    /** 登记本能力的任务输入类型和对应的任务。 */
    default void registerTasks(TaskFactories factories) {}

    /**
     * 这个能力的目标能不能带按顺序的步骤（steps）。只有按顺序做几件事的能力回答是；
     * MCP 入口据此拒绝给其他能力带 steps，免得步骤被悄悄忽略。
     */
    default boolean acceptsSteps() {
        return false;
    }

    /**
     * 这个能力的目标被临时任务（自卫撤离、跑回去捡东西）带离原地后，能不能从新地点直接接着做：去某地、拿东西这类
     * 在哪做都一样的能；施工、挖指定的格这类守着一个地方的不能，回不去就停下等 LLM 定。默认不能。
     * 目标在两件事之间（看现场决定下一件做什么）时也按它答，不只看手上正在跑的那一步。
     */
    default boolean resumesAnywhere() {
        return false;
    }

    /** 本能力的特殊处理；绝大多数能力用不到。 */
    default AbilityHooks hooks() {
        return AbilityHooks.NONE;
    }

    /**
     * 插一下（{@link ExecutionMode#CUTS_IN}）遇到角色正忙腾不出手脚时，能不能不控制角色直接把这次目标办完。
     * 默认返回 null，照常等角色空出来或按时机不对收场。
     */
    default TaskResult finishWithoutControl(Goal goal, TickContext context) {
        return null;
    }

}
