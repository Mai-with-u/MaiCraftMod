// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.event;

import java.util.List;

import org.maiwithu.maicraft.kernel.goal.Goal;

/**
 * sequence 的一步做完时，后面还没开始的步骤里此刻不用走路就能做的那几步（砍完木头，木剑的料就够了）。
 * 由启动处接上各能力的估算口；只是告诉 LLM，先后不替它调。
 */
@FunctionalInterface
public interface ReadsReadySteps {

    /** 不看：离线测试与还没接上现场时用。 */
    ReadsReadySteps NONE = (sequence, finished) -> List.of();

    /**
     * @param sequence 整件 sequence 的目标
     * @param finished 刚做完的是第几步（从 0 数）
     */
    List<TaskEvent.ReadyStep> readyAfter(Goal sequence, int finished);
}
