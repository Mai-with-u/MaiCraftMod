// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 持续使用的兜底收尾账：使用已经不在手上（被原版松开、手里的东西被换走，或持用它的任务已经结束），
 * 确认就永远不会再来了。短暂宽限后如实记不确定，别让一个永远等待的提交把端口卡到期限——
 * 那段时间里下一个任务连第一次点击都提交不了，看上去就是"光瞄准不出手"。
 * 挖掘和仍然在手上的持用不归这里管：它们得由专门的动作去物理停止。
 */
final class AbandonedItemUse {

    /** 持续使用自己结束后，等效果同步回来的宽限（刻）。 */
    private static final int GRACE_TICKS = 10;

    private PendingInteraction pending;
    private long sinceTick = -1;

    /**
     * 每刻核对一次：使用还在手上时这条账不算数；不在手上了先认下这一次（换了新确认就重新起算，
     * 不把上一个的等待算到它头上），宽限过完就如实收场。
     *
     * @param wasHeld  起手确认之后确实进入过举盾、进食这类持续持用；瞬时右键不适用提前收尾
     * @param stillOurs 这次持用仍归提交它的那份等待；被后来者接管后旧账不再写结果
     */
    void retire(PlayerContext context, PendingInteraction pending, boolean wasHeld, boolean stillOurs) {
        if (pending.terminal() || pending.kind() != PendingInteraction.Kind.USE_ITEM
                || !wasHeld || context.localPlayer().isUsingItem()) {
            forget();
            return;
        }
        if (this.pending != pending) {
            this.pending = pending;
            sinceTick = context.clientTick();
            return;
        }
        // 宽限半秒：物品自行结束的那一瞬，扣数／组件证据要等服务端那一刻结算再同步回来，联机时常常晚几刻到。
        // 原先只宽限两刻，砂纸磨完一件、成品还没进背包就判"没等到确认"，抛光玫瑰石英怎么都磨不出来。
        if (context.clientTick() - sinceTick < GRACE_TICKS) return;
        forget();
        if (stillOurs && !pending.terminal()) {
            pending.finish(PendingInteraction.Status.UNCERTAIN,
                    "手上的持续使用结束了，没等到游戏确认效果");
        }
    }

    /** 新的等待接手或等待已终结时把账清掉，不把旧账算到后来者头上。 */
    void forget() {
        pending = null;
        sinceTick = -1;
    }
}
