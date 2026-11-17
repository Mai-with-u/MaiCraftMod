// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.farmersdelight;

import java.util.List;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.machine.spi.MachineState;

/**
 * 读农夫乐事厨锅的模组读写接缝（客户端）：锅里做好的餐与成品、下方有没有热源。
 * 厨锅的九个格随方块实体同步给客户端，热源判定只读方块状态与标签，都站在外面读得到；
 * 煮的进度不同步，读不到。读写端在 NeoForge 模块里，测试里用替身。
 */
public interface FarmersDelightReads {

    /** 没有读写端时用：读不出任何输出，也判不出供热。 */
    FarmersDelightReads NONE = new FarmersDelightReads() {
        @Override public Optional<List<MachineState.Shown>> meal(ClientLevel level, BlockPos at) {
            return Optional.empty();
        }

        @Override public Optional<Boolean> heated(ClientLevel level, BlockPos at) {
            return Optional.empty();
        }
    };

    /**
     * 这一格厨锅里做好的餐：餐品显示格与成品输出格里现在有什么；不是厨锅、没加载给空，
     * 空锅给空表。汤类的餐没配碗时留在显示格不出来，两格一起列。
     */
    Optional<List<MachineState.Shown>> meal(ClientLevel level, BlockPos at);

    /** 这一格厨锅下方有没有热源（篝火、炉子这类，隔着导热方块也算一格深）；不是厨锅给空。 */
    Optional<Boolean> heated(ClientLevel level, BlockPos at);
}
