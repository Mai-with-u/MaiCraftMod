// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * 交互提交运行的三条约定：本刻不收下一刻原样重交、收下后逐刻等到结论、
 * 任务边界没等到的提交交边界路由收场。用脚本化提交入口替身驱动，不接触真实客户端。
 */
class InteractionSubmissionTest {

    @Test
    void entryNotAcceptingThisTickRetriesTheSameSubmissionNextTick() {
        FakePlayerContext context = new FakePlayerContext(null);
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        context.sender = sender;
        InteractionSubmission submission = new InteractionSubmission();
        AtomicInteger attempts = new AtomicInteger();
        // 本刻交互机会被占用：交不出去不算失败，下一刻原样再交。
        sender.busyThisTick = true;
        assertNull(submission.submit(context, c -> {
            attempts.incrementAndGet();
            return sender.useItem(c, null, InteractionConfirmation.pending(), 10);
        }));
        assertEquals(1, attempts.get());
        sender.busyThisTick = false;
        assertTrue(submission.submit(context.nextTick(), c -> {
            attempts.incrementAndGet();
            return sender.useItem(c, null, InteractionConfirmation.pending(), 10);
        }) != null);
        assertEquals(2, attempts.get());
    }

    @Test
    void acceptedSubmissionIsNotSubmittedAgainWhileAwaitingConfirmation() {
        FakePlayerContext context = new FakePlayerContext(null);
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        context.sender = sender;
        InteractionSubmission submission = new InteractionSubmission();
        AtomicInteger attempts = new AtomicInteger();
        assertTrue(submission.submit(context, c -> {
            attempts.incrementAndGet();
            return sender.useItem(c, null, InteractionConfirmation.pending(), 10);
        }) != null);
        // 已交出去还在等确认：再调提交不重复出手，提交现场只有一份。
        assertNull(submission.submit(context, c -> {
            attempts.incrementAndGet();
            return sender.useItem(c, null, InteractionConfirmation.pending(), 10);
        }));
        assertEquals(1, attempts.get());
        assertEquals(1, sender.submissions.size());
        assertTrue(submission.awaitingConfirmation());
    }

    @Test
    void pollKeepsWaitingThenReturnsTheConclusionAndResets() {
        FakePlayerContext context = new FakePlayerContext(null);
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        context.sender = sender;
        InteractionSubmission submission = new InteractionSubmission();
        submission.submit(context, c -> sender.useItem(c, null, InteractionConfirmation.pending(), 10));
        // 确认条件还没给结论：逐刻等，每刻返回 null。
        assertNull(submission.poll(context));
        sender.nextStatus = PendingInteraction.Status.CONFIRMED_APPLIED;
        PendingInteraction settled = submission.poll(context.nextTick());
        assertSame(PendingInteraction.Status.CONFIRMED_APPLIED, settled.status());
        // 结论已取走：回到未提交状态，awaiting 为假，可以交出下一次。
        assertFalse(submission.awaitingConfirmation());
        assertNull(submission.poll(context));
        assertTrue(submission.submit(context, c -> sender.useItem(c, null, InteractionConfirmation.pending(), 10)) != null);
        assertEquals(2, sender.submissions.size());
    }

    @Test
    void taskBoundarySettlesTheInFlightSubmissionAndReleasesTheWait() {
        FakePlayerContext context = new FakePlayerContext(null);
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        context.sender = sender;
        InteractionSubmission submission = new InteractionSubmission();
        submission.submit(context, c -> sender.useItem(c, null, InteractionConfirmation.pending(), 10));
        submission.settleForTaskBoundary(context, "任务被替换，确认不再等");
        assertEquals(1, sender.settleItemUseCalled);
        assertFalse(submission.awaitingConfirmation());
        // 收场后没有占位：下一次提交不被旧等待挡住。
        assertTrue(submission.submit(context, c -> sender.useItem(c, null, InteractionConfirmation.pending(), 10)) != null);
    }

    @Test
    void taskBoundaryWithoutInFlightSubmissionDoesNothing() {
        FakePlayerContext context = new FakePlayerContext(null);
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        context.sender = sender;
        new InteractionSubmission().settleForTaskBoundary(context, "没有在途提交");
        assertEquals(0, sender.settleItemUseCalled);
    }
}
