// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import org.maiwithu.maicraft.game.mixin.MinecraftAttackKeyInvoker;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 真按一下攻击键：和玩家按左键走同一条路（原版 startAttack），准星指着实体就打它一下，指着方块就开始挖，
 * 对着空处只挥一下手；原版点空以后半秒内再按不算。和按一下使用键对称。
 *
 * <p>只管按，不管瞄：调用方先把准星转到要的地方，再用 {@link #aimsAtNothing} 或 {@link #aimedEntity} 核对。
 * 按之前占本刻唯一的一次交互机会，人按 F8 拿着角色、本刻已经出过手时不按。
 */
public final class AttackKeyPress {

    /** 准星此刻什么都没指着（原版这一刻的射线命中是空的）：这时按攻击键只挥一下手。 */
    public boolean aimsAtNothing() {
        HitResult hit = Minecraft.getInstance().hitResult;
        return hit == null || hit.getType() == HitResult.Type.MISS;
    }

    /** 准星此刻指着的实体编号；没指着实体为 -1。 */
    public int aimedEntity() {
        return Minecraft.getInstance().hitResult instanceof EntityHitResult hit ? hit.getEntity().getId() : -1;
    }

    /** 眼前是世界画面、没开任何界面：原版只在这时处理按键。 */
    public boolean worldInFront() {
        return Minecraft.getInstance().screen == null;
    }

    /** 占本刻的交互机会按一下攻击键；机会已被占、人在操作角色时不按，返回 false。 */
    public boolean press(PlayerContext player) {
        if (player == null || !player.tryClaimInteraction()) return false;
        ((MinecraftAttackKeyInvoker) Minecraft.getInstance()).maicraft$startAttack();
        return true;
    }
}
