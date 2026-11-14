// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import java.util.List;

import org.maiwithu.maicraft.game.interaction.DigToolUse;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 挖方块的动作能说出这一趟用了什么工具：挖了几格、用了哪几件、各磨掉多少、各花了几刻。
 * 收一批格子时在每格挖完、收掉那一格的子动作之前问一次，整批挖完再把几段拼成结果里的一条。
 *
 * <p>只是能说出；说不出（还没有挖过、或者这一层接不上）给空表，结果里就不写工具现场。
 */
public interface TellsToolUse extends Action {

    /** 这个动作到目前为止挖下来的格子用了哪些工具，按先后。 */
    List<DigToolUse> toolUses();

    /** 尝试过连锁但退回单挖的原因；真正连上了或本来就是普通单挖时为 null。 */
    default String notChainedReason() {
        return null;
    }
}
