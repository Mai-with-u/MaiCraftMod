// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.gomoku;

import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.ability.gomoku.spi.GomokuBoards;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 下一手五子棋：找到棋盘 → 交给联动的办法落子并等她应手（或重开一局）。
 * 不是棋盘按做不到，它所属的模组装了联动没登记按不支持；等她应手没等到不算失败，结果写明她还没下。
 */
final class GomokuPlayTask extends PhasedTask<GomokuPlayTask.Phase> {

    /** 下棋的阶段：找棋盘 → 下。 */
    enum Phase { FIND, PLAY }

    /** 多久没有真实进展算卡住：走过去、点一下、等她最多 30 秒，一分钟足够。 */
    private static final long STUCK_AFTER_TICKS = 20L * 60;

    private final GomokuPlayInput input;
    private final FindsBoard boards;
    private GomokuBoards.Board board;
    private GomokuBoards.Play play;

    GomokuPlayTask(GomokuPlayInput input, FindsBoard boards) {
        super("下五子棋", Phase.FIND, new ProgressTracker(STUCK_AFTER_TICKS, Long.MAX_VALUE));
        this.input = Objects.requireNonNull(input, "input");
        this.boards = Objects.requireNonNull(boards, "boards");
    }

    /**
     * 下棋的结果细节。
     *
     * @param boardId      棋盘的观察编号
     * @param yourMove     你这一手下在哪；没落下不给
     * @param maidMove     她应在哪；没应不给
     * @param board        此刻的棋盘：15 行，X 你、O 她、. 空，第一行是第 15 路
     * @param turn         此刻轮到谁：you / maid / over
     * @param winner       结束时谁赢：you / maid / draw
     * @param replyPending 落下了但等满没看到她应手
     */
    record GomokuDetails(String boardId, String yourMove, String maidMove, List<String> board, String turn,
                         String winner, Boolean replyPending) implements ResultDetails {}

    @Override protected Action enter(Phase phase) {
        if (phase != Phase.PLAY) return null;
        play = input.reset() ? board.reset(input.permissions())
                : board.move(input.move(), input.maxSeconds(), input.permissions());
        return play;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case FIND -> find();
            case PLAY -> runActionThen(context, this::finish);
        };
    }

    // 找棋盘：不在了按目标没了；不是棋盘按做不到；模组装了联动没登记按不支持。
    private Next<Phase> find() {
        return switch (boards.find(input.seenId())) {
            case FindsBoard.Found.Gone gone -> Next.fail(Problem.of(Problem.Kind.TARGET_GONE,
                    "观察编号 " + input.seenId() + " 对应的那一格已经不在了", "先 observe 看看棋盘还在不在"));
            case FindsBoard.Found.NotBoard other when other.unregistered() != null -> Next.fail(Problem.of(
                    Problem.Kind.UNSUPPORTED, other.what() + " 是模组 " + other.modId() + " 的方块，这个模组装了，但它的联动没登记（"
                            + other.unregistered() + "），下不了棋", "看启动日志里这个模组的联动结论"));
            case FindsBoard.Found.NotBoard other -> Next.fail(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    other.what() + " 不是五子棋盘", null));
            case FindsBoard.Found.Board found -> {
                board = found.board();
                yield Next.go(Phase.PLAY, "找到" + board.name());
            }
        };
    }

    // 下完：变化照列，没能确认的记进去；结论说下在哪、她应在哪、谁赢了。
    private Next<Phase> finish() {
        GomokuBoards.Played played = play.played();
        played.changes().forEach(this::recordChange);
        played.unconfirmed().forEach(this::recordUnconfirmed);
        return Next.done(TaskResult.done(summary(played)));
    }

    private String summary(GomokuBoards.Played played) {
        if (input.reset()) return played.unconfirmed().isEmpty() ? "重开了一局" : "点了棋盒，没能确认棋盘清空了";
        if (played.yourMove() == null) return "在 " + input.move() + " 右键了，没能确认落下";
        StringBuilder summary = new StringBuilder("下在 " + played.yourMove());
        if (played.maidMove() != null) summary.append("，她应在 ").append(played.maidMove());
        if (played.winner() != null) {
            summary.append(switch (played.winner()) {
                case "you" -> "，你赢了";
                case "maid" -> "，她赢了";
                default -> "，和棋";
            });
        } else if (played.replyPending()) {
            summary.append("，等了 ").append(input.maxSeconds()).append(" 秒她还没下");
        }
        return summary.toString();
    }

    @Override protected ResultDetails details() {
        GomokuBoards.Played played = play == null ? GomokuBoards.Played.nothing() : play.played();
        return new GomokuDetails(input.seenId(), played.yourMove(), played.maidMove(),
                played.board().isEmpty() ? null : played.board(), played.turn(), played.winner(),
                played.replyPending() ? Boolean.TRUE : null);
    }

    @Override protected String describePhase(Phase value) {
        return switch (value) {
            case FIND -> "认这是不是棋盘";
            case PLAY -> input.reset() ? "重开一局" : "落子并等她应手";
        };
    }
}
