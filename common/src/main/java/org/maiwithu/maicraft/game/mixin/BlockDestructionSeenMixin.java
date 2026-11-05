// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import org.maiwithu.maicraft.game.ClientHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 原版处理完挖方块进度包后，把谁在挖哪一格、挖到几成交给挖方块观察；不改任何同步结果。 */
@Mixin(ClientPacketListener.class)
public abstract class BlockDestructionSeenMixin {

    @Shadow private ClientLevel level;

    @Inject(method = "handleBlockDestruction", at = @At("TAIL"))
    private void maicraft$observeDigging(ClientboundBlockDestructionPacket packet, CallbackInfo callback) {
        var seen = ClientHooks.diggingSeen();
        if (seen == null || level == null) return;
        seen.digging(packet.getId(), packet.getPos(), packet.getProgress(), level.getGameTime());
    }
}
