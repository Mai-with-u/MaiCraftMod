// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import org.maiwithu.maicraft.game.ClientHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 原版把服务器发来的整包背包内容装进本地玩家之后，告诉控制权边界"这具身体的背包到了"。
 * 每换一具本地玩家（进世界、死亡重生、过传送门）背包都从空的起，要等这一包才看得见身上的东西；
 * 只读观察，不改任何同步结果。容器编号 0 是玩家自己的背包，开着的箱子、工作台另有编号。
 */
@Mixin(ClientPacketListener.class)
public abstract class BackpackContentsMixin {

    @Inject(method = "handleContainerContent", at = @At("TAIL"))
    private void maicraft$noteBackpackReceived(ClientboundContainerSetContentPacket packet, CallbackInfo callback) {
        if (packet.getContainerId() != 0) return;
        var control = ClientHooks.playerControl();
        if (control != null) control.noteBackpackReceived(Minecraft.getInstance().player);
    }
}
