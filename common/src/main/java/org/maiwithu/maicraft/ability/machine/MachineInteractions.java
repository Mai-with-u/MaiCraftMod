// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 机器能力要的原生交互入口：右键一格方块（拨机器自带的开关），点开机器的界面看出口，捡起机器弹到地上的产物。
 * 动作自己做瞄准、提交与逐刻确认；站位由调用方先靠近（点开界面、捡东西的动作自己走过去）。测试用替身按脚本回答。
 */
interface MachineInteractions {

    /** 右键一格方块，生效与否按给定的确认条件核对。 */
    Action useBlock(BlockPos target, InteractionConfirmation confirmation);

    /**
     * 走到机器跟前点开它的界面看一眼出口：数一数界面里有几件 itemId，collect 时把它们搬进背包，再关上界面。
     * 这一处接不上界面（离线替身没给）时给空。
     */
    default Optional<OutputLook> lookIntoMenu(BlockPos machine, String itemId, boolean collect,
            Permissions permissions) {
        return Optional.empty();
    }

    /** 走过去把这一块地上躺着的 itemId 一件件捡进背包；这一处接不上捡东西时给空。 */
    default Optional<OutputLook> pickUpFrom(AABB area, String itemId) {
        return Optional.empty();
    }
}
