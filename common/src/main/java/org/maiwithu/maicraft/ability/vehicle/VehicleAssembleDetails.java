// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle;

import java.util.List;

import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 组装的结果细节。
 *
 * @param structure   组装出来（或拆回前）的结构身份；没有为空串
 * @param alreadyDone 开始时就是要的样子，什么都没做
 * @param converted   拉了之后真的转换了
 * @param blocks      组装进结构的方块数；拆回或不知道为 -1
 * @param note        经过一句话
 * @param archive     档案这次怎么走的（改记在世界里、跟到新结构、当新载具、留在原身份下）；车站组车、没转换为 null
 * @param glued       拉杆前补胶的经过；拆回、车站组车、已经是要的样子时为 null
 * @param machineArchive 机器档案这次怎么走的；组装器不在任何机器档案里、结构上没记机器档案、没转换时为 null
 */
record VehicleAssembleDetails(String structure, boolean alreadyDone, boolean converted, int blocks, String note,
        VehicleArchives.Handover archive, Glue glued, MachineArchiveMove machineArchive) implements ResultDetails {

    /** 机器档案跟到结构上了。 */
    static final String ON_STRUCTURE = "on_structure";
    /** 机器档案跟回世界了。 */
    static final String IN_WORLD = "in_world";
    /** 机器档案没跟成，留在原处。 */
    static final String KEPT = "kept";

    /**
     * 机器档案（machine_build 建的那份）这次怎么走的。
     *
     * @param name  档案名
     * @param state on_structure 跟到结构上 / in_world 跟回世界 / kept 没跟成留在原处
     * @param note  一句话
     */
    record MachineArchiveMove(String name, String state, String note) {}

    /**
     * 拉杆前补胶的经过。
     *
     * @param archive   组装器所在的机器档案名；不在任何档案里为 null（这时没查胶）
     * @param added     这次补上的胶，一框一句"x, y, z 到 x, y, z"
     * @param remaining 补胶没补成时还差的那几框；补全了为空表
     * @param note      一句话：胶已经盖全、补了几框、没查胶的原因，或补胶没补成
     */
    record Glue(String archive, List<String> added, List<String> remaining, String note) {
        Glue {
            added = List.copyOf(added);
            remaining = List.copyOf(remaining);
        }
    }
}
