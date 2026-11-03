// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.gomoku;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.gomoku.spi.GomokuBoards;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.param.ParseResult;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 下五子棋的能力：写法不对当场退回；棋盘不在了、不是棋盘、模组装了联动没登记各自如实说；
 * 结论写下在哪、她应在哪、谁赢了、她还没下。
 */
class GomokuPlayAbilityTest {

    /** 刻号由测试推进的本刻上下文。 */
    private static final class TestTick implements TickContext {
        private long gameTick = 1000;

        @Override public long gameTick() {
            return gameTick;
        }

        @Override public PlayerContext player() {
            return null;
        }
    }

    /** 一个棋盘替身：下棋时按给的结局收场，记下每次是怎么下的。 */
    private static final class FakeBoard implements GomokuBoards.Board {
        final List<String> asked = new ArrayList<>();
        GomokuBoards.Played played = new GomokuBoards.Played("H8", "I9", List.of(), "you", null, false,
                List.of(new Change(Change.Kind.BLOCK_CHANGED, "五子棋盘", 1, "在 H8 落了一子")), List.of());

        @Override public String name() {
            return "五子棋盘";
        }

        @Override public GomokuBoards.Play move(String move, int maxSeconds, Permissions permissions) {
            asked.add("move " + move + " " + maxSeconds);
            return play();
        }

        @Override public GomokuBoards.Play reset(Permissions permissions) {
            asked.add("reset");
            return play();
        }

        private GomokuBoards.Play play() {
            return new GomokuBoards.Play() {
                private int ticks;

                @Override public ActionStatus tick(TickContext context) {
                    return ++ticks < 3 ? ActionStatus.running() : ActionStatus.done();
                }

                @Override public String describe() {
                    return "下棋";
                }

                @Override public GomokuBoards.Played played() {
                    return ticks < 3 ? GomokuBoards.Played.nothing() : played;
                }
            };
        }
    }

    private static StepContext step(Goal goal) {
        return new StepContext() {
            @Override public Goal goal() {
                return goal;
            }

            @Override public int stepIndex() {
                return 0;
            }

            @Override public TickContext tick() {
                throw new IllegalStateException("决定阶段不应碰到每刻上下文");
            }
        };
    }

    private static StepDecision decide(FindsBoard boards, String params, Target target) {
        GomokuPlayModule module = new GomokuPlayModule(boards);
        ParseResult parsed = module.spec().paramSpecs().parse(JsonParser.parseString(params).getAsJsonObject());
        assertTrue(parsed.ok(), "参数应能解析：" + parsed.errors());
        Goal goal = new Goal("maicraft:gomoku_play", null, target, parsed.params(), Permissions.DEFAULT, List.of(),
                null);
        return module.decide(step(goal));
    }

    /** 照真实路径开一条任务：决定阶段出输入，任务工厂造任务，推进到出结果。 */
    private static TaskResult run(FindsBoard boards, String params) {
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class, decide(boards, params, new Target.Seen("b4")));
        GomokuPlayTask task = new GomokuPlayTask(assertInstanceOf(GomokuPlayInput.class, run.input()), boards);
        TestTick tick = new TestTick();
        task.start(tick);
        for (int i = 0; i < 500; i++) {
            if (task.tick(tick) instanceof TickResult.Finished finished) return finished.result();
            tick.gameTick++;
        }
        throw new AssertionError("下棋任务没有按时收场");
    }

    private static Problem refused(StepDecision decision) {
        return assertInstanceOf(StepDecision.Finish.class, decision).result().problem();
    }

    @Test
    void 目标不是方块编号_当场退回() {
        Problem problem = refused(decide(id -> new FindsBoard.Found.Gone(), "{\"move\":\"H8\"}", new Target.Seen("e4")));
        assertEquals(Problem.Kind.INVALID_PARAMETER, problem.kind());
    }

    @Test
    void 落子没给move_当场退回() {
        Problem problem = refused(decide(id -> new FindsBoard.Found.Gone(), "{}", new Target.Seen("b4")));
        assertTrue(problem.message().contains("move"));
    }

    @Test
    void 棋盘不在了_按目标没了() {
        assertEquals(Problem.Kind.TARGET_GONE, run(id -> new FindsBoard.Found.Gone(), "{\"move\":\"H8\"}")
                .problem().kind());
    }

    @Test
    void 不是棋盘_按做不到() {
        TaskResult result = run(id -> new FindsBoard.Found.NotBoard("minecraft:chest", "minecraft", null),
                "{\"move\":\"H8\"}");
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, result.problem().kind());
    }

    @Test
    void 模组装了联动没登记_按不支持() {
        TaskResult result = run(id -> new FindsBoard.Found.NotBoard("touhou_little_maid:gomoku", "touhou_little_maid",
                "装的是 1.6.0，验证过的范围是 1.5.3 到 1.5.4，不登记"), "{\"move\":\"H8\"}");
        assertEquals(Problem.Kind.UNSUPPORTED, result.problem().kind());
    }

    @Test
    void 落子_默认等15秒_结论写下在哪她应在哪() {
        FakeBoard board = new FakeBoard();
        TaskResult result = run(id -> new FindsBoard.Found.Board(board), "{\"move\":\" h8 \"}");
        assertEquals(List.of("move h8 15"), board.asked);
        assertEquals("下在 H8，她应在 I9", result.summary());
        assertEquals(1, result.changes().size());
        GomokuPlayTask.GomokuDetails details = assertInstanceOf(GomokuPlayTask.GomokuDetails.class, result.details());
        assertEquals("b4", details.boardId());
        assertNull(details.replyPending());
    }

    @Test
    void 她还没下_结论写等了几秒() {
        FakeBoard board = new FakeBoard();
        board.played = new GomokuBoards.Played("H8", null, List.of(), "maid", null, true, List.of(), List.of());
        TaskResult result = run(id -> new FindsBoard.Found.Board(board), "{\"move\":\"H8\",\"max_seconds\":5}");
        assertEquals("下在 H8，等了 5 秒她还没下", result.summary());
    }

    @Test
    void 这一手赢了_结论写你赢了() {
        FakeBoard board = new FakeBoard();
        board.played = new GomokuBoards.Played("H8", null, List.of(), "over", "you", false, List.of(), List.of());
        assertEquals("下在 H8，你赢了", run(id -> new FindsBoard.Found.Board(board), "{\"move\":\"H8\"}").summary());
    }

    @Test
    void 重开_不用给move() {
        FakeBoard board = new FakeBoard();
        TaskResult result = run(id -> new FindsBoard.Found.Board(board), "{\"operation\":\"reset\"}");
        assertEquals(List.of("reset"), board.asked);
        assertEquals("重开了一局", result.summary());
    }
}
