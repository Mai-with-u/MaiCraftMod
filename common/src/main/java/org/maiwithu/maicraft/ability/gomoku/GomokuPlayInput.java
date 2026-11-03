// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.gomoku;

import java.util.Objects;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 下五子棋任务的输入。
 *
 * @param seenId     棋盘的观察编号 b#
 * @param reset      重开一局；否则是落一子
 * @param move       这一手下在哪（记谱写法）；重开时为 null
 * @param maxSeconds  落下以后最多等她应手几秒
 * @param permissions 走过去能动多少地形
 */
record GomokuPlayInput(String seenId, boolean reset, String move, int maxSeconds, Permissions permissions)
        implements TaskInput {

    GomokuPlayInput {
        Objects.requireNonNull(seenId, "seenId");
        Objects.requireNonNull(permissions, "permissions");
    }

    @Override public String describe() {
        return reset ? "重开 " + seenId + " 的五子棋" : "在 " + seenId + " 的五子棋盘上下 " + move;
    }
}
