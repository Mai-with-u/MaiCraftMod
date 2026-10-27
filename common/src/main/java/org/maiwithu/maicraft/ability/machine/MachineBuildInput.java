// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.Objects;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 按机器蓝图施工的任务输入：能力在计划阶段落实好的全部事实——合并后的整机蓝图（相对锚点）、
 * 锚点、档案名、要并进的那份已有档案（新建时为 null），以及拆整台的开关；在组装好的物理结构上施工时还带着那个结构。
 *
 * @param remove      拆档案里的整台：蓝图按档案范围换成全清空，其余字段照给
 * @param blueprint   要落地的整机蓝图（相对锚点的偏移；拆整台时为 null）
 * @param anchor      锚点：蓝图原点落在的那一格（拆整台时是档案锚点）；在结构上施工时是结构地块里的格
 * @param name        档案名：新建时用给定的或按锚点起的，补丁与拆除时是已有档案的名字
 * @param archive     目标是已有档案时的那份档案；新建为 null
 * @param permissions 这次任务的许可
 * @param what        一句话：这次是建、改还是拆
 * @param onStructure 在组装好的物理结构上施工时是哪个结构、原点在哪；在世界里施工为 null
 */
record MachineBuildInput(boolean remove, MachineBlueprint blueprint, WorldPosition anchor, String name,
                         MachineArchive archive, Permissions permissions, String what,
                         OnStructure onStructure) implements TaskInput {

    /** 在世界里施工。 */
    MachineBuildInput(boolean remove, MachineBlueprint blueprint, WorldPosition anchor, String name,
            MachineArchive archive, Permissions permissions, String what) {
        this(remove, blueprint, anchor, name, archive, permissions, what, null);
    }

    MachineBuildInput {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(permissions, "permissions");
        Objects.requireNonNull(what, "what");
        if (!remove && blueprint == null) {
            throw new IllegalArgumentException("施工要给蓝图");
        }
    }

    @Override public String describe() {
        return (remove ? "拆机器「" : "按机器蓝图施工：「") + name + "」";
    }
}
