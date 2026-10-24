// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine.spi;

import java.util.Objects;

import net.minecraft.core.Direction;

/**
 * 一格某一面此刻装着的部件，连同它在不在干活：例如 AE2 线缆格北面的输出总线、东面的终端。
 * 看机器时每个部件单列成一个设备，它朝着的那一格（总线对着的箱子、保险库）算一条连接；
 * 和蓝图里的部件 {@link PartCell} 是同一种东西，这一份是现场读到的样子。
 *
 * @param side   装在这一格的哪一面
 * @param itemId 部件的物品 ID，例如 ae2:export_bus
 * @param state  部件此刻的运行状态：有电有频道在干活、有电没频道停着、没电离线
 */
public record MountedPart(Direction side, String itemId, MachineState state) {

    public MountedPart {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(state, "state");
    }
}
