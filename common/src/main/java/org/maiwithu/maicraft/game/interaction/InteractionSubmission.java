// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 一次交互提交从交出到有确认结论的逐刻推进，把提交入口的三条约定收在同一个地方：
 * 交出的这一刻没被收下（交互机会被占用或上一动作还没结清）不算失败，下一刻原样重交；
 * 收下之后逐刻结算，直到确认条件给出结论或到期限；任务在结论之前结束时，
 * 按动作种类交边界路由收场（挖掘发停手、物品使用按玩家变更结算、其余按不确定交还）。
 * 调用方只说两件事：提交什么（{@link Attempt} 里调交互提交入口的哪个方法）与怎样算生效
 * （确认条件和期限随提交方法一起给）；每刻调 {@link #submit} 或 {@link #poll} 推进一刻。
 */
public final class InteractionSubmission {

    /** 调用方给的提交动作：调 {@link InteractionSender} 的某个提交方法，带上确认条件与期限。 */
    @FunctionalInterface
    public interface Attempt {
        PendingInteraction submit(PlayerContext context);
    }

    private PendingInteraction pending;

    /**
     * 试着交出一次交互：还没有在途提交时才调用提交动作，收下时返回新的等待记录，
     * 调用方据此记账（冻结现场、写状态说明）。本刻没被收下返回 null，不算失败，
     * 下一刻原样重交；已有在途提交或已有没取走的结论时也返回 null，不重复提交。
     */
    public PendingInteraction submit(PlayerContext context, Attempt attempt) {
        if (pending != null) return null;
        pending = attempt.submit(context);
        return pending;
    }

    /**
     * 逐刻结算在途提交：确认条件给出了结论就取走并复位到未提交状态，返回该结论；
     * 还在等、本刻没轮到或没有在途提交时返回 null。取走之前每刻原样返回 null，结论不丢。
     */
    public PendingInteraction poll(PlayerContext context) {
        if (pending == null || pending.terminal()) return null;
        pending = context.interactionSender().poll(context, pending);
        if (!pending.terminal()) return null;
        PendingInteraction settled = pending;
        pending = null;
        return settled;
    }

    /** 已交出去还没等到确认结论时为真：这时停下任务，结论会留在不确定里，由边界收尾处理。 */
    public boolean awaitingConfirmation() {
        return pending != null && !pending.terminal();
    }

    /** 任务边界收尾：还没等到确认的在途提交按动作种类交边界路由结算，之后回到未提交状态。 */
    public void settleForTaskBoundary(PlayerContext context, String boundaryReason) {
        if (pending != null && !pending.terminal()) {
            context.interactionSender().settleForTaskBoundary(context, pending, boundaryReason);
        }
        pending = null;
    }
}
