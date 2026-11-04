// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.game.player.LocalPlayerInput.BotInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 自动化走路时像人一样按住疾跑键：原版每刻判开跑时读疾跑键，这里让它读到自动输入按没按。
 * 原版因此会在同一刻里把服务端回传的"没在跑"接回来，撞墙、饿了、在用东西照原版规则停跑。
 * 人自己操作时身上是键盘输入，读值原样不变；其他按键也不受影响。
 */
@Mixin(LocalPlayer.class)
public abstract class SprintKeyHoldMixin {
    @WrapOperation(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;isDown()Z"))
    private boolean maicraft$holdSprintKey(KeyMapping key, Operation<Boolean> original) {
        boolean actual = original.call(key);
        if (key != Minecraft.getInstance().options.keySprint) return actual;
        return BotInput.sprintKeyDown(((LocalPlayer) (Object) this).input, actual);
    }
}
