// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.maiwithu.maicraft.game.ClientHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 取画面的取图点：一帧开始记帧时间；世界画完、主画面重新绑上（物品栏、聊天栏、界面还没画）时拍只有世界的那种；
 * 整帧画完时拍连界面一起的那种；渲染世界时把那一份投影抄下来，标注按它换算像素。只读，不改渲染。
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererPictureMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void maicraft$frameStarted(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo callback) {
        var pictures = ClientHooks.cameraPictures();
        if (pictures != null) pictures.frameStarted();
    }

    // 世界与手画完、后期效果做完，原版把主画面重新绑上准备画物品栏：只拍世界的请求在这一刻取。
    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;bindWrite(Z)V", ordinal = 0, shift = At.Shift.AFTER))
    private void maicraft$worldDrawn(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo callback) {
        var pictures = ClientHooks.cameraPictures();
        if (pictures != null) pictures.worldDrawn();
    }

    // 整帧画完：物品栏、界面都在主画面上了，连界面一起拍的请求在这一刻取。
    @Inject(method = "render", at = @At("RETURN"))
    private void maicraft$frameDrawn(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo callback) {
        var pictures = ClientHooks.cameraPictures();
        if (pictures != null) pictures.frameDrawn();
    }

    // 渲染世界时定下的投影（含视野与镜头晃动）原样放行，只抄一份：手的投影是另一份，不能拿来换算世界。
    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;resetProjectionMatrix(Lorg/joml/Matrix4f;)V",
            ordinal = 0))
    private Matrix4f maicraft$worldProjection(Matrix4f projection) {
        var pictures = ClientHooks.cameraPictures();
        if (pictures != null) pictures.worldProjection(projection);
        return projection;
    }
}
