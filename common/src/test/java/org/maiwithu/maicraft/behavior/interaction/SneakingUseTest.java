// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.interaction.ScriptedInteractionSender;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 潜行右键包装动作的离线验证：潜行落定后才向目标方块提交右键，结算完成后保留内部交互结论并在收尾时释放潜行。
 */
class SneakingUseTest {

    private static final BlockPos LOG_POS = new BlockPos(2, 64, 2);

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void holdsSneakBeforeSubmittingBlockUseAndExposesSettledResult() {
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        sender.nextStatus = PendingInteraction.Status.CONFIRMED_APPLIED;
        InteractionTestFakes.FakeContext context = new InteractionTestFakes.FakeContext().withSender(sender);
        InteractionTestFakes.FakeScene scene = new InteractionTestFakes.FakeScene(context);
        scene.ray = new BlockHitResult(Vec3.atCenterOf(LOG_POS), Direction.UP, LOG_POS, false);

        RecordingSneak sneak = new RecordingSneak();
        AimAndInteract inner = new AimAndInteract(
                new InteractionTarget.BlockTarget(LOG_POS),
                c -> InteractionConfirmation.Verdict.APPLIED,
                p -> scene);
        SneakingUse use = new SneakingUse(inner, sneak);

        // 第一刻：先按住潜行等待潜行落定，不抢在非潜行状态下瞄准或提交。
        assertInstanceOf(ActionStatus.Running.class, use.tick(context.asTickContext()));
        assertTrue(sneak.holding);
        assertTrue(sender.submissions.isEmpty());

        // 第二刻起潜行已落定：转头瞄准 → 转到位提交 → 确认生效。
        context.advance();
        assertInstanceOf(ActionStatus.Running.class, use.tick(context.asTickContext()));
        context.advance();
        assertEquals(ActionStatus.progressed(), use.tick(context.asTickContext()));
        assertEquals(1, sender.submissions.size());

        context.advance();
        assertInstanceOf(ActionStatus.Done.class, use.tick(context.asTickContext()));
        assertNotNull(use.result());
        assertEquals(InteractionVerdict.APPLIED, use.result().verdict());

        use.close();
        assertFalse(sneak.holding);
        assertTrue(sneak.closed);
    }

    /** 记录潜行按下、首刻等待落定与收尾松开的替身。 */
    private static final class RecordingSneak implements HoldsSneak {
        boolean holding;
        boolean settled;
        boolean closed;

        @Override
        public Action sneaking(Action inner) {
            return new Action() {
                @Override
                public ActionStatus tick(TickContext context) {
                    holding = true;
                    if (!settled) {
                        settled = true;
                        return ActionStatus.running();
                    }
                    return inner.tick(context);
                }

                @Override
                public void pause() {
                    holding = false;
                    inner.pause();
                }

                @Override
                public void close() {
                    holding = false;
                    closed = true;
                    inner.close();
                }

                @Override
                public Interruptibility interruptibility() {
                    return inner.interruptibility();
                }

                @Override
                public String describe() {
                    return "潜行着" + inner.describe();
                }
            };
        }
    }
}
