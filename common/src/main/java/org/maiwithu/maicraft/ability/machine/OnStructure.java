// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.Objects;

import org.maiwithu.maicraft.ability.machine.spi.StructureWorksites;

/**
 * 在组装好的物理结构上动手时是哪一个：结构身份、指它的观察编号、它的工地、局部坐标的原点。
 * 在船上施工时原点就是档案锚点（结构上已有档案用它，没有就按原点新建）。
 *
 * @param structureId 结构身份
 * @param seenId      指它的观察编号 s#（说的时候用）；指档案名时为 null
 * @param worksite    结构上的工地
 * @param origin      局部坐标的原点
 */
record OnStructure(String structureId, String seenId, StructureWorksites.Worksite worksite, StructureOrigin origin) {

    OnStructure {
        Objects.requireNonNull(structureId, "structureId");
        Objects.requireNonNull(worksite, "worksite");
        Objects.requireNonNull(origin, "origin");
    }
}
