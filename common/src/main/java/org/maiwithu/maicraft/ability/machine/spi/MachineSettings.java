// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine.spi;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 一种机器的设置项：这一格有哪些设置、现在是什么值、把一项改成要的值怎么改。
 * 没有设置项的机器（厨锅这类）不实现这条，不用答"我没有设置项"。
 */
public interface MachineSettings {

    /** 这一格有哪些设置项、现在是什么值；读不到或没有时给空列表。 */
    List<MachineSetting> settings(BlockPos at);

    /** 把一项设置改成要的值：手持配置器点面、开界面加规则；改完由调用方读回核对。这种机器没有这一项时给空。 */
    Optional<Action> change(BlockPos at, String key, String value, Permissions permissions);

    /**
     * 组装好的物理结构上这一格的设置项与现值（格是结构地块里的）：读得了结构上这种机器的设置才给，不然空表。
     *
     * @param structureId 结构身份
     */
    default List<MachineSetting> settingsOnStructure(String structureId, BlockPos at) {
        return List.of();
    }

    /**
     * 在组装好的物理结构上把这一格的一项设置改成要的值（格是地块坐标），走过去用 {@code reach}（坐在座位上够得着就坐着改）。
     * 只发一包、人够得着就行的（无线红石打字机绑键）给动作；要瞄准点设置框的在结构上还不会，给空。改完由调用方读回核对。
     */
    default Optional<Action> changeOnStructure(String structureId, BlockPos at, String key, String value,
            Permissions permissions, BringsPlayerClose reach) {
        return Optional.empty();
    }

    /**
     * 宿主格某一面装着的部件（例如 AE2 线缆部件）的设置项与现值；没有设置项或读不到时给空列表。
     * 查看机器在拼部件设备时按面单独读取，非空才写入该部件的设置视图。
     */
    default List<MachineSetting> partSettings(BlockPos host, Direction side) {
        return List.of();
    }
}
