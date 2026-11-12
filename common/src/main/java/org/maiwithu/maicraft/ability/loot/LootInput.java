// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.loot;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 搜刮能力的任务输入：要搜哪一只战利品容器（或在哪一片挑）与搜索半径。
 *
 * @param target      指定的容器（观察编号、坐标或记住的地点）；null 表示在脚下半径内挑没开过的战利品容器
 * @param radius      不指定具体容器时的搜索半径（1..64 格）
 * @param permissions 这次任务的许可
 */
record LootInput(Target target, int radius, Permissions permissions) implements TaskInput {

    LootInput {
        if (radius < 1 || radius > 64) {
            throw new IllegalArgumentException("搜刮半径要在 1..64 格之间：" + radius);
        }
    }

    @Override
    public String describe() {
        return target == null ? "搜刮附近 " + radius + " 格内的战利品容器" : "搜刮指定的战利品容器";
    }
}
