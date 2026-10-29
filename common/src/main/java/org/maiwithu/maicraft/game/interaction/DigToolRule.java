// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 挖一格之前拿哪件工具的规矩：给角色此刻的背包和要挖的方块，回答换到背包第几格（0~8 快捷栏、9~35 主背包），
 * 不换给 -1。怎么挑（能掉落先于不掉落、最省优先、改掉落的附魔不掺和、预留的不挑）归玩家行为层的挑工具，
 * 挖方块的基础代码只照它换手，自己不比较工具。
 */
@FunctionalInterface
public interface DigToolRule {

    /** 手上拿着什么就用什么挖，不换：照准星点一下的原始点击用它。 */
    DigToolRule HELD = (player, state, minimumDurability, expectedBlocks) -> -1;

    /**
     * @param minimumDurability 这一批至少要剩多少耐久；不要求给 0
     * @param expectedBlocks    这一趟预计还要挖几格（徒手也掉的方块按它算换不换；估不出给 1）
     */
    int slotFor(LocalPlayer player, BlockState state, int minimumDurability, int expectedBlocks);
}
