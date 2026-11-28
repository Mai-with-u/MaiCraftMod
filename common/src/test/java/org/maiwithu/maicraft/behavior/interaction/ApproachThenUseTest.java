// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.behavior.inventory.hands.GearChanges;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.interaction.DigToolRule;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.serverlink.ServerReads;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 靠近后右键一次的组合动作：走近做完才建右键动作，走不过去时右键一次都不出手；
 * 做完、没做完被收掉时都要把手头动作收干净。
 */
class ApproachThenUseTest {

    private final AtomicInteger built = new AtomicInteger();

    @Test
    void 走近失败时右键一次都不出手并按靠近的问题收场() {
        ScriptedStep approach = ScriptedStep.failing();
        ScriptedStep use = new ScriptedStep();
        ApproachThenUse action = new ApproachThenUse("靠近并右键 10 64 10", ApproachTarget.ofBlock(HERE),
                Permissions.DEFAULT, services(target -> approach), () -> use);

        assertTrue(action.tick(VOID_TICK) instanceof ActionStatus.Failed failed
                && failed.problem().message().contains("走不过去"), "靠近的问题原样往上传");
        assertEquals(0, built.get(), "右键动作没有建立");
        assertEquals(1, approach.closed, "失败时手头的靠近动作被收干净");
    }

    @Test
    void 走近完成才建右键动作推进到完成() {
        ScriptedStep approach = new ScriptedStep();
        ScriptedStep use = new ScriptedStep();
        ApproachThenUse action = new ApproachThenUse("靠近并右键扩展充能器 10 64 10", ApproachTarget.ofBlock(HERE),
                Permissions.DEFAULT, services(target -> approach), () -> {
                    built.incrementAndGet();
                    return use;
                });

        assertTrue(action.tick(VOID_TICK) instanceof ActionStatus.Running running && running.progressed(),
                "走近的一刻报推进");
        assertEquals(0, built.get(), "走近没做完不建右键动作");

        assertTrue(action.tick(VOID_TICK) instanceof ActionStatus.Running running && running.progressed(),
                "走近做完的一刻报推进");
        assertEquals(0, built.get(), "走近刚做完这一刻还不建右键动作");

        assertTrue(action.tick(VOID_TICK) instanceof ActionStatus.Running running && running.progressed(),
                "右键在做的一刻报推进");
        assertEquals(1, built.get(), "走近做完才建右键动作");
        assertEquals(1, use.ticked, "右键动作被推进了一次");

        assertTrue(action.tick(VOID_TICK) instanceof ActionStatus.Done, "右键做完整个动作收场");
        assertEquals(2, use.ticked, "收场那一刻右键动作推进到完成");
        assertEquals("靠近并右键扩展充能器 10 64 10", action.describe());
        assertEquals(1, approach.closed, "做完的靠近动作已收干净");
    }

    @Test
    void 没做完被收掉时手头动作跟着收掉() {
        ScriptedStep approach = new ScriptedStep();
        ApproachThenUse action = new ApproachThenUse("靠近并右键 10 64 10", ApproachTarget.ofBlock(HERE),
                Permissions.DEFAULT, services(target -> approach), ScriptedStep::new);

        action.tick(VOID_TICK);
        action.close();
        assertEquals(1, approach.closed, "收掉时正在走的一趟跟着收掉");
        assertFalse(approach.paused, "没喊过暂停就不暂停");
    }

    /** 离线测试的目标格；只用于建目标，不碰世界。 */
    private static final BlockPos HERE = BlockPos.ZERO;

    /** 不碰角色对象的刻上下文替身。 */
    private static final TickContext VOID_TICK = new TickContext() {
        @Override public long gameTick() { return 0; }
        @Override public PlayerContext player() { return null; }
    };

    /** 按脚本推进的动作替身：两刻推进到完成，记下推进、暂停与收场次数。 */
    private static final class ScriptedStep implements Action {
        int ticked;
        int closed;
        boolean paused;
        private final boolean failing;

        ScriptedStep() { this.failing = false; }

        private ScriptedStep(boolean failing) { this.failing = failing; }

        static ScriptedStep failing() { return new ScriptedStep(true); }

        @Override public ActionStatus tick(TickContext tick) {
            ticked++;
            if (failing) return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE, "走过去没做成：走不过去"));
            return ticked >= 2 ? ActionStatus.done() : ActionStatus.progressed();
        }

        @Override public void pause() { paused = true; }

        @Override public void close() { closed++; }

        @Override public String describe() { return failing ? "走不过去的一步" : "按脚本推进的一步"; }
    }

    /** 一份什么都不接的玩家行为服务；本动作只经 approach 建动作。 */
    private static PlayerServices services(Function<ApproachTarget, Action> approachSteps) {
        Protection nobodyOwns = new Protection((dimension, x, y, z) -> Optional.empty(), List::of,
                name -> Optional.empty(), "self");
        BringsPlayerClose approach = new BringsPlayerClose() {
            @Override public Action toward(ApproachTarget target, Permissions permissions) {
                return approachSteps.apply(target);
            }
        };
        GearChanges noGear = new GearChanges() {
            @Override public Optional<Action> wear(String itemId, GearSlotName slot) {
                return Optional.empty();
            }

            @Override public Optional<Action> takeOff(GearSlotName slot) {
                return Optional.empty();
            }
        };
        return new PlayerServices(() -> null, approach, new Interactions(null),
                itemId -> Optional.empty(),
                noGear, inner -> inner,
                (request, permissions) -> new ScriptedStep(), nobodyOwns, itemId -> Set.of(),
                MenuLayouts.VANILLA, new BlockScanService(), ServerReads.NONE,
                new BackpackView() {
                    @Override public List<BackpackStack> stacks() { return List.of(); }
                    @Override public int usedSlots() { return 0; }
                    @Override public int totalSlots() { return 36; }
                },
                DigToolRule.HELD);
    }
}
