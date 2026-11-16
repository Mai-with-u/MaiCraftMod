// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fillpit;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 填坑能力的任务输入：要填哪一处地面坑（或在脚下半径内把营地地面坑依次填平）。
 */
record FillPitInput(Target target, int radius, Permissions permissions) implements TaskInput {

    FillPitInput {
        if (radius < 4 || radius > 32) {
            throw new IllegalArgumentException("填坑范围要在 4..32 格之间：" + radius);
        }
    }

    @Override
    public String describe() {
        return target == null ? "填平附近 " + radius + " 格内的营地地面坑" : "填平指定的地面坑";
    }
}
