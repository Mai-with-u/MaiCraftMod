// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.ability;

import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 估算口：能力模块可以选着实现，回答"这个目标此刻要花多大功夫"。没实现的能力估出来是"这项能力还不估"。
 *
 * <p>只问价不动手：不启动任何动作，不记事实，不改世界记忆。在客户端线程上调。
 * 附近还没扫完时不在这里等，如实回"还没看完"，要不要再等由调用方定。
 */
public interface EstimatesGoal {

    /** 按这个目标的写法（参数、许可）估一份；目标本身写错了也照实估成弄不到或估不了，不抛异常。 */
    GoalEstimate estimate(Goal goal, TickContext tick);
}
