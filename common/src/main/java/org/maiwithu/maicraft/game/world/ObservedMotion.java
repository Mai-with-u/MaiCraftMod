// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 看到的移动：一个实体在本客户端上这一刻比上一刻挪了多少（格/刻），全仓只有这一份读法。近战瞄点与射箭弹道都按它往前提。
 *
 * <p>原版不把玩家的速度同步给别的客户端（只有被击退、滑翔时才发），角色这边读别的玩家的速度几乎总是零；
 * 看到的位置倒是每刻都在动——服务端两刻发一次位置，客户端分三步插过去，而且每刻在实体动之前记下它上一刻的位置，
 * 前后一减就是它这一刻的移动。怪和角色自己也这样读，同一个口径。
 *
 * <p>一刻挪了两格以上（末影珍珠、传送、过门）不当成在走，这一刻按没动算，免得瞄点、弹道跟着甩出去。
 */
public final class ObservedMotion {

    /** 一刻挪得比这远就当传送，不算它在动（格）。疾跑跳一刻不到 0.7 格。 */
    public static final double TELEPORT_STEP = 2.0;

    private ObservedMotion() {}

    /** 它此刻的移动：这一刻的位置减上一刻的位置；挪得太远当传送，给零。 */
    public static Vec3 of(Entity entity) {
        return step(entity.position(), new Vec3(entity.xo, entity.yo, entity.zo));
    }

    /** 从上一刻到这一刻挪了多少；超过传送那一档给零。 */
    public static Vec3 step(Vec3 now, Vec3 before) {
        Vec3 moved = now.subtract(before);
        return moved.lengthSqr() > TELEPORT_STEP * TELEPORT_STEP ? Vec3.ZERO : moved;
    }
}
