// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.network.CharacterReachTolerance;

/**
 * 服务端角色账号的交互距离容差判断：只有服务端玩家且通过角色账号名单准入时，
 * 才在原版传入的距离余量上再加 1.0 格刹车与挤退余量；客户端玩家与名单外的普通玩家一律原样保留。
 * 原版后续的物品消耗、方块放置、破坏进度与保护事件照常结算，这里只给距离余量的计算。
 */
public final class CharacterReach {

    private CharacterReach() {}

    /** 按是否准入角色账号计算实际生效的距离余量：准入账号加上 1.0 格额外容差，普通玩家保持原值。 */
    public static double effectivePadding(double distancePadding, boolean admitted) {
        return admitted ? distancePadding + CharacterReachTolerance.CHARACTER_EXTRA_PADDING : distancePadding;
    }

    /** 按角色账号名单与玩家名、UUID、主机和管理员身份计算实际生效的距离余量。 */
    public static double effectivePadding(CharacterAccounts accounts, String name, UUID id,
                                          boolean singleplayerOwner, boolean operator, double distancePadding) {
        boolean admitted = accounts != null && accounts.admits(name, id, singleplayerOwner, operator);
        return effectivePadding(distancePadding, admitted);
    }

    /**
     * 从运行时的玩家对象取实际生效的距离余量：客户端玩家、尚未挂入服务的服务器或非角色账号都不改动原值。
     */
    public static double paddingForPlayer(Player player, double distancePadding) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return distancePadding;
        }
        CharacterAccounts accounts = ServerLinkServices.characterAccounts(serverPlayer.serverLevel().getServer());
        return effectivePadding(distancePadding, accounts != null && accounts.admits(serverPlayer));
    }

    /** 与原版方块交互距离核对同源的纯计算：眼睛到目标整格包围盒的距离平方严格小于上限平方。 */
    public static boolean canInteractWithBlock(Vec3 eyePosition, double blockInteractionRange,
                                               BlockPos pos, double distancePadding, boolean admitted) {
        double limit = blockInteractionRange + effectivePadding(distancePadding, admitted);
        return new AABB(pos).distanceToSqr(eyePosition) < limit * limit;
    }

    /** 与原版实体交互距离核对同源的纯计算：眼睛到实体包围盒的距离平方严格小于上限平方。 */
    public static boolean canInteractWithEntity(Vec3 eyePosition, double entityInteractionRange,
                                                AABB entityBounds, double distancePadding, boolean admitted) {
        double limit = entityInteractionRange + effectivePadding(distancePadding, admitted);
        return entityBounds.distanceToSqr(eyePosition) < limit * limit;
    }
}
