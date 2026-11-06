// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import org.maiwithu.maicraft.behavior.construction.Blueprint;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;

/**
 * 一栋建筑的档案：盖完（或读现场时起了名）留底的"这栋房子照什么盖的、在哪、朝哪"。名字是 LLM 起的；
 * 整栋蓝图存相对锚点的偏移，朝向已经转好，后来的补丁并进来。改建按它补盖，补完对它整栋比一遍。
 *
 * @param name          档案名，按名字指这一栋
 * @param dimension     所在的维度
 * @param anchor        锚点：蓝图原点落在的那一格
 * @param rotation      朝向：0 / 90 / 180 / 270，改建照它
 * @param designId      照哪一版图纸盖的；逐格或结构文件盖的为 null
 * @param cells         整栋蓝图：相对锚点的计划格，朝向已经转好
 * @param lastCheckAt   最近一次整栋比对的时间；没比过为 null
 * @param lastCheckNote 最近一次整栋比对的一句结论；没比过为 null
 * @param readFromSite  这份档案是读现场时建的（那一栋不一定是她盖的，可能是别人的房子）；
 *                      她按图盖的为假。圈"她自己的家"不认读来的档
 * @param placedCells   她施工时确认真放下过方块的格（世界坐标，几次施工合起来）：圈"她自己的家"只认这些格外扩一圈，
 *                      不认整张蓝图。被保护拒绝过的那次施工不往这里添
 */
public record BuildingArchive(String name, String dimension, BlockPos anchor, int rotation, String designId,
                              List<PlannedCell> cells, Instant lastCheckAt, String lastCheckNote, boolean readFromSite,
                              List<BlockPos> placedCells) {

    /** 她按图盖的一栋，还没记下真放下过哪几格：盖完时由施工记账补上。 */
    public BuildingArchive(String name, String dimension, BlockPos anchor, int rotation, String designId,
                           List<PlannedCell> cells, Instant lastCheckAt, String lastCheckNote) {
        this(name, dimension, anchor, rotation, designId, cells, lastCheckAt, lastCheckNote, false, List.of());
    }

    /** 读现场建的档（readFromSite 为真）或别的不带放下格的档。 */
    public BuildingArchive(String name, String dimension, BlockPos anchor, int rotation, String designId,
                           List<PlannedCell> cells, Instant lastCheckAt, String lastCheckNote, boolean readFromSite) {
        this(name, dimension, anchor, rotation, designId, cells, lastCheckAt, lastCheckNote, readFromSite, List.of());
    }

    public BuildingArchive {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(dimension, "dimension");
        anchor = Objects.requireNonNull(anchor, "anchor").immutable();
        cells = List.copyOf(cells);
        if (cells.isEmpty()) throw new IllegalArgumentException("建筑档案至少要有一格");
        placedCells = placedCells == null ? List.of() : List.copyOf(new LinkedHashSet<>(placedCells.stream()
                .map(BlockPos::immutable).toList()));
    }

    /** 整栋蓝图落到锚点上：偏移加锚点，朝向已经转好，不再转。 */
    public Blueprint laid() {
        return Blueprint.at(dimension, anchor, Rotation.NONE, cells);
    }

    /** 这一栋在世界里占的范围。 */
    public Blueprint.Bounds bounds() {
        return laid().bounds();
    }

    /** 这一格落不落在这一栋的范围里。 */
    public boolean contains(String dimension, BlockPos pos) {
        return this.dimension.equals(dimension) && bounds().contains(pos);
    }

    /** 换一份整栋蓝图（新一版图纸或并过补丁）与这次比对的结论：名字、维度、锚点、朝向不动。 */
    public BuildingArchive with(List<PlannedCell> merged, String newDesignId, Instant checkedAt, String checkNote) {
        return new BuildingArchive(name, dimension, anchor, rotation, newDesignId, merged, checkedAt, checkNote,
                readFromSite, placedCells);
    }

    /** 再记上这次施工确认真放下过的几格：和以前记的合起来，同一格只记一次。 */
    public BuildingArchive withPlaced(Collection<BlockPos> more) {
        List<BlockPos> all = new ArrayList<>(placedCells);
        all.addAll(more);
        return new BuildingArchive(name, dimension, anchor, rotation, designId, cells, lastCheckAt, lastCheckNote,
                readFromSite, all);
    }
}
