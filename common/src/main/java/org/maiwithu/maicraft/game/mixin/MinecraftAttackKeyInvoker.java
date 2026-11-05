// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 原版"按一下攻击键"那条路的入口：玩家按左键时原版走 startAttack，照准星打实体、开始挖方块，
 * 对着空处就只挥一下手（之后半秒内再按不算）。和按使用键的入口对称。
 */
@Mixin(Minecraft.class)
public interface MinecraftAttackKeyInvoker {

    /** 按一下攻击键，和玩家按左键同一条路；这一下被原版吞掉（点空后的半秒内、手忙着）时返回值不说明挥没挥手。 */
    @Invoker("startAttack")
    boolean maicraft$startAttack();
}
