// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;

/**
 * 客户端收到的别的实体挨打的伤害包：服务端发给看得见挨打者的所有人，包里有伤害类型、出手的实体、直接的实体，
 * 没有伤害数值。本地玩家自己挨的那一下走伤害观察（DamageObserver），不走这里。
 * 由网络包的 Mixin 在客户端线程喂进来，只读不改。听的一方据此分清"他打中了别的东西"与"他对空挥手"。
 */
public interface EntityHurtSeen {

    /**
     * 本地玩家以外的一个实体挨了一下。
     *
     * @param entity   挨打的实体
     * @param source   原版的伤害来源：类型与出手的实体（可能为空）
     * @param gameTick 这一刻的游戏刻
     */
    void hurt(Entity entity, DamageSource source, long gameTick);
}
