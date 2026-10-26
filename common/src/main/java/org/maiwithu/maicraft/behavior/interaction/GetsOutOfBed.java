// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 起床的动作：给服务器发"离开床"，逐刻确认人已经不在睡了。
 * 人本来就不在睡（服务器跳夜、床被拆时本来就会叫醒所有睡着的人）即刻算起完了；
 * 发包没能确认（超时、被不确定收尾）按失败交代，起身有没有真的发生由调用方按现场另看。
 */
public final class GetsOutOfBed implements Action {

    /** 等服务器把人叫起来的耐心；起身通常两三刻就完成，超时说明起身被什么占住了。 */
    private static final int CONFIRM_TIMEOUT_TICKS = 40;

    /** 已提交的"离开床"等待；提交前为 null。 */
    private PendingInteraction pending;

    /** 起床确认：不再睡着就是起完了。 */
    private static final InteractionConfirmation NOT_SLEEPING =
            context -> !context.localPlayer().isSleeping()
                    ? InteractionConfirmation.Verdict.APPLIED
                    : InteractionConfirmation.Verdict.PENDING;

    @Override
    public ActionStatus tick(TickContext context) {
        PlayerContext player = context.player();
        if (player == null || player.localPlayer() == null) {
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "角色不在了，起不了床", null));
        }
        // 本来就不在睡：不用发离开床，直接算起完了。
        if (!player.localPlayer().isSleeping()) {
            return ActionStatus.done();
        }
        if (pending == null) {
            if (player.interactionSender() == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.INTERNAL_ERROR, "提交不起起床：交互入口没接上", null));
            }
            PendingInteraction submitted = player.interactionSender().wakeFromBed(player, NOT_SLEEPING, CONFIRM_TIMEOUT_TICKS);
            // 提交入口这一刻不收：还在床上多躺一刻，下一刻再发离开床。
            if (submitted == null) return ActionStatus.running();
            pending = submitted;
            return ActionStatus.running();
        }
        pending = player.interactionSender().poll(player, pending);
        return switch (pending.status()) {
            case CONFIRMED_APPLIED -> ActionStatus.done();
            case PENDING -> ActionStatus.running();
            // 没生效、出乎预料、超时不确定：如实交代，不无限重发——服务器随时可能自己把人叫醒。
            default -> ActionStatus.failed(Problem.of(Problem.Kind.STUCK,
                    "发了离开床，但没能确认已经起来（" + pending.status() + "）", null));
        };
    }

    @Override
    public String describe() {
        return "从床上起来";
    }
}
