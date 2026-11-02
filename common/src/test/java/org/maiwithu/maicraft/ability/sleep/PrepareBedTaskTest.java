// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sleep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 备床任务的离线场景：三分钟上限按任务实际被推进的刻数计——引擎一直报进展也会在 3600 刻收场；
 * 被自卫、吃饭这类插曲打断的刻（任务没被推进）不计入，收场时刻只看推进到的刻数。
 */
class PrepareBedTaskTest {

    /** 拿东西的引擎替身：按脚本给出动作；没脚本时动作一直报告有进展。 */
    private static final class StubObtain implements ItemNeeds {
        private final Deque<Action> script = new ArrayDeque<>();

        void offer(Action action) {
            script.add(action);
        }

        @Override
        public Action actionFor(ItemRequest request, Permissions permissions) {
            return script.isEmpty() ? new EverProgressing() : script.remove();
        }
    }

    /** 一直有进展的动作：每刻都报告推进，卡住判定永远不触发，只剩三分钟上限能收场。 */
    private static final class EverProgressing implements Action {
        @Override
        public ActionStatus tick(TickContext context) {
            return ActionStatus.progressed();
        }

        @Override public String describe() {
            return "一直有进展的动作";
        }
    }

    private static TickContext tick() {
        // 角色替身不接世界：备床在这条路径上不读玩家现场。
        return new TickContext() {
            @Override public long gameTick() {
                return 100;
            }

            @Override public PlayerContext player() {
                return null;
            }
        };
    }

    private static PrepareBedTask task() {
        // 身上始终没床：任务一路靠引擎动作推进，直到被上限收场或引擎给结局。
        return new PrepareBedTask(new StubObtain(), Permissions.DEFAULT, Optional::empty);
    }

    // 结果还在做给 null，提前结束交出结果。
    private static TaskResult finishedOrNull(TickResult tickResult) {
        return tickResult instanceof TickResult.Finished finished ? finished.result() : null;
    }

    @Test
    void keepsReportingProgressYetEndsAtThe3600TickCap() {
        PrepareBedTask prepare = task();
        TickContext context = tick();
        prepare.start(context);
        // 第 1 到第 3599 推进刻：一直报进展，没到上限，任务照常推进。
        for (int i = 0; i < 3599; i++) {
            assertNull(finishedOrNull(prepare.tick(context)), "3599 刻内没到三分钟上限，不该收场");
        }
        // 第 3600 推进刻：到上限，带着超时的问题收场。
        TaskResult result = finishedOrNull(prepare.tick(context));
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.STUCK, result.problem().kind());
        assertTrue(result.problem().headline().contains("3600 刻"),
                "收场说明应写明推进到的刻数：" + result.problem().headline());
    }

    @Test
    void interruptedTicksDoNotCountTowardTheCap() {
        PrepareBedTask prepare = task();
        TickContext context = tick();
        prepare.start(context);
        // 先推进 1000 刻。
        for (int i = 0; i < 1000; i++) {
            assertNull(finishedOrNull(prepare.tick(context)));
        }
        // 插曲：被自卫、吃饭打断——这段时间墙钟在走、世界照常转，但备床不被推进，计时器一格不涨。
        prepare.pause();
        // 打断期间不调 tick：模拟任务没被推进的那些刻（墙上时间照走，计时器一格不涨），之后回来接着做。
        // 回来后再推进 2599 刻（合计 3599 推进刻）：还差一刻，不该收场——插曲的刻没有计入。
        for (int i = 0; i < 2599; i++) {
            assertNull(finishedOrNull(prepare.tick(context)),
                    "插曲不计入，此刻只推进了 1000~3599 刻，不该收场");
        }
        // 第 3600 推进刻：超时收场。
        TaskResult result = finishedOrNull(prepare.tick(context));
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertTrue(result.problem().headline().contains("3600 刻"),
                "收场说明应写明推进到的刻数：" + result.problem().headline());
    }
}
