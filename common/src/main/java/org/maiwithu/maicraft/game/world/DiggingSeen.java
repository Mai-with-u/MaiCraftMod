// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import net.minecraft.core.BlockPos;

/**
 * 客户端收到的挖方块进度包：服务端把一格方块被挖到几成发给 32 格内除挖的人以外的玩家，包里写着是谁在挖。
 * 由网络包的 Mixin 在客户端线程喂进来，只读不改。听的一方据此分清"他在挖矿"与"他对着谁挥手"。
 */
public interface DiggingSeen {

    /**
     * 有人在挖一格方块。
     *
     * @param breakerId 挖的人的实体编号
     * @param pos       被挖的那一格
     * @param progress  挖到几成（0–9）；停手、挖掉时是别的值
     * @param gameTick  这一刻的游戏刻
     */
    void digging(int breakerId, BlockPos pos, int progress, long gameTick);
}
