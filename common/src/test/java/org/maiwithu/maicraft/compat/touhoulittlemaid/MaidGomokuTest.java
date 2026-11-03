// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.touhoulittlemaid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.gomoku.spi.GomokuBoards;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.kernel.event.ChatEventLog;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 和女仆下五子棋：落下等她应手；写法不对、那一路有子、这一局结束当场交回；轮到她先等；
 * 座位没人按聊天栏回应拒绝；右键了没变化记没能确认；等满没应写明她还没下；一手赢了不再等；重开清盘。
 */
class MaidGomokuTest {

    private final MaidFakes.Reads reads = new MaidFakes.Reads();
    private final ChatEventLog chat = new ChatEventLog();
    private final TouhouLittleMaidCompat compat = new TouhouLittleMaidCompat(reads, new MaidFakes.Actions(reads));
    private final List<GomokuNotation.Spot> clicked = new ArrayList<>();
    /** 右键那一下服务器那边的结果：由测试摆，点下去的第一刻生效。 */
    private Runnable onClick = () -> {};
    /** 每过一刻棋盘怎么变（她应手）：由测试摆。 */
    private Runnable eachTick = () -> {};

    MaidGomokuTest() {
        reads.board = MaidFakes.board(MaidFakes.emptyStones(), true, GomokuState.Ending.NOT_OVER, 0, -1, -1, 0);
    }

