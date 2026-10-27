// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import net.minecraft.core.BlockPos;

/**
 * 一台机器的档案：建成后留底的"这台机器按什么建的"。名字是 LLM 起的（和地标一样），
 * 蓝图冻结的是相对锚点的偏移，补丁合并后存合并结果；拆除只做标记，蓝图留着，随时可以照着再建。
 *
 * <p>组装成物理结构的机器（飞艇、船、车）档案跟着结构走：记在结构上时锚点是结构地块里的格，蓝图偏移不变；
 * 拆回后跟回世界，锚点换回世界里的格。
 *
 * @param name          档案名，按名字指这台机器
 * @param dimension     机器所在的维度 ID
 * @param anchor        锚点：蓝图原点落在的那一格；记在结构上时是结构地块里的格
 * @param blueprint     冻结的整机蓝图（相对锚点；含合并过的补丁）
 * @param designId      引用的设计编号，蓝图不是从设计来的为 null
 * @param removed       已拆除的标记：拆了的机器档案保留，指名字会说明它已拆除
 * @param networks      建档或接网时两端确认过的网络：种类 → 网络编号
 * @param lastCheckAt   最近一次整机比对的时间；没比对过为 null
 * @param lastCheckNote 最近一次整机比对的结论一句话；没比对过为 null
 * @param structure     记在哪个物理结构上（结构身份）；在世界里为 null
 */
record MachineArchive(String name, String dimension, BlockPos anchor, MachineBlueprint blueprint,
                      String designId, boolean removed, Map<String, String> networks,
                      Instant lastCheckAt, String lastCheckNote, String structure) {

    MachineArchive {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(dimension, "dimension");
        anchor = Objects.requireNonNull(anchor, "anchor").immutable();
        Objects.requireNonNull(blueprint, "blueprint");
        networks = Map.copyOf(networks);
    }

    /** 建档时的样子：蓝图刚落地，还没接过网、没比过对，在世界里。 */
    static MachineArchive fresh(String name, String dimension, BlockPos anchor, MachineBlueprint blueprint,
            String designId) {
        return new MachineArchive(name, dimension, anchor, blueprint, designId, false, Map.of(), null, null, null);
    }

    /** 建在物理结构上的档案：锚点是结构地块里的格（局部坐标的原点）。 */
    static MachineArchive freshOn(String structure, String name, String dimension, BlockPos plotAnchor,
            MachineBlueprint blueprint) {
        return new MachineArchive(name, dimension, plotAnchor, blueprint, null, false, Map.of(), null, null,
                Objects.requireNonNull(structure, "structure"));
    }

    /** 记在物理结构上，不在世界里。 */
    boolean onStructure() {
        return structure != null;
    }

    /** 换一份蓝图（补丁合并结果）与其余现状：名字、维度、锚点、在哪个结构上都不动。 */
    MachineArchive withBlueprint(MachineBlueprint merged, String designId, Instant checkedAt, String checkNote) {
        return new MachineArchive(name, dimension, anchor, merged,
                designId != null ? designId : this.designId, false, networks, checkedAt, checkNote, structure);
    }

    /** 记下这次确认过的网络：种类 → 网络编号，旧的保留。 */
    MachineArchive joinedTo(Map<String, String> joined) {
        Map<String, String> merged = new LinkedHashMap<>(networks);
        merged.putAll(joined);
        return new MachineArchive(name, dimension, anchor, blueprint, designId, removed, merged,
                lastCheckAt, lastCheckNote, structure);
    }

    /** 拆除标记：蓝图保留，再建时覆盖这个标记。 */
    MachineArchive markRemoved() {
        return new MachineArchive(name, dimension, anchor, blueprint, designId, true, networks, lastCheckAt,
                lastCheckNote, structure);
    }

    /** 组装后跟到结构上：锚点平移到结构地块里，蓝图偏移不变（组装是纯平移）。 */
    MachineArchive movedOnto(String structureId, BlockPos plotAnchor) {
        return new MachineArchive(name, dimension, plotAnchor, blueprint, designId, removed, networks,
                lastCheckAt, lastCheckNote, Objects.requireNonNull(structureId, "structureId"));
    }

    /** 拆回后跟回世界：锚点换成世界里的格，蓝图换成跟着转过的那份。 */
    MachineArchive movedToWorld(BlockPos worldAnchor, MachineBlueprint turned) {
        return new MachineArchive(name, dimension, worldAnchor, turned, designId, removed, networks,
                lastCheckAt, lastCheckNote, null);
    }
}
