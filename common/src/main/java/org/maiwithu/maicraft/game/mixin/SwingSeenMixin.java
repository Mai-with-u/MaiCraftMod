// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.world.entity.Entity;
import org.maiwithu.maicraft.game.ClientHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 原版处理完挥手包（主手 0、副手 3）后，把谁挥了哪只手交给挥手观察；不改任何同步结果。 */
@Mixin(ClientPacketListener.class)
public abstract class SwingSeenMixin {

    @Shadow private ClientLevel level;

    // 原版在网络线程碰到这个包会先抛出、改到客户端线程再处理；TAIL 只在客户端线程处理完才走到。
    @Inject(method = "handleAnimate", at = @At("TAIL"))
    private void maicraft$observeSwing(ClientboundAnimatePacket packet, CallbackInfo callback) {
        var seen = ClientHooks.swingsSeen();
        int action = packet.getAction();
        if (seen == null || level == null || action != 0 && action != 3) return;
        Entity entity = level.getEntity(packet.getId());
        if (entity != null) seen.swung(entity, action == 0, level.getGameTime());
    }
}
