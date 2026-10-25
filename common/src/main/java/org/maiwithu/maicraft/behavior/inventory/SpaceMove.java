// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.List;

import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.behavior.inventory.containers.KnownContainer;

/**
 * 腾挪的一步：计划只说"动哪一堆、怎么动"，怎么点击、怎么存是执行接缝的事。
 * 不再有"丢"这一步：把玩家背包里的东西扔到地上是替玩家做处置决定，腾背包只做可逆的搬动（合并、存放）。
 */
sealed interface SpaceMove {

    /** 把背包里同一种物品的散堆并起来，腾出整格；多少格由当前散堆的数目算出来。 */
    record MergeStacks(String itemId, int slotsFreed) implements SpaceMove {
    }

    /** 把主背包里的一堆放进随身背包。 */
    record ToCarriedBackpack(BackpackStack stack) implements SpaceMove {
    }

    /** 把主背包里的一堆存进一个已知容器。 */
    record ToKnownContainer(KnownContainer container, BackpackStack stack) implements SpaceMove {
    }

    /** 把主背包里的几堆存进身上无线终端连着的网络：终端开一次存完。 */
    record ToNetwork(List<BackpackStack> stacks) implements SpaceMove {
        public ToNetwork {
            stacks = List.copyOf(stacks);
        }
    }
}
