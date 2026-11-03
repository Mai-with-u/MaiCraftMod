// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.touhoulittlemaid;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.ability.gomoku.spi.GomokuBoards;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.HandThenClick;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 和女仆下车万女仆的五子棋：角色执黑先手，空手对准那一路交叉点右键落子，再站着等她应手；
 * 她那一手由下棋的人自己的客户端算好、停一会儿再发给服务器，角色的客户端装着车万女仆，会自动替她应答。
 *
 * <p>落子：这一局已经结束、那一路已有子当场交回（棋盘上看得见）；轮到她时先等她下完。
 * 右键以后那一路变成你的子才算落下；没变时这段时间聊天栏有回应（座位上没有女仆、不是你的女仆）按游戏拒绝附原话，
 * 没有就记进没能确认。重开：对准棋盘角上的棋盒右键，棋盘清空才算做成。
 */
final class MaidGomoku implements GomokuBoards {

    private final TouhouLittleMaidCompat compat;
    private final PlayerServices services;
    private final Clicks clicks;

    MaidGomoku(TouhouLittleMaidCompat compat, PlayerServices services) {
        this(compat, services, new LiveClicks(services));
    }

    MaidGomoku(TouhouLittleMaidCompat compat, PlayerServices services, Clicks clicks) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.clicks = Objects.requireNonNull(clicks, "clicks");
    }

    /** 走过去、空手对准那一小块右键、等确认的那一下；生产实现走"备好手再点"，测试用替身。 */
    interface Clicks {
        Action click(String what, GomokuNotation.Spot spot, Permissions permissions,
                InteractionConfirmation confirmation);
    }

    @Override public Optional<Board> board(PlayerContext context, BlockPos at) {
        Level level = context == null ? null : context.level();
        return compat.gomoku(level, at).map(state -> new LiveBoard(level, state.center()));
    }

    /** 一个棋盘：按中心那一格认，每刻重读。 */
    private final class LiveBoard implements Board {

        private final Level level;
        private final BlockPos center;

        LiveBoard(Level level, BlockPos center) {
            this.level = level;
            this.center = center;
        }

        @Override public String name() {
            return "五子棋盘";
        }

        @Override public Play move(String move, int maxSeconds, Permissions permissions) {
            return new Moving(level, center, move, maxSeconds * 20, permissions);
        }

        @Override public Play reset(Permissions permissions) {
            return new Resetting(level, center, permissions);
        }
    }

    /** 落子与重开共用的部分：每刻重读棋盘、右键那一下没成时看聊天栏、记下变化与结局。 */
    private abstract class Playing implements Play {

        final Level level;
        final BlockPos center;
        final Permissions permissions;
        final List<Change> changes = new ArrayList<>();
        final List<Change> unconfirmed = new ArrayList<>();
        GomokuState last;
        Action current;
        long chatMark;
        boolean finished;
        String yourMove;
        String maidMove;
        boolean replyPending;

        Playing(Level level, BlockPos center, Permissions permissions) {
            this.level = level;
            this.center = center;
            this.permissions = permissions;
        }

        @Override public final ActionStatus tick(TickContext context) {
            if (finished) return ActionStatus.done();
            Optional<GomokuState> read = compat.gomoku(level, center);
            if (read.isEmpty()) {
                return fail(Problem.of(Problem.Kind.TARGET_GONE, "棋盘不在了（被收起来了，或离开了加载范围）", null));
            }
            last = read.get();
            return step(context);
        }

        abstract ActionStatus step(TickContext context);

        // 出手：记下聊天栏读到哪，交给点击动作。
        Action click(String what, GomokuNotation.Spot spot, InteractionConfirmation confirmation) {
            chatMark = services.refusals().chatMark();
            return clicks.click(what, spot, permissions, confirmation);
        }

        // 右键那一下没成：聊天栏有回应就是游戏不让（附原话）；等满没变记进没能确认；走不到、点不中照原因交回。
        ActionStatus clickFailed(Problem problem, String doing) {
            List<String> notices = services.refusals().systemMessagesSince(chatMark);
            if (!notices.isEmpty()) {
                return fail(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                        doing + "没成，游戏回了：" + String.join(" / ", notices), null));
            }
            if (problem.kind() == Problem.Kind.STUCK) {
                unconfirmed.add(new Change(Change.Kind.OTHER, "五子棋盘", 1, doing + "：右键了，没看到棋盘变化"));
                finished = true;
                return ActionStatus.done();
            }
            return fail(problem);
        }

        ActionStatus fail(Problem problem) {
            if (current != null) current.close();
            current = null;
            finished = true;
            return ActionStatus.failed(problem);
        }

        ActionStatus finish() {
            finished = true;
            return ActionStatus.done();
        }

        @Override public void pause() {
            // 被打断：这一下右键放下，回来先看棋盘再决定点不点，不重复落子。
            if (current != null) current.close();
            current = null;
        }

        @Override public void close() {
            if (current != null) current.close();
            current = null;
        }

        @Override public Played played() {
            if (last == null) return new Played(yourMove, maidMove, List.of(), null, null, replyPending, changes,
                    unconfirmed);
            return new Played(yourMove, maidMove, GomokuNotation.rows(last), last.turn(), last.winner().orElse(null),
                    replyPending, changes, unconfirmed);
        }
    }

    /** 落一子并等她应手。 */
    private final class Moving extends Playing {

        private enum Stage { CHECK, WAIT_TURN, CLICK, WAIT_REPLY }

        private final String move;
        private final int maxTicks;
        private Stage stage = Stage.CHECK;
        private GomokuNotation.Point point;
        private int waited;
        private int movesAfterMine;

        Moving(Level level, BlockPos center, String move, int maxTicks, Permissions permissions) {
            super(level, center, permissions);
            this.move = move;
            this.maxTicks = maxTicks;
        }

        @Override ActionStatus step(TickContext context) {
            return switch (stage) {
                case CHECK -> check();
                case WAIT_TURN -> waitTurn();
                case CLICK -> place(context);
                case WAIT_REPLY -> waitReply();
            };
        }

        // 核对这一手：写法、这一局结束没有、那一路空不空、轮没轮到你；轮到她先等她下完。
        private ActionStatus check() {
            if (point == null) {
                Optional<GomokuNotation.Point> parsed = GomokuNotation.parse(move, last.facing());
                if (parsed.isEmpty()) {
                    return fail(Problem.of(Problem.Kind.INVALID_PARAMETER, "记谱写法不对：" + move,
                            "列 A–O（站在你这一侧从左到右）加行 1–15（离你最近的是 1），例如 H8"));
                }
                point = parsed.get();
            }
            if (last.ending() != GomokuState.Ending.NOT_OVER) return over();
            int stone = last.stone(point.x(), point.y());
            if (stone != GomokuState.EMPTY) {
                return fail(Problem.of(Problem.Kind.INVALID_PARAMETER,
                        written() + " 已经有" + (stone == GomokuState.YOURS ? "你的" : "她的") + "子了",
                        "observe(detail) 看棋盘的 board，挑一路空着的"));
            }
            if (!last.yourTurn()) {
                if (stage == Stage.WAIT_TURN || maxTicks == 0) return herTurnTooLong();
                stage = Stage.WAIT_TURN;
                waited = 0;
                return ActionStatus.progressed();
            }
            stage = Stage.CLICK;
            return ActionStatus.progressed();
        }

        // 轮到她：真人会等她下完再落子；等满还没下就这一手不下。
        private ActionStatus waitTurn() {
            if (last.ending() != GomokuState.Ending.NOT_OVER) return over();
            if (last.yourTurn()) return check();
            if (++waited > maxTicks) return herTurnTooLong();
            return ActionStatus.running();
        }

        private ActionStatus herTurnTooLong() {
            return fail(Problem.of(Problem.Kind.WRONG_TIME,
                    "轮到她下，等了 " + maxTicks / 20 + " 秒她还没下，这一手没落",
                    "observe 看看她还坐不坐在棋盘边；她起身了，下一手前要她重新坐回来"));
        }

        private ActionStatus over() {
            String winner = last.winner().orElse("draw");
            String how = switch (winner) {
                case "you" -> "你赢了";
                case "maid" -> "她赢了";
                default -> "和棋";
            };
            return fail(Problem.of(Problem.Kind.WRONG_TIME, "这一局已经结束（" + how + "），棋盘不再收子",
                    "先 operation=reset 重开一局"));
        }

        // 落子：空手对准那一路右键，那一路变成你的子才算落下。被打断回来时那一路已经是你的子，就是上一下已经落下了。
        private ActionStatus place(TickContext context) {
            if (current == null) {
                if (last.stone(point.x(), point.y()) == GomokuState.YOURS) return landed();
                int x = point.x();
                int y = point.y();
                current = click("在 " + written() + " 落子", GomokuNotation.spot(center, point),
                        view -> compat.gomoku(level, center).map(state -> state.stone(x, y) == GomokuState.YOURS)
                                .orElse(false) ? InteractionConfirmation.Verdict.APPLIED
                                : InteractionConfirmation.Verdict.PENDING);
            }
            ActionStatus status = current.tick(context);
            if (status instanceof ActionStatus.Done) {
                current = null;
                return landed();
            }
            if (status instanceof ActionStatus.Failed failed) {
                current.close();
                current = null;
                return clickFailed(failed.problem(), "在 " + written() + " 落子");
            }
            return status;
        }

        // 落下了：记一条棋盘变化；这一手就分出胜负或不等她，就此收场，不然站着等她应手。
        private ActionStatus landed() {
            yourMove = written();
            movesAfterMine = last.moves();
            changes.add(new Change(Change.Kind.BLOCK_CHANGED, "五子棋盘", 1, "在 " + yourMove + " 落了一子"));
            if (last.ending() != GomokuState.Ending.NOT_OVER) return finish();
            if (maxTicks == 0) {
                replyPending = true;
                return finish();
            }
            stage = Stage.WAIT_REPLY;
            waited = 0;
            return ActionStatus.progressed();
        }

        // 等她应手：棋盘上多了她的一子，或这一局结束了；等满没应写明她还没下，不算失败。
        private ActionStatus waitReply() {
            if (last.moves() > movesAfterMine && last.lastStone() == GomokuState.HERS) {
                maidMove = GomokuNotation.write(last.lastX(), last.lastY(), last.facing());
                return finish();
            }
            if (last.ending() != GomokuState.Ending.NOT_OVER) return finish();
            if (++waited > maxTicks) {
                replyPending = true;
                return finish();
            }
            return ActionStatus.running();
        }

        private String written() {
            return GomokuNotation.write(point.x(), point.y(), last.facing());
        }

        @Override public void pause() {
            super.pause();
            // 回来先重新核对：这段时间她可能已经下了，那一路可能被占了。
            if (stage == Stage.CLICK) stage = Stage.CHECK;
        }

        @Override public String describe() {
            return switch (stage) {
                case CHECK -> "核对这一手 " + move;
                case WAIT_TURN -> "等她下完上一手";
                case CLICK -> "在 " + move + " 落子";
                case WAIT_REPLY -> "等她应手";
            };
        }
    }

    /** 点棋盒重开一局。 */
    private final class Resetting extends Playing {

        private boolean clicked;

        Resetting(Level level, BlockPos center, Permissions permissions) {
            super(level, center, permissions);
        }

        // 棋盘已经是空的、还在下：本来就是新的一局（或刚才被打断前已经清空了），不再点。
        @Override ActionStatus step(TickContext context) {
            boolean fresh = last.moves() == 0 && last.ending() == GomokuState.Ending.NOT_OVER;
            if (current == null && fresh) {
                if (clicked) changes.add(new Change(Change.Kind.BLOCK_CHANGED, "五子棋盘", 1, "重开了一局，棋盘清空了"));
                return finish();
            }
            if (current == null) {
                current = click("点棋盒重开一局",
                        GomokuNotation.chessBoxes(center, last.facing(), playerPosition()).getFirst(),
                        view -> compat.gomoku(level, center).map(state -> state.moves() == 0).orElse(false)
                                ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING);
            }
            ActionStatus status = current.tick(context);
            if (status instanceof ActionStatus.Done) {
                current = null;
                clicked = true;
                changes.add(new Change(Change.Kind.BLOCK_CHANGED, "五子棋盘", 1, "重开了一局，棋盘清空了"));
                return finish();
            }
            if (status instanceof ActionStatus.Failed failed) {
                current.close();
                current = null;
                return clickFailed(failed.problem(), "点棋盒重开");
            }
            return status;
        }

        // 角色此刻的位置：两个棋盒挑近的那个；不在世界里时不挑。
        private Vec3 playerPosition() {
            PlayerContext player = services.context().get();
            return player == null || player.localPlayer() == null ? null : player.localPlayer().position();
        }

        @Override public String describe() {
            return "点棋盒重开一局";
        }
    }

    /** 生产实现：先腾空手（下棋必须空手），走到够得着那一小块的地方，对准右键，等确认。 */
    private static final class LiveClicks implements Clicks {

        private final PlayerServices services;

        LiveClicks(PlayerServices services) {
            this.services = services;
        }

        @Override public Action click(String what, GomokuNotation.Spot spot, Permissions permissions,
                InteractionConfirmation confirmation) {
            return new HandThenClick(services, what, spot.cell(), spot.area(), permissions,
                    () -> handEmpty() ? Optional.empty() : services.toMainhand().emptyMainhand(), () -> confirmation);
        }

        private boolean handEmpty() {
            PlayerContext player = services.context().get();
            return player != null && player.localPlayer() != null && player.localPlayer().getMainHandItem().isEmpty();
        }
    }
}
