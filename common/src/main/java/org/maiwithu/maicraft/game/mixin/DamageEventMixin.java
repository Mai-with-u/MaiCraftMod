// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.world.entity.Entity;
import org.maiwithu.maicraft.game.ClientHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 原版处理完伤害包后，本地玩家挨的这一下（是谁、什么伤）交给伤害观察，别的实体挨的交给别人挨打的观察
 * （认"他打中了别的东西"用）；不改任何同步结果。
 */
@Mixin(ClientPacketListener.class)
public abstract class DamageEventMixin {

    @Shadow private ClientLevel level;

    @Inject(method = "handleDamageEvent", at = @At("TAIL"))
    private void maicraft$observeDamage(ClientboundDamageEventPacket packet, CallbackInfo callback) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || level == null) return;
        // 别人挨的：交给看别的玩家的那一侧，分清他是打中了东西还是对空挥手。
        if (packet.entityId() != player.getId()) {
            var others = ClientHooks.entityHurtSeen();
            Entity entity = level.getEntity(packet.entityId());
            if (others != null && entity != null) others.hurt(entity, packet.getSource(level), level.getGameTime());
            return;
        }
        var observer = ClientHooks.damageObserver();
        if (observer != null) observer.hurt(player, packet.getSource(level), level.getGameTime());
    }
}
