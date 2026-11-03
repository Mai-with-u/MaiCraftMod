// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.gomoku.spi.GomokuBoards;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 联动模组交来的下五子棋的办法，登记表给它包的一层：模组停用后认不出棋盘（能力按"联动没登记"说）；
 * 下到一半停用或碰到模组接口对不上，按不支持收场，已经落下的照样交代。
 */
final class CompatGomokuBoards implements GomokuBoards {

    private final CompatModule module;
    private final GomokuBoards boards;

    CompatGomokuBoards(CompatModule module, GomokuBoards boards) {
        this.module = Objects.requireNonNull(module, "module");
        this.boards = Objects.requireNonNull(boards, "boards");
    }

    @Override public Optional<Board> board(PlayerContext context, BlockPos at) {
        if (!module.active()) return Optional.empty();
        try {
            return boards.board(context, at).map(Guarded::new);
        } catch (ModApiMismatch broken) {
            return Optional.empty();
        }
    }

    /** 包一层的棋盘：开始下时对不上，第一刻按不支持收场。 */
    private final class Guarded implements Board {

        private final Board board;

        Guarded(Board board) {
            this.board = board;
        }

        @Override public String name() {
            return board.name();
        }

        @Override public Play move(String move, int maxSeconds, Permissions permissions) {
            try {
                return new GuardedPlay(board.move(move, maxSeconds, permissions));
            } catch (ModApiMismatch broken) {
                return new Broken(broken.getMessage());
            }
        }

        @Override public Play reset(Permissions permissions) {
            try {
                return new GuardedPlay(board.reset(permissions));
            } catch (ModApiMismatch broken) {
                return new Broken(broken.getMessage());
            }
        }
    }

    /** 下棋的动作包一层：推进照转，模组半路停用按不支持收场；结局读的是动作自己记下的。 */
    private final class GuardedPlay implements Play {

        private final Play play;
        private final Action guarded;

        GuardedPlay(Play play) {
            this.play = play;
            this.guarded = CompatAction.of(module, play);
        }

        @Override public ActionStatus tick(TickContext context) {
            return guarded.tick(context);
        }

        @Override public Interruptibility interruptibility() {
            return guarded.interruptibility();
        }

        @Override public Interruptibility interruptibility(TickContext context) {
            return guarded.interruptibility(context);
        }

        @Override public boolean stopSafely() {
            return guarded.stopSafely();
        }

        @Override public boolean stoppedSafely() {
            return guarded.stoppedSafely();
        }

        @Override public void pause() {
            guarded.pause();
        }

        @Override public void close() {
            guarded.close();
        }

        @Override public String describe() {
            return guarded.describe();
        }

        @Override public Played played() {
            return play.played();
        }
    }

    /** 一开始就对不上模组：第一刻就按不支持收场。 */
    private static final class Broken implements Play {

        private final String why;

        Broken(String why) {
            this.why = why;
        }

        @Override public ActionStatus tick(TickContext context) {
            return ActionStatus.failed(Problem.of(Problem.Kind.UNSUPPORTED, why, null));
        }

        @Override public String describe() {
            return "下五子棋（联动对不上）";
        }

        @Override public Played played() {
            return Played.nothing();
        }
    }
}
