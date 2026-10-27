// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine.api;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;

/**
 * 已建档的机器（`machine_build` 建的、`machine_inspect(operation=read_site)` 读的）：按一格找它属于哪一台。
 * 只读，不改档案；档案读不出来按没有对待。
 */
public interface ArchivedMachines {

    /**
     * 这个维度里声明了这一格要放方块、还没拆除的那一份档案；几份都盖住时取档案名排在前面的那份，没有为空。
     *
     * @param dimension 维度 ID，例如 minecraft:overworld
     * @param cell      世界坐标的一格
     */
    Optional<Machine> covering(String dimension, BlockPos cell);

    /**
     * 一台机器落地后的样子。
     *
     * @param name   档案名
     * @param blocks 蓝图里声明要放方块的每一格（世界坐标）；清空的格与倒桶的格不算
     */
    record Machine(String name, Set<BlockPos> blocks) {
        public Machine {
            Objects.requireNonNull(name, "name");
            blocks = Set.copyOf(blocks);
        }
    }
}
