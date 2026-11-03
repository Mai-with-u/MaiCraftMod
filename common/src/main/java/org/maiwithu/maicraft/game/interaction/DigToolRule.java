// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.block.state.BlockState;


/**
 * 挖一格之前拿哪件工具：由玩家行为层实现，从构造函数传进挖掘器。
 *
 * <p>结论只给"要不要动手"：主手不动（含手上没有更合适的）、换到哪一格、腾空主手，
 * 或者点名的工具身上没有。换成哪件、腾空手的东西往哪放，由挖掘器按原生动作逐步兑现。
 */
@FunctionalInterface
public interface DigToolRule {

    /** 照准星点一下的原始点击：不动手，手里有什么就用什么。 */
    DigToolRule HELD = (player, state, expectedBlocks, wantsDrop, wanted) -> DigToolPlan.keep();

    /**
     * @param player         本地角色
     * @param state          要挖的方块
     * @param expectedBlocks 这一趟预计还要挖几格同样的（估不出给 1）
     * @param wantsDrop      这一趟挖下来的东西要不要；只要路通给 false
     * @param wanted         目标里点名的工具；为 null 时按每格代价自动挑
     */
    DigToolPlan plan(LocalPlayer player, BlockState state, int expectedBlocks, boolean wantsDrop, DigTool wanted);

    /** 只要格号的入口（寻路与拆结构自己搬东西）：不用换时 -1。 */
    default int slotFor(LocalPlayer player, BlockState state, int expectedBlocks) {
        return plan(player, state, expectedBlocks, false, null) instanceof DigToolPlan.Hold(int slot) ? slot : -1;
    }
}
