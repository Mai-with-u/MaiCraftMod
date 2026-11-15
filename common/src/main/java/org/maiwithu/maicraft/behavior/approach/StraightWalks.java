// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 直走接缝：三格内平地的短途给一个直走动作——面向站位按键走过去，高一格跳一下，走到跟前后由发起方自己核对；
 * 不是这样的短途给空，发起方照旧走寻路。判不判得过由 {@link NearbyWalks} 说，接缝只负责把判断变成动作。
 */
public interface StraightWalks {

    /** 从 from 到 to 的短途给一个直走动作；不是快路给空。 */
    Optional<Action> toward(BlockPos from, BlockPos to, ApproachWorldView world, ProtectedCells guarded);
}
