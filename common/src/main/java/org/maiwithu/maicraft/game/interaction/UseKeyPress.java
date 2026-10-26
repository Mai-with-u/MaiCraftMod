// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.HitResult;

import org.maiwithu.maicraft.game.mixin.MinecraftUseKeyInvoker;

/**
 * 真按一下使用键：和玩家按右键走同一条路（原版 startUseItem），先发按键事件，模组挂在按键上的处理
 * （Create 手持锁链潜行按右键，清掉链条输送机接线记着的起点）都会收到；没有模组接下，原版再照准星用手里的东西。
 * 交互动作的右键直接提交"右键方块 / 使用物品"，不经按键事件，只认按键的东西要走这里。
 *
 * <p>只管按，不管瞄：准星指着方块或实体时按下去会照常用到它们，只想让模组收到按键的，
 * 先把准星对着空处（{@link #aimsAtNothing}）。调用方经交互提交入口占好本刻的交互机会再按。
 */
public final class UseKeyPress {

    /** 准星此刻什么都没指着（原版这一刻的射线命中是空的）：这时按使用键只会用手里的东西本身。 */
    public boolean aimsAtNothing() {
        HitResult hit = Minecraft.getInstance().hitResult;
        return hit == null || hit.getType() == HitResult.Type.MISS;
    }

    /** 眼前是世界画面、没开任何界面（聊天框也算界面）：原版和模组只在这时处理按键。 */
    public boolean worldInFront() {
        return Minecraft.getInstance().screen == null;
    }

    /** 按一下使用键。 */
    public void press() {
        ((MinecraftUseKeyInvoker) Minecraft.getInstance()).maicraft$startUseItem();
    }
}
