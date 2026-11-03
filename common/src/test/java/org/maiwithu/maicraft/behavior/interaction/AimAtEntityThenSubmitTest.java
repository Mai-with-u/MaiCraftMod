// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.interaction.ScriptedInteractionSender;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;

/**
 * 对准一只生物再替模组发一次包：转到位、准星落在它身上、眼前没开界面才发；对不准不发；
 * 发了等确认，确认窗到期没回按没生效；它不在了按目标没了。
 */
class AimAtEntityThenSubmitTest {

    /** 生物在角色正前方（-Z 方向）一格多；替身视线跟着最后一次转头走。 */
    private static final Vec3 BODY = new Vec3(0.0, 64.0, -2.0);

    private final ScriptedInteractionSender sender = new ScriptedInteractionSender();
    private final InteractionTestFakes.FakeContext context = new InteractionTestFakes.FakeContext().withSender(sender);
    private final InteractionTestFakes.FakeScene scene = new InteractionTestFakes.FakeScene(context);
    private final AtomicInteger sent = new AtomicInteger();
    private boolean gone;
    private boolean onTarget = true;
    private boolean worldInFront = true;

    private AimAtEntityThenSubmit action() {
        return new AimAtEntityThenSubmit(() -> gone, ignored -> BODY, sight -> onTarget, ignored -> scene,
                "打开她的对话框", sent::incrementAndGet, c -> InteractionConfirmation.Verdict.PENDING, 40,
                () -> worldInFront);
    }

    private ActionStatus run(AimAtEntityThenSubmit action, int ticks) {
        ActionStatus status = null;
        for (int i = 0; i < ticks; i++) {
            status = action.tick(context.asTickContext());
            context.advance();
            if (status instanceof ActionStatus.Failed || status instanceof ActionStatus.Done) break;
            if (!sender.submissions.isEmpty()) break;
        }
        return status;
    }

    @Test
    void 对准了才发包_发了等确认() {
        AimAtEntityThenSubmit action = action();
        run(action, 10);
        assertEquals(1, sent.get(), "转到位、准星落在它身上才发那一个包");
        assertEquals(PendingInteraction.Kind.MOD_PROTOCOL, sender.submissions.getFirst().kind());
        sender.nextStatus = PendingInteraction.Status.CONFIRMED_APPLIED;
        assertInstanceOf(ActionStatus.Done.class, action.tick(context.asTickContext()));
    }

    @Test
    void 准星一直落不到它身上_不发包并说对不准() {
        onTarget = false;
        ActionStatus status = run(action(), 60);
        Problem problem = assertInstanceOf(ActionStatus.Failed.class, status).problem();
        assertEquals(Problem.Kind.UNREACHABLE, problem.kind());
        assertEquals(0, sent.get());
    }

    @Test
    void 眼前开着界面_不发包() {
        worldInFront = false;
        ActionStatus status = run(action(), 100);
        assertEquals(Problem.Kind.WRONG_TIME, assertInstanceOf(ActionStatus.Failed.class, status).problem().kind());
        assertEquals(0, sent.get());
    }

    @Test
    void 发了游戏没接_按没生效() {
        AimAtEntityThenSubmit action = action();
        run(action, 10);
        sender.nextStatus = PendingInteraction.Status.CONFIRMED_NOT_APPLIED;
        ActionStatus status = action.tick(context.asTickContext());
        assertEquals(Problem.Kind.REFUSED_BY_GAME,
                assertInstanceOf(ActionStatus.Failed.class, status).problem().kind());
    }

    @Test
    void 它不在了_按目标没了() {
        gone = true;
        ActionStatus status = run(action(), 3);
        Problem problem = assertInstanceOf(ActionStatus.Failed.class, status).problem();
        assertEquals(Problem.Kind.TARGET_GONE, problem.kind());
        assertTrue(problem.message().contains("打开她的对话框"));
    }
}
