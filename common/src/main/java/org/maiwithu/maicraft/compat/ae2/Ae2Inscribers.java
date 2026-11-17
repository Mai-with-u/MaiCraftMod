// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.List;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.machine.spi.MachineState;

/**
 * 读 AE2 压印器的模组读写接缝（客户端）：输出槽里有什么。压印器的四个工作格随方块实体同步给客户端画出来，
 * 站在外面就读得到；进度与有没有电不同步，读不到。读写端在 NeoForge 模块里，测试里用替身。
 */
public interface Ae2Inscribers {

    /** 没有读写端时用：读不出任何输出。 */
    Ae2Inscribers NONE = new Ae2Inscribers() {
        @Override public Optional<List<MachineState.Shown>> output(ClientLevel level, BlockPos at) {
            return Optional.empty();
        }
    };

    /**
     * 这一格压印器的输出槽里现在有什么；不是压印器、没加载给空，空槽给空表。
     */
    Optional<List<MachineState.Shown>> output(ClientLevel level, BlockPos at);
}
