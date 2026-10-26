// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 连一条链的整个过程：连之前先清残留的起点，点过第一台没连成再清一次，清掉记进变化，清不掉如实写。 */
class ChainLinkRunTest {

    private static final BlockPos FIRST = new BlockPos(0, 64, 0);

    /** 清起点：Create 说清掉了。 */
    private static final ActionStatus CLEARED = ActionStatus.done();
    /** 清起点：按了没反应，手上本来没记着起点。 */
    private static final ActionStatus NOTHING = ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME,
            "按了一下使用键，游戏没有反应"));
    /** 清起点：身上没有锁链，按不成。 */
    private static final ActionStatus NO_CHAIN = ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM,
            "身上没有锁链：Create 只认手持锁链按使用键清起点"));

    @Test
    void 连之前清掉了上一回的残留_记进变化_接着连成() {
        Rig rig = new Rig(ActionStatus.done(), CLEARED);
        ActionStatus end = rig.run(clicked -> once(() -> {
            clicked.run();
            return ActionStatus.done();
        }));
        assertTrue(end instanceof ActionStatus.Done, end.toString());
        assertEquals(1, rig.clears, "连成了只在连之前清一次");
        List<Change> changes = rig.run.changes();
        assertEquals(1, changes.size());
        assertEquals(Change.Kind.OTHER, changes.getFirst().kind());
        assertTrue(changes.getFirst().note().contains("连之前"), changes.getFirst().note());
    }

    @Test
    void 点过第一台没连成_再清一次起点_清掉了记进变化() {
        Rig rig = new Rig(ActionStatus.done(), NOTHING, CLEARED);
        ActionStatus end = rig.run(clicked -> once(() -> {
            clicked.run();
            return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "第二下没连上"));
        }));
        Problem problem = ((ActionStatus.Failed) end).problem();
        assertEquals(Problem.Kind.STUCK, problem.kind(), "没连成的原因照原样，后面接上清起点的结论");
        assertTrue(problem.message().startsWith("第二下没连上"), problem.message());
        assertTrue(problem.message().contains("已清掉手上锁链记着的接线起点"), problem.message());
        assertEquals(2, rig.clears);
        assertEquals(1, rig.run.changes().size(), "连之前没有可清的不记，没连成清掉的记一笔");
        assertTrue(rig.run.changes().getFirst().note().contains("没连成"));
    }

    @Test
    void 没连成又清不掉_如实写手上的锁链还带着起点() {
        Rig rig = new Rig(ActionStatus.done(), NOTHING, NO_CHAIN);
        ActionStatus end = rig.run(clicked -> once(() -> {
            clicked.run();
            return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE, "第二台够不着"));
        }));
        String message = ((ActionStatus.Failed) end).problem().message();
        assertTrue(message.contains("清不掉"), message);
        assertTrue(message.contains("身上没有锁链"), message);
        assertTrue(message.contains("手上的锁链还带着它"), message);
        assertTrue(message.contains(FIRST.toShortString()), "告诉 LLM 起点多半记的是哪一台：" + message);
        assertTrue(rig.run.changes().isEmpty(), "没清掉就不记变化");
    }

    @Test
    void 第一台还没点上就失败_不再清() {
        Rig rig = new Rig(ActionStatus.done(), NOTHING);
        ActionStatus end = rig.run(clicked -> once(() -> ActionStatus.failed(
                Problem.of(Problem.Kind.UNREACHABLE, "第一台够不着"))));
        String message = ((ActionStatus.Failed) end).problem().message();
        assertEquals("第一台够不着", message, "一下都没点上，Create 没记新起点，原因照原样交回");
        assertEquals(1, rig.clears);
    }

    @Test
    void 拿不到锁链_不清也不连() {
        Rig rig = new Rig(ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "锁链不够")));
        boolean[] linked = {false};
        ActionStatus end = rig.run(clicked -> {
            linked[0] = true;
            return once(ActionStatus::done);
        });
        assertEquals(Problem.Kind.NEED_ITEM, ((ActionStatus.Failed) end).problem().kind());
        assertEquals(0, rig.clears);
        assertFalse(linked[0]);
    }

    @Test
    void 清起点的结论按动作收场的样子认() {
        assertEquals(ChainLinkRun.Cleared.CLEARED, ChainLinkRun.cleared(CLEARED));
        assertEquals(ChainLinkRun.Cleared.NOTHING_TO_CLEAR, ChainLinkRun.cleared(NOTHING));
        assertEquals(ChainLinkRun.Cleared.COULD_NOT, ChainLinkRun.cleared(NO_CHAIN));
    }

    /** 一刻按给定结论收场的动作。 */
    private static Action once(Supplier<ActionStatus> end) {
        return new Action() {
            @Override public ActionStatus tick(TickContext context) {
                return end.get();
            }

            @Override public String describe() {
                return "替身动作";
            }
        };
    }

    /** 拿锁链按给定结论收场，清起点每次按脚本弹一个结论，数一数清了几次。 */
    private static final class Rig {
        private final ActionStatus fetched;
        private final Deque<ActionStatus> clearings;
        int clears;
        ChainLinkRun run;

        Rig(ActionStatus fetched, ActionStatus... clearings) {
            this.fetched = fetched;
            this.clearings = new ArrayDeque<>(List.of(clearings));
        }

        ActionStatus run(Function<Runnable, Action> linking) {
            run = new ChainLinkRun("连链条", FIRST, () -> once(() -> fetched), () -> {
                clears++;
                ActionStatus result = clearings.poll();
                return once(() -> result);
            }, linking);
            TickContext tick = new TickContext() {
                @Override public long gameTick() {
                    return 0;
                }

                @Override public PlayerContext player() {
                    return null;
                }
            };
            for (int i = 0; i < 20; i++) {
                ActionStatus status = run.tick(tick);
                if (!(status instanceof ActionStatus.Running)) return status;
            }
            throw new AssertionError("二十刻都没收场");
        }
    }
}
