// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 真实交互提交方各种收场留下的确认记录：不手抄句子，驱动 {@link DefaultInteractionSender} 走到每一种收场
 * （生效、没生效、出乎预料、确认窗到期、接新动作前收掉过期的、任务边界、调用原版出错），交回确认记录里那句话。
 * 离线没有真实客户端，调用原版那一步会出错，正好落到"游戏出错、没能确认"的收场上。
 */
public final class SettledConfirmations {

    private static final int TIMEOUT_TICKS = 5;

    private SettledConfirmations() {}

    /** 放置类手势的确认窗到期、证据一样都没出现：按没生效收场的那句话。 */
    public static String expiredWithoutChange() {
        return expire(placement(), null, null);
    }

    /** 能离线走到的每一种收场的那句话，一种一条。 */
    public static List<String> all() {
        List<String> details = new ArrayList<>();
        details.add(settledAs(InteractionConfirmation.Verdict.APPLIED));
        details.add(settledAs(InteractionConfirmation.Verdict.NOT_APPLIED));
        details.add(settledAs(InteractionConfirmation.Verdict.DIVERGED));
        details.add(expiredWithoutChange());
        // 拿不准的手势到期：原版结论没有、当场接受、当场拒了（带命中的拒收条件）三种都要过一遍。
        details.add(expire(InteractionConfirmation.pending(), null, null));
        details.add(expire(InteractionConfirmation.pending(), true, null));
        details.add(expire(InteractionConfirmation.pending(), false, new EntityUseScene(true, 4.2, 3.0, true, 75.0F)));
        details.add(expire(InteractionConfirmation.pending(), false, new EntityUseScene(false, 1.0, 3.0, false, null)));
        details.add(reclaimedStale());
        details.addAll(boundaries());
        details.addAll(miningStops());
        details.addAll(nativeCallsThatThrew());
        details.add(new PendingInteraction(PendingInteraction.Kind.USE_BLOCK, new FakePlayerContext(null),
                TIMEOUT_TICKS, 1, InteractionConfirmation.pending(), null, null).detail());
        return details;
    }

    // 确认条件第一刻就给出结论：生效、没生效、出乎预料各走一次真实的逐刻确认。
    private static String settledAs(InteractionConfirmation.Verdict verdict) {
        DefaultInteractionSender sender = new DefaultInteractionSender(null);
        FakePlayerContext context = new FakePlayerContext(null);
        PendingInteraction pending = installed(sender, context, PendingInteraction.Kind.USE_BLOCK, c -> verdict);
        sender.poll(context, pending);
        return pending.detail();
    }

    // 确认条件一直等不到结论，推到期限那一刻再查：按手势是否挂了"过期即没生效"分两种收场。
    private static String expire(InteractionConfirmation confirmation, Boolean nativeAnswer, EntityUseScene scene) {
        DefaultInteractionSender sender = new DefaultInteractionSender(null);
        FakePlayerContext context = new FakePlayerContext(null);
        PendingInteraction pending = installed(sender, context, PendingInteraction.Kind.INTERACT_ENTITY, confirmation);
        if (nativeAnswer != null) pending.noteNativeAnswer(nativeAnswer);
        if (scene != null) pending.noteEntityUseScene(scene);
        context.tick = pending.deadlineTick();
        sender.poll(context, pending);
        return pending.detail();
    }

    // 上一动作过了期限还挂着：下一次提交前就地收掉。
    private static String reclaimedStale() {
        DefaultInteractionSender sender = new DefaultInteractionSender(null);
        FakePlayerContext context = new FakePlayerContext(null);
        PendingInteraction stale = installed(sender, context, PendingInteraction.Kind.USE_BLOCK,
                InteractionConfirmation.pending());
        context.tick = stale.deadlineTick() + 1;
        sender.acceptSubmission(context);
        return stale.detail();
    }

