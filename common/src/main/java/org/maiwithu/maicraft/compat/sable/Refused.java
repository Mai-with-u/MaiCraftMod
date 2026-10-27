// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.Objects;

import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 立刻做不下去的一步：第一刻就按给的问题失败。船上找不到上船、下船的地方，结构上倒桶这类还不会的出手，
 * 都用它把原因原样交给上一层（挑站位的换下一处，施工记进那一格的问题）。
 *
 * @param problem 做不下去的原因
 */
record Refused(Problem problem) implements Action {

    Refused {
        Objects.requireNonNull(problem, "problem");
    }

    @Override public ActionStatus tick(TickContext context) {
        return ActionStatus.failed(problem);
    }

    @Override public String describe() {
        return problem.message();
    }
}
