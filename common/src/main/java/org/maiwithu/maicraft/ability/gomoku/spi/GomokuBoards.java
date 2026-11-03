// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.gomoku.spi;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 下五子棋的办法：车万女仆这类模组摆在地上的五子棋盘，角色执黑先手、坐在对面的女仆执白。
 * 下五子棋的能力不认识具体模组，点名一个棋盘时逐个问联动登记的这份办法"这是不是你的棋盘"。
 *
 * <p>分工：能力管认观察编号、认不出时如实说、把参数交过来；实现管记谱写法、那一路有没有子、走过去对准那一路右键、
 * 确认落子、等她应手、重开。
 */
public interface GomokuBoards {

    /** 这一格是不是这个模组的五子棋盘（棋盘上任一格都算）：是就给下棋的办法，不是给空。 */
    Optional<Board> board(PlayerContext context, BlockPos at);

    /** 一个五子棋盘。 */
    interface Board {

        /** 叫什么：写进结果。 */
        String name();

        /**
         * 落一子并等她应手：记谱写法不对、那一路已有子按参数不对；这一局已经结束按时机不对；
         * 轮到她时先等她下完。落下以后最多等 maxSeconds 秒她应手，没等到不算失败。
         */
        Play move(String move, int maxSeconds, Permissions permissions);

        /** 重开一局：对准棋盒右键，棋盘清空才算做成；本来就是空棋盘时直接完成。 */
        Play reset(Permissions permissions);
    }

    /** 下棋的动作；做完、失败、被取消都读得到这一趟的结局。 */
    interface Play extends Action {

        /** 到这一刻为止的结局。 */
        Played played();
    }

    /**
     * 下棋的结局。
     *
     * @param yourMove     你这一手下在哪（记谱写法）；没落下为 null
     * @param maidMove     她应在哪；没应为 null
     * @param board        此刻的棋盘：15 行，每行 15 个字符，X 你、O 她、. 空，第一行是第 15 路
     * @param turn         此刻轮到谁：you / maid / over
     * @param winner       结束时谁赢：you / maid / draw；没结束为 null
     * @param replyPending 落下了但等满没看到她应手
     * @param changes      棋盘上的变化
     * @param unconfirmed  右键了却没看到棋盘变化的那一下
     */
    record Played(String yourMove, String maidMove, List<String> board, String turn, String winner,
                  boolean replyPending, List<Change> changes, List<Change> unconfirmed) {

        public Played {
            board = List.copyOf(board);
            changes = List.copyOf(changes);
            unconfirmed = List.copyOf(unconfirmed);
        }

        /** 什么都还没做。 */
        public static Played nothing() {
            return new Played(null, null, List.of(), null, null, false, List.of(), List.of());
        }
    }
}
