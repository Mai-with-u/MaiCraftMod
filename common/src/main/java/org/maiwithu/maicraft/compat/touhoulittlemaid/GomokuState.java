// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.touhoulittlemaid;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 一个五子棋盘此刻的样子，读自棋盘中心那一格同步下来的方块实体与方块状态。
 * 棋盘数组 stones[x][y] 的 x 沿世界 +X、y 沿世界 +Z，与棋盘朝向无关；下棋的人执黑（1），女仆执白（2）。
 *
 * @param center     棋盘中心那一格
 * @param facing     棋盘的朝向（方块状态里的 FACING）：她的座位在它顺时针转一格的方向
 * @param stones     15×15 的棋子：0 空、1 黑（你）、2 白（她）
 * @param yourTurn   轮到下棋的人（不是轮到她）
 * @param ending     这一局怎么样了：还没结束 / 有人赢了 / 和棋
 * @param moves      已经下了几手
 * @param lastX      最后一手的 x；还没下为 -1
 * @param lastY      最后一手的 y；还没下为 -1
 * @param lastStone  最后一手是谁下的：1 黑、2 白；还没下为 0
 * @param maidSeated 她的座位上坐着女仆
 */
public record GomokuState(BlockPos center, Direction facing, int[][] stones, boolean yourTurn, Ending ending,
                          int moves, int lastX, int lastY, int lastStone, boolean maidSeated) {

    /** 棋盘一边几路。 */
    static final int SIZE = 15;
    static final int EMPTY = 0;
    static final int YOURS = 1;
    static final int HERS = 2;

    /** 这一局怎么样了：还没结束、有人赢了、和棋。 */
    public enum Ending { NOT_OVER, WON, DRAW }

    public GomokuState {
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(facing, "facing");
        Objects.requireNonNull(stones, "stones");
        Objects.requireNonNull(ending, "ending");
        center = center.immutable();
    }

    /** 这一路上是什么子。 */
    int stone(int x, int y) {
        return stones[x][y];
    }

    /** 此刻轮到谁：you / maid / over。 */
    String turn() {
        if (ending != Ending.NOT_OVER) return "over";
        return yourTurn ? "you" : "maid";
    }

    /** 结束时谁赢：车万女仆只记"有人赢了"，谁赢看最后一手是谁下的；和棋为 draw；没结束给空。 */
    Optional<String> winner() {
        return switch (ending) {
            case NOT_OVER -> Optional.empty();
            case DRAW -> Optional.of("draw");
            case WON -> Optional.of(lastStone == HERS ? "maid" : "you");
        };
    }

    /** 最后一手的记谱写法；还没下给空。 */
    Optional<String> lastMove() {
        if (moves == 0 || lastX < 0 || lastY < 0) return Optional.empty();
        return Optional.of(GomokuNotation.write(lastX, lastY, facing));
    }
}
