// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.Objects;

import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 机器查看的任务输入：看哪里、看多大范围。只读，不带许可（看不需要许可）。
 *
 * @param target      目标对象；不给就是脚下这一片
 * @param radius      没给档案时看多大范围，单位格
 * @param archive     目标对上的机器档案：看档案的范围并和档案整机比对；目标不是档案为 null
 * @param readSite    读现场：把 target 那一台抄成机器蓝图，不只是看
 * @param saveArchive 读现场且非已有档案时，是否把抄出的机器保存为永久档案并记进世界记忆；默认 false（纯只读）
 * @param name        读现场且 saveArchive=true 时的新档案名（显式给出）；不建档或指已有档案时为 null
 */
record MachineInspectInput(Target target, int radius, MachineArchive archive, boolean readSite,
                           boolean saveArchive, String name)
        implements TaskInput {

    MachineInspectInput {
        Objects.requireNonNull(radius, "radius");
    }

    /** 只看不抄。 */
    MachineInspectInput(Target target, int radius, MachineArchive archive) {
        this(target, radius, archive, false, false, null);
    }

    @Override public String describe() {
        return (readSite ? "把机器读成蓝图（" : "看一眼机器（") + radius + " 格范围）";
    }
}
