// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine.api;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;

/**
 * 已建档的机器（`machine_build` 建的、`machine_inspect(operation=read_site)` 读的）：按一格找它属于哪一台，
 * 以及组装、拆回物理结构时让组装器所在的那份档案跟着结构走。档案读不出来按没有对待。
 */
public interface ArchivedMachines {

    /**
     * 这个维度里声明了这一格要放方块、还没拆除的那一份档案；几份都盖住时取档案名排在前面的那份，没有为空。
     * 记在物理结构上的档案不算（它的格在结构的地块里）。
     *
     * @param dimension 维度 ID，例如 minecraft:overworld
     * @param cell      世界坐标的一格
     */
    Optional<Machine> covering(String dimension, BlockPos cell);

    /** 记在这个物理结构上、还没拆除的那份档案名；没有为空。 */
    default Optional<String> onStructure(String structureId) {
        return Optional.empty();
    }

    /**
     * 组装后：世界里组装器那一格落在哪份档案里，那份跟到新结构上——组装是纯平移，锚点挪进地块、蓝图偏移不变。
     * 组装器不在任何档案里为空。
     *
     * @param worldAssembler 拉杆时组装器在世界里的格
     * @param structureId    组装出来的结构身份
     * @param plotAssembler  组装器在结构地块里的格
     */
    default Optional<Moved> moveOntoStructure(String dimension, BlockPos worldAssembler, String structureId,
            BlockPos plotAssembler) {
        return Optional.empty();
    }

    /**
     * 拆回后：记在这个结构上的档案跟回世界——拆回以组装器为轴转整 90°，落到组装器当时所在的世界格，
     * 锚点按它换算、蓝图跟着转。结构上没记档案为空。
     *
     * @param plotAssembler  拆回前组装器在结构地块里的格
     * @param worldAssembler 拆回后组装器落在世界里的格
     * @param turn           拆回时转的整 90°（结构自己的 +z 落到世界的哪个方向）
     */
    default Optional<Moved> moveToWorld(String structureId, BlockPos plotAssembler, BlockPos worldAssembler,
            Rotation turn) {
        return Optional.empty();
    }

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

    /**
     * 一份档案跟着结构走了一次。
     *
     * @param name  档案名
     * @param saved 存进去了；存不进去时档案还在原处
     */
    record Moved(String name, boolean saved) {
        public Moved {
            Objects.requireNonNull(name, "name");
        }
    }
}
