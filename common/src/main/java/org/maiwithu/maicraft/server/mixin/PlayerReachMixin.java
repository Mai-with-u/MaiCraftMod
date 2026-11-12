// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.mixin;

import net.minecraft.world.entity.player.Player;
import org.maiwithu.maicraft.server.CharacterReach;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 服务端角色账号的交互距离容差钩子：原版方块右键、方块破坏与实体交互统一调用 Player 上的距离检查并传入 1.0 格余量；
 * 当调用者是准入的角色账号时，在原余量基础上再加 1.0 格刹车与挤退余量，避免角色刚踏进站位边沿停步或被水流、生物轻推半格时被判够不着。
 * 普通玩家与客户端侧调用原样放过，不改任何原版距离，也不绕过原版物品消耗、放置破坏与保护结算。
 */
@Mixin(Player.class)
public abstract class PlayerReachMixin {

    @ModifyVariable(method = "canInteractWithBlock", at = @At("HEAD"), argsOnly = true)
    private double maicraft$padCharacterBlockReach(double distancePadding) {
        // 仅服务端准入的角色账号叠加刹车容差，其余玩家原样返回。
        return CharacterReach.paddingForPlayer((Player) (Object) this, distancePadding);
    }

    @ModifyVariable(method = "canInteractWithEntity(Lnet/minecraft/world/phys/AABB;D)Z", at = @At("HEAD"), argsOnly = true)
    private double maicraft$padCharacterEntityReach(double distancePadding) {
        // 实体重载方法内部也转发到包围盒重载，只在这里改一次避免重复叠加。
        return CharacterReach.paddingForPlayer((Player) (Object) this, distancePadding);
    }
}
