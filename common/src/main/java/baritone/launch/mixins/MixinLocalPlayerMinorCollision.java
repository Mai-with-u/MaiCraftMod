// SPDX-License-Identifier: GPL-3.0-only
package baritone.launch.mixins;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.event.events.RotationMoveEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 原版判断"擦了一下墙算不算撞墙"时，拿走路用的朝向去比实际挪动的方向，而不是拿镜头朝向。
 *
 * <p>原版先按朝向把前进键换算成世界里的方向，再跟这一刻真正挪出去的方向比，相差在 8 度以内才算擦边，
 * 擦边不打断疾跑。自动走路时脚下按路线朝向走（{@link MixinEntity} 在换算前进键时换成路线朝向），
 * 镜头却看着前面几格的点，两者常差十几到几十度；原版若拿镜头朝向比，贴着树、墙角、台阶走时
 * 擦一下就被当成迎面撞墙，把疾跑停掉，下一刻又按回去，视野就跟着一缩一放。
 * 这里在原版判断的前后把朝向临时换成走路那一份、再换回来，与换算前进键用同一个朝向。
 * 人自己操作时路线朝向不生效，判断照原版用镜头朝向。
 */
@Mixin(LocalPlayer.class)
public abstract class MixinLocalPlayerMinorCollision {
    /** 判断期间被换下来的镜头朝向；为 null 表示这次没有换。 */
    @Unique
    private Float baritone$cameraYaw;

    // 原版判断开始前：有路线朝向且和镜头不同，就先把朝向换成走路那一份。
    @Inject(method = "isHorizontalCollisionMinor", at = @At("HEAD"))
    private void baritone$useWalkingYaw(Vec3 movement, CallbackInfoReturnable<Boolean> callback) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer(self);
        if (baritone == null) return;
        RotationMoveEvent event = new RotationMoveEvent(RotationMoveEvent.Type.MOTION_UPDATE, self.getYRot(), self.getXRot());
        baritone.getGameEventHandler().onPlayerRotationMove(event);
        if (event.getYaw() == self.getYRot()) return;
        baritone$cameraYaw = self.getYRot();
        self.setYRot(event.getYaw());
    }

    // 判断一结束就把镜头朝向换回去，画面和发给服务端的朝向都不受影响。
    @Inject(method = "isHorizontalCollisionMinor", at = @At("RETURN"))
    private void baritone$restoreCameraYaw(Vec3 movement, CallbackInfoReturnable<Boolean> callback) {
        if (baritone$cameraYaw == null) return;
        ((LocalPlayer) (Object) this).setYRot(baritone$cameraYaw);
        baritone$cameraYaw = null;
    }
}
