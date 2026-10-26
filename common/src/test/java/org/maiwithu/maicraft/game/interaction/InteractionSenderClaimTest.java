// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

/**
 * 交互提交真正发包前都要占用本刻唯一的交互机会：本刻已经出过手、或人按 F8 接回了控制权，
 * 提交交回「本刻不收」的显式结论（null），不抛异常也不悄悄再发一个点击。
 */
class InteractionSenderClaimTest {

    private static final BlockHitResult HIT =
            new BlockHitResult(Vec3.atCenterOf(new BlockPos(0, 64, 0)), Direction.UP, new BlockPos(0, 64, 0), false);

    @Test
    void submissionWithoutAnOpportunityReportsNotAcceptedThisTick() {
        DefaultInteractionSender sender = new DefaultInteractionSender(null);
        FakePlayerContext context = new FakePlayerContext(null);
        context.canInteract = false;

        // 本刻没有交互机会：所有提交都交回 null，调用方据此下一刻重试，不再以异常传播。
        assertNull(sender.useBlock(
                context, InteractionHand.MAIN_HAND, HIT, observed -> InteractionConfirmation.Verdict.APPLIED, 20));
        assertNull(sender.useItem(
                context, InteractionHand.MAIN_HAND, observed -> InteractionConfirmation.Verdict.APPLIED, 20));
        assertNull(sender.startBreaking(context, HIT, 20));
        assertNull(sender.selectHotbar(context, 1, 20));
    }

    @Test
    void oneOpportunityPerTickAndItComesBackNextTick() {
        FakePlayerContext context = new FakePlayerContext(null);
        assertTrue(context.tryClaimInteraction());
        assertFalse(context.canInteractThisTick(), "同一刻用过就没有了");
        assertFalse(context.tryClaimInteraction());
        context.nextTick();
        assertTrue(context.tryClaimInteraction(), "下一刻自动恢复");
    }
}
