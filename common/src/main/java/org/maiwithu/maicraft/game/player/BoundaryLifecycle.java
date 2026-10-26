// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import net.minecraft.client.player.LocalPlayer;

/**
 * 边界生命周期：玩家控制权边界在边界时刻（每刻开头、换身体、控制权换手、客户端停机）
 * 对交互提交与容器界面两个入口驱动的共同小协议。两个入口各自实现它，边界只认这份协议，
 * 新增一种边界时刻只改协议与各实现各一处，不再在两个宽接口里成对加方法。
 */
public interface BoundaryLifecycle {

    /**
     * 每刻开始时由控制权边界调一次：推进没人再管的收尾。任务结束时挂下的停挖、没确认完的副手动作、
     * 请游戏关的界面，原任务对象已经没了，只能由这里接着做完；新任务开始之前先结清，
     * 占掉本刻交互机会时新任务等下一刻。
     */
    default void advance(PlayerContext context) {}

    /**
     * 换了身体、控制权换手或客户端停机时由控制权边界调：旧的等待按效果不确定结清，不再发任何世界操作。
     * 旧结果不能算到新操作者头上，也不能让一条没人推进的等待挡住后面的提交。
     */
    default void revokeForBoundary(String reason) {}

    /**
     * 人按 F8 收回角色时由控制权边界调：旧等待按不确定结清，自动化点开的界面走原版关闭，
     * 光标上挂着的东西由游戏照原版规则还回背包或掉出来；人接手时面前不留一个开着的箱子。
     * 没有界面要收拾的入口不必覆写：按普通边界结清等待即可。
     */
    default void revokeForHumanHandoff(LocalPlayer player, String reason) {
        revokeForBoundary(reason);
    }
}
