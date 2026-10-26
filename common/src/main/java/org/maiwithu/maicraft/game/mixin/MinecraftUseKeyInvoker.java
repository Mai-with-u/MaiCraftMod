// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 原版"按一下使用键"那条路的入口：玩家按右键时原版走 startUseItem，先发按键事件、再照准星用手里的东西。
 * 交互动作的右键直接提交"右键方块 / 使用物品"，不经按键事件；只认按键的模组处理要从这里进。
 */
@Mixin(Minecraft.class)
public interface MinecraftUseKeyInvoker {

    /** 按一下使用键，和玩家按右键同一条路。 */
    @Invoker("startUseItem")
    void maicraft$startUseItem();
}
