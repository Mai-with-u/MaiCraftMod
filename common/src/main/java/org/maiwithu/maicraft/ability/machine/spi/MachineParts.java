// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine.spi;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 一种机器装在方块某一面上的部件（AE2 线缆上的总线、终端）：某一面装着什么、装这一个部件能不能装、
 * 怎么装、装上没有。没有部件这种东西的机器不实现这条，不用答"我上不装部件"。
 */
public interface MachineParts {

    /**
     * 这一格各面此刻装着的部件与它们在不在干活。看机器时线缆合进网络，
     * 装在线缆上的部件才是干活的东西，要单列出来；没有部件这种东西的机器不实现这条。
     */
    default List<MountedPart> mountedParts(BlockPos at) {
        return List.of();
    }

    /** 这个部件能不能装在这一格宿主的这一面；能给空，不能给一句说明。用法同安装段的形状检查。 */
    Optional<String> partProblem(PartCell part, BlockState host);

    /** 手持部件右键宿主的那一面把它装上；这种机器装不了这个部件时给空。 */
    Optional<Action> mount(PartCell part, Permissions permissions);

    /** 这个部件现在是不是装在那一面上。 */
    boolean mounted(PartCell part);
}
