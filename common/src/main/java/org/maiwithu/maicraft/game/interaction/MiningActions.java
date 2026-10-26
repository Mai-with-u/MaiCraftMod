// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 持续挖掘的开挖、逐刻推进与停止：挖掘一挖就是几十几百刻，占的是与世界动作同一份逐刻确认等待，
 * 每刻最多推进一次，停手要真的发原版停止而不是把等待记录一扔了事。任务边界来不及停的时刻，
 * 欠下的停挖原因也记在这里，下一刻由交互提交方在推进任何新动作之前先结清。
 */
final class MiningActions {

    private final DefaultInteractionSender owner;
    /** 旧任务留下的挖掘还未实际停止时保留此原因，由每刻推进先完成停手。 */
    private String pendingBreakCancellationReason;

    MiningActions(DefaultInteractionSender owner) {
        this.owner = owner;
    }

    /** 开挖一格：占用本刻交互机会并核对上一动作已结清后，记录目标原状态，按住原版挖掘。 */
    PendingInteraction start(PlayerContext context, BlockHitResult hit, int timeoutTicks) {
        // 先检查当前控制权和有无旧动作，再占用本刻交互机会，记录目标原状态后开始挖；
        // 本刻不收（机会被占用或上一动作没结清）时交回 null，由调用方下一刻再开挖。
        if (!owner.acceptSubmission(context)) return null;
        PendingInteraction pending = new PendingInteraction(
                PendingInteraction.Kind.BREAK_BLOCK,
                context,
                timeoutTicks,
                2,
                InteractionConfirmation.blockBecomesAir(
                        hit.getBlockPos(), context.level().getBlockState(hit.getBlockPos())),
                hit.getBlockPos(),
                hit.getDirection());
        owner.install(pending);
        try {
            DefaultInteractionSender.gameMode(context).startDestroyBlock(hit.getBlockPos(), hit.getDirection());
            context.localPlayer().swing(InteractionHand.MAIN_HAND);
        } catch (RuntimeException failure) {
            pending.finish(PendingInteraction.Status.UNCERTAIN,
                    "native mining start threw after entering the client action path");
        }
        return owner.poll(context, pending);
    }

    /** 逐刻推进：先读取当前结果，已经完成或这一刻已经挥过手就不再继续挥手。 */
    PendingInteraction advance(PlayerContext context, PendingInteraction pending) {
        requireActiveBreak(pending);
        owner.poll(context, pending);
        if (pending.terminal() || pending.lastNativeTick() == context.clientTick()) return pending;
        // 本刻没有交互机会就这一刻不挥手：等待记录原样交回，下一刻接着挖。
        if (!DefaultInteractionSender.claimSubmission(context)) return pending;
        try {
            DefaultInteractionSender.gameMode(context).continueDestroyBlock(pending.breakTarget(), pending.breakFace());
            context.localPlayer().swing(InteractionHand.MAIN_HAND);
            pending.nativeAdvanced(context.clientTick());
        } catch (RuntimeException failure) {
            pending.finish(PendingInteraction.Status.UNCERTAIN,
                    "native mining continuation threw before the block outcome was confirmed");
        }
        return owner.poll(context, pending);
    }

    /** 停止挖掘：先看是否已经挖完，仍在挖才发原版停止，避免把刚确认完成的操作改说成取消。 */
    PendingInteraction stop(PlayerContext context, PendingInteraction pending) {
        DefaultInteractionSender.requireCurrent(context);
        requireActiveBreak(pending);
        owner.poll(context, pending);
        if (pending.terminal()) {
            pendingBreakCancellationReason = null;
            return pending;
        }
        // 本刻没有交互机会就先不停：等待记录原样交回，调用方下一刻再来停。
        if (!DefaultInteractionSender.claimSubmission(context)) return pending;
        try {
            DefaultInteractionSender.gameMode(context).stopDestroyBlock();
            pending.finish(PendingInteraction.Status.CANCELLED,
                    "native mining was cancelled before confirmation");
        } catch (RuntimeException failure) {
            pending.finish(PendingInteraction.Status.UNCERTAIN,
                    "native mining cancellation could not be confirmed");
        }
        pendingBreakCancellationReason = null;
        return pending;
    }

    /**
     * 任务结束时停挖：本刻还有交互机会就当场停；来不及就记下原因，下一刻在新任务操作角色之前先停。
     * 挖掘窗口长达数千刻，不停手会让等待一直占着动作队列，期间所有原生提交都被拒绝。
     */
    PendingInteraction stopForTaskBoundary(
            PlayerContext context, PendingInteraction pending, String boundaryReason) {
        requireActiveBreak(pending);
        owner.poll(context, pending);
        if (pending.terminal()) {
            pendingBreakCancellationReason = null;
            return pending;
        }
        String reason = boundaryReason == null || boundaryReason.isBlank()
                ? "the owning task ended before native mining confirmation"
                : boundaryReason;
        if (context.tryClaimInteraction()) {
            try {
                DefaultInteractionSender.gameMode(context).stopDestroyBlock();
                pending.finish(PendingInteraction.Status.CANCELLED, reason);
            } catch (RuntimeException failure) {
                pending.finish(PendingInteraction.Status.UNCERTAIN,
                        reason + "; native mining cancellation could not be confirmed");
            }
            pendingBreakCancellationReason = null;
            return pending;
        }
        pendingBreakCancellationReason = reason;
        return pending;
    }

    /**
     * 结清一刻欠下的停挖：等待记录还能认出是那次挖掘就发原版停止并按取消收场；
     * 已经终结或被别的动作接替的，如实按不确定结清。欠账清掉后交回真，本刻不再走普通推进。
     */
    boolean settleOwedCancellation(PlayerContext context, PendingInteraction pending) {
        if (pendingBreakCancellationReason == null) return false;
        owner.poll(context, pending);
        if (pending.terminal()) {
            pendingBreakCancellationReason = null;
            return true;
        }
        if (pending.kind() != PendingInteraction.Kind.BREAK_BLOCK) {
            pending.finish(PendingInteraction.Status.UNCERTAIN,
                    "a task-boundary break cancellation no longer owned the active break interaction");
            pendingBreakCancellationReason = null;
            return true;
        }
        if (!context.tryClaimInteraction()) return true;
        try {
            DefaultInteractionSender.gameMode(context).stopDestroyBlock();
            pending.finish(PendingInteraction.Status.CANCELLED,
                    pendingBreakCancellationReason);
        } catch (RuntimeException failure) {
            pending.finish(PendingInteraction.Status.UNCERTAIN,
                    pendingBreakCancellationReason
                            + "; native mining cancellation could not be confirmed");
        } finally {
            pendingBreakCancellationReason = null;
        }
        return true;
    }

    /** 任务刚结束时登记停挖原因：只登记，下一刻的推进先停挖再结算。 */
    void oweCancellation(String boundaryReason) {
        pendingBreakCancellationReason = boundaryReason == null || boundaryReason.isBlank()
                ? "the owning task ended before native mining confirmation" : boundaryReason;
    }

    /** 没有欠着的停挖：换角色、收尾与新动作接手时都不再欠。 */
    void clearCancellation() {
        pendingBreakCancellationReason = null;
    }

    /** 还有欠着的停挖：新动作要等它结清才接手。 */
    boolean owesCancellation() {
        return pendingBreakCancellationReason != null;
    }

    // 等待记录必须确实是那次挖掘：对不上号是调用方的协议错误，不是这一刻没轮到。
    private void requireActiveBreak(PendingInteraction pending) {
        owner.requireActive(pending, PendingInteraction.Kind.BREAK_BLOCK);
    }
}
