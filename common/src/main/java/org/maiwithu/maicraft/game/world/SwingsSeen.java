// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import net.minecraft.world.entity.Entity;

/**
 * 客户端收到的别人挥手的包：服务端收到谁挥了手就广播给挥手的人以外的人，主手、副手分开。
 * 由网络包的 Mixin 在客户端线程、原版让那个实体挥完手以后喂进来，只读不改。
 * 听的一方据此认"他对着谁挥了一下"；挖方块、打东西、放方块也会挥手，分辨是听的一方的事。
 */
public interface SwingsSeen {

    /**
     * 一个实体挥了一下手。
     *
     * @param entity   挥手的实体（本地世界里找得到的那个）
     * @param mainHand 主手为真，副手为假
     * @param gameTick 这一刻的游戏刻
     */
    void swung(Entity entity, boolean mainHand, long gameTick);
}
