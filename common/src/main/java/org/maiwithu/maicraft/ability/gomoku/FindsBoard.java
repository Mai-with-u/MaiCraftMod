// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.gomoku;

import org.maiwithu.maicraft.ability.gomoku.spi.GomokuBoards;

/**
 * 点名一个棋盘时找它：观察编号换回那一格，逐个问联动登记的下五子棋的办法它是不是棋盘。
 * 生产实现读场景与世界，测试用替身。
 */
public interface FindsBoard {

    /** 观察编号 b# 指的那一格此刻是不是五子棋盘。 */
    Found find(String seenId);

    /** 找的结果。 */
    sealed interface Found {

        /** 是棋盘：交给这份办法。 */
        record Board(GomokuBoards.Board board) implements Found {}

        /** 编号对应的那一格已经不在了（没登记过、被拆了）。 */
        record Gone() implements Found {}

        /**
         * 不是棋盘。
         *
         * @param what         它是什么方块
         * @param modId        它所属的模组（方块 ID 的命名空间）
         * @param unregistered 这个模组装了、联动却没登记上时为什么；不是这种情况为 null
         */
        record NotBoard(String what, String modId, String unregistered) implements Found {}
    }
}