    // 任务边界收场：调用方没给原因时的默认说法，以及换角色、控制权换手时的结清。
    private static List<String> boundaries() {
        List<String> details = new ArrayList<>();
        DefaultInteractionSender sender = new DefaultInteractionSender(null);
        FakePlayerContext context = new FakePlayerContext(null);
        PendingInteraction retired = installed(sender, context, PendingInteraction.Kind.USE_BLOCK,
                InteractionConfirmation.pending());
        sender.retireOneShotForTaskBoundary(context, retired, null);
        details.add(retired.detail());

        PendingInteraction abandoned = installed(sender, context, PendingInteraction.Kind.INTERACT_ENTITY,
                InteractionConfirmation.pending());
        sender.abandonOneShotForTaskBoundary(abandoned, "");
        details.add(abandoned.detail());

        PendingInteraction itemUse = installed(sender, context, PendingInteraction.Kind.USE_ITEM,
                InteractionConfirmation.pending());
        sender.settleItemUseForTaskBoundary(context, itemUse, null);
        details.add(itemUse.detail());
        return details;
    }

    // 挖掘的停手：当场停、任务边界停、欠着下一刻停，离线发停挖都会出错，落到"没能确认停没停下"。
    private static List<String> miningStops() {
        List<String> details = new ArrayList<>();
        DefaultInteractionSender sender = new DefaultInteractionSender(null);
        FakePlayerContext context = new FakePlayerContext(null);
        PendingInteraction stopped = installed(sender, context, PendingInteraction.Kind.BREAK_BLOCK,
                InteractionConfirmation.pending());
        sender.cancelBreaking(context, stopped);
        details.add(stopped.detail());

        context.nextTick();
        PendingInteraction atBoundary = installed(sender, context, PendingInteraction.Kind.BREAK_BLOCK,
                InteractionConfirmation.pending());
        sender.cancelBreakingForTaskBoundary(context, atBoundary, null);
        details.add(atBoundary.detail());

        context.nextTick();
        PendingInteraction continued = installed(sender, context, PendingInteraction.Kind.BREAK_BLOCK,
                InteractionConfirmation.pending());
        context.nextTick();
        sender.continueBreaking(context, continued);
        details.add(continued.detail());
        return details;
    }

    // 调用原版途中出错的几种一次性动作：离线没有客户端，提交那一步必然出错。
    private static List<String> nativeCallsThatThrew() {
        List<String> details = new ArrayList<>();
        BlockPos cell = new BlockPos(1, 64, 1);
        details.add(new DefaultInteractionSender(null).useBlock(new FakePlayerContext(null), InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(cell), Direction.UP, cell, false),
                InteractionConfirmation.pending(), TIMEOUT_TICKS).detail());
        details.add(new DefaultInteractionSender(null).wakeFromBed(new FakePlayerContext(null),
                InteractionConfirmation.pending(), TIMEOUT_TICKS).detail());

        DefaultInteractionSender sender = new DefaultInteractionSender(null);
        FakePlayerContext context = new FakePlayerContext(null);
        PendingInteraction used = sender.useItem(context, InteractionHand.MAIN_HAND,
                InteractionConfirmation.pending(), TIMEOUT_TICKS);
        details.add(used.detail());
        context.nextTick();
        details.add(sender.releaseUsingItem(context, used).detail());
        return details;
    }

    // 装成提交方当前等确认的那一下；连续稳定一刻就算数。
    private static PendingInteraction installed(DefaultInteractionSender sender, FakePlayerContext context,
                                                PendingInteraction.Kind kind, InteractionConfirmation confirmation) {
        PendingInteraction pending = new PendingInteraction(kind, context, TIMEOUT_TICKS, 1, confirmation, null, null);
        sender.install(pending);
        return pending;
    }

    // 放置类手势的确认：等不到证据，过期即没生效。
    private static InteractionConfirmation placement() {
        return new InteractionConfirmation() {
            @Override public Verdict observe(PlayerContext context) {
                return Verdict.PENDING;
            }
            @Override public boolean expiryMeansNotApplied() { return true; }
        };
    }
}