    // 点击替身：第一刻出手（服务器照 onClick 结算），之后每刻看确认，十刻没确认按没能确认失败。
    private final MaidGomoku.Clicks clicks = (what, spot, permissions, confirmation) -> new Action() {
        private int ticks;

        @Override public ActionStatus tick(TickContext context) {
            if (ticks++ == 0) {
                clicked.add(spot);
                onClick.run();
                return ActionStatus.progressed();
            }
            if (confirmation.observe(null) == InteractionConfirmation.Verdict.APPLIED) return ActionStatus.done();
            if (ticks > 10) return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, what + "，没能确认", null));
            return ActionStatus.running();
        }

        @Override public String describe() {
            return what;
        }
    };

    private GomokuBoards.Board board() {
        return new MaidGomoku(compat, MaidFakes.services(chat), clicks).board(null, new BlockPos(1, 64, -1))
                .orElseThrow();
    }

    private ActionStatus run(GomokuBoards.Play play) {
        MaidFakes.Tick tick = new MaidFakes.Tick();
        for (int i = 0; i < 2000; i++) {
            ActionStatus status = play.tick(tick);
            if (status instanceof ActionStatus.Done || status instanceof ActionStatus.Failed) return status;
            eachTick.run();
            tick.gameTick++;
        }
        throw new AssertionError("下棋没有按时收场");
    }

    // 把棋盘改成落了一子以后的样子。
    private void put(int x, int y, int stone, boolean yourTurnAfter, GomokuState.Ending ending) {
        GomokuState board = reads.board;
        int[][] stones = board.stones();
        stones[x][y] = stone;
        reads.board = MaidFakes.board(stones, yourTurnAfter, ending, board.moves() + 1, x, y, stone);
    }

    @Test
    void 落下_等她应手() {
        onClick = () -> put(7, 7, GomokuState.YOURS, false, GomokuState.Ending.NOT_OVER);
        int[] ticks = {0};
        eachTick = () -> {
            if (!reads.board.yourTurn() && ++ticks[0] == 5) put(8, 8, GomokuState.HERS, true,
                    GomokuState.Ending.NOT_OVER);
        };
        GomokuBoards.Play play = board().move("h8", 15, Permissions.DEFAULT);
        assertInstanceOf(ActionStatus.Done.class, run(play));
        GomokuBoards.Played played = play.played();
        assertEquals("H8", played.yourMove());
        assertEquals("I9", played.maidMove());
        assertEquals("you", played.turn());
        assertEquals(1, played.changes().size());
        assertEquals(GomokuNotation.spot(new BlockPos(0, 64, 0), new GomokuNotation.Point(7, 7)), clicked.getFirst());
    }

    @Test
    void 写法不对_不点() {
        Problem problem = assertInstanceOf(ActionStatus.Failed.class,
                run(board().move("Z9", 15, Permissions.DEFAULT))).problem();
        assertEquals(Problem.Kind.INVALID_PARAMETER, problem.kind());
        assertTrue(clicked.isEmpty());
    }

    @Test
    void 那一路已有子_不点() {
        put(7, 7, GomokuState.HERS, true, GomokuState.Ending.NOT_OVER);
        Problem problem = assertInstanceOf(ActionStatus.Failed.class,
                run(board().move("H8", 15, Permissions.DEFAULT))).problem();
        assertEquals(Problem.Kind.INVALID_PARAMETER, problem.kind());
        assertTrue(problem.message().contains("她的"));
        assertTrue(clicked.isEmpty());
    }

    @Test
    void 这一局已经结束_先重开() {
        put(0, 0, GomokuState.HERS, true, GomokuState.Ending.WON);
        Problem problem = assertInstanceOf(ActionStatus.Failed.class,
                run(board().move("H8", 15, Permissions.DEFAULT))).problem();
        assertEquals(Problem.Kind.WRONG_TIME, problem.kind());
        assertTrue(problem.message().contains("她赢了"));
        assertTrue(problem.suggestion().contains("reset"));
    }

    @Test
    void 轮到她_先等她下完再落子() {
        put(7, 7, GomokuState.YOURS, false, GomokuState.Ending.NOT_OVER);
        int[] ticks = {0};
        eachTick = () -> {
            if (!reads.board.yourTurn() && ++ticks[0] == 10) put(6, 6, GomokuState.HERS, true,
                    GomokuState.Ending.NOT_OVER);
        };
        onClick = () -> put(8, 8, GomokuState.YOURS, false, GomokuState.Ending.NOT_OVER);
        GomokuBoards.Play play = board().move("I9", 1, Permissions.DEFAULT);
        assertInstanceOf(ActionStatus.Done.class, run(play));
        assertEquals("I9", play.played().yourMove());
        assertEquals(1, clicked.size());
    }

    @Test
    void 轮到她一直不下_这一手不落() {
        put(7, 7, GomokuState.YOURS, false, GomokuState.Ending.NOT_OVER);
        Problem problem = assertInstanceOf(ActionStatus.Failed.class,
                run(board().move("I9", 1, Permissions.DEFAULT))).problem();
        assertEquals(Problem.Kind.WRONG_TIME, problem.kind());
        assertTrue(clicked.isEmpty());
    }

    @Test
    void 座位上没有女仆_按聊天栏原话拒绝() {
        onClick = () -> MaidFakes.systemMessage(chat, "没有女仆坐在棋盘旁");
        Problem problem = assertInstanceOf(ActionStatus.Failed.class,
                run(board().move("H8", 15, Permissions.DEFAULT))).problem();
        assertEquals(Problem.Kind.REFUSED_BY_GAME, problem.kind());
        assertTrue(problem.message().contains("没有女仆坐在棋盘旁"));
    }

    @Test
    void 右键了没变化也没回应_记没能确认() {
        GomokuBoards.Play play = board().move("H8", 15, Permissions.DEFAULT);
        assertInstanceOf(ActionStatus.Done.class, run(play));
        assertNull(play.played().yourMove());
        assertEquals(1, play.played().unconfirmed().size());
    }

    @Test
    void 等满她没应_写明她还没下() {
        onClick = () -> put(7, 7, GomokuState.YOURS, false, GomokuState.Ending.NOT_OVER);
        GomokuBoards.Play play = board().move("H8", 1, Permissions.DEFAULT);
        assertInstanceOf(ActionStatus.Done.class, run(play));
        assertTrue(play.played().replyPending());
        assertNull(play.played().maidMove());
        assertEquals("maid", play.played().turn());
    }

    @Test
    void 这一手赢了_不再等() {
        onClick = () -> put(7, 7, GomokuState.YOURS, true, GomokuState.Ending.WON);
        GomokuBoards.Play play = board().move("H8", 15, Permissions.DEFAULT);
        assertInstanceOf(ActionStatus.Done.class, run(play));
        assertEquals("you", play.played().winner());
        assertEquals("over", play.played().turn());
    }

    @Test
    void 重开_点棋盒清盘() {
        put(7, 7, GomokuState.YOURS, false, GomokuState.Ending.NOT_OVER);
        onClick = () -> reads.board = MaidFakes.board(MaidFakes.emptyStones(), true, GomokuState.Ending.NOT_OVER,
                0, -1, -1, 0);
        GomokuBoards.Play play = board().reset(Permissions.DEFAULT);
        assertInstanceOf(ActionStatus.Done.class, run(play));
        assertEquals(1, clicked.size());
        assertEquals(1, play.played().changes().size());
    }

    @Test
    void 本来就是空棋盘_重开不点() {
        GomokuBoards.Play play = board().reset(Permissions.DEFAULT);
        assertInstanceOf(ActionStatus.Done.class, run(play));
        assertTrue(clicked.isEmpty());
        assertTrue(play.played().changes().isEmpty());
    }

    @Test
    void 棋盘外的格子_不是棋盘() {
        assertTrue(new MaidGomoku(compat, MaidFakes.services(chat), clicks).board(null, new BlockPos(3, 64, 0))
                .isEmpty());
    }
}
