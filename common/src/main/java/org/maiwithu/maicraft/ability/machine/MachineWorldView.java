// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * 机器能力看现场的最小只读视图：角色此刻在哪、一格现在是什么方块、一块地上躺着几件什么。
 * 只读，不动角色；实现接在当刻的角色上下文上，测试用替身摆场景。
 */
interface MachineWorldView {

    /** 角色此刻站的位置与所在维度；不在世界里给空。 */
    Optional<Spot> playerSpot();

    /** 一格现在的方块状态；那一格没加载给空，不按空气算。 */
    Optional<BlockState> stateAt(BlockPos at);

    /**
     * 这一块地上此刻躺着几件 itemId：机器弹到地上的产物是看得见的掉落物，站在旁边就数得出来。
     * 不在世界里、那一块没加载给 0，等的是增长，读不到不会误报做好了。
     */
    default int itemsOnGround(AABB area, String itemId) {
        return 0;
    }

    /** 角色的位置：站位与维度。 */
    record Spot(BlockPos pos, String dimension) {
    }
}
