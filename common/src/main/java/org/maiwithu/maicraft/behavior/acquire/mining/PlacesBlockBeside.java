// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import java.util.Optional;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 把身上的一格方块放在角色身边的执行接缝：砾石挖出来没出燧石，就把身上的砾石放在脚边再挖，
 * 省得满世界找下一块砾石。放哪一格以当刻的现场为准（身边空着、下面结实、头顶空着的一格，眼前同一层优先），
 * 不记进世界记忆——它马上就要被挖回来，不是摆着用的设施。现场动作由游戏接口层实现，测试用替身。
 * 放不下（身边没空位、身上没有、游戏拒绝）时动作以问题失败。
 */
public interface PlacesBlockBeside {

    /**
     * @param blockType 要放的方块，也是身上那件东西的物品 ID，例如 minecraft:gravel
     * @param placedAt  真放下了才收到落下的那一格；没放下不会收到
     * @return 放的动作；接缝接不上时为空
     */
    Optional<Action> placeBeside(String blockType, Consumer<BlockPos> placedAt);
}
