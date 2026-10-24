// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.planning;

import java.util.OptionalInt;
import java.util.function.IntPredicate;

import org.maiwithu.maicraft.behavior.explore.ColumnSample;
import org.maiwithu.maicraft.behavior.explore.OpenSky;

/**
 * 一柱里的落点挑选（纯函数）：只给了 x/z 的目的地要落到一个真能站的脚位，
 * 不是"顶上有个方块就站那儿"——水面列的顶格在水面上方悬空，封顶建筑的顶格在屋顶，
 * 都不是人指的那层。站不站得住由调用方给的判断回答（干地可站是
 * {@code BlockPassability} 里那一份事实，这里不另写站得住的规矩）；
 * 头顶有没有遮挡按探索逐列样本与露天判断回答，不重写第二份顶盖扫描。
 *
 * <p>挑法：从顶盖往下扫一段，收露天可站的脚位与被遮住的可站脚位。
 * 人待在盖了顶的空间里时（室内的人指室内），先取离人所在层最近的那格遮住的脚位——
 * 不一路找到天；其余取最高的露天可站脚位（露天的"去那儿"指的是地面上）。
 * 都没有（整柱是水、整柱悬空）给空，调用方退回"先走到那一柱列"。
 */
public final class ColumnLanding {

    private ColumnLanding() {}

    /** 从顶盖往下最多扫几格：室内的地板离顶盖就几格，再深的多半是洞穴，不顺着扫进去。 */
    public static final int SCAN_DEPTH = 24;

    /** 遮住的脚位离人所在层最多差几格还算"同一层"：再远就不是人待的那个空间了。 */
    public static final int LAYER_BAND = 12;

    /**
     * 在一柱里挑能站的落点。
     *
     * @param column           这一柱的顶盖样本；未加载给空
     * @param standable        脚在这个高度站不站得住（干地可站）；按调用方那一份事实判
     * @param playerFeetY      人此刻脚的高度：遮住的脚位按离它近近来挑
     * @param playerUnderCover 人此刻是不是待在盖了顶的空间里（自己那列头顶有遮挡）
     */
    public static OptionalInt feet(ColumnSample column, IntPredicate standable,
            int playerFeetY, boolean playerUnderCover) {
        if (!column.loaded()) {
            return OptionalInt.empty();
        }
        OptionalInt open = OptionalInt.empty();
        OptionalInt covered = OptionalInt.empty();
        int top = column.topY();
        for (int y = top + 1; y > top - SCAN_DEPTH; y--) {
            if (!standable.test(y)) {
                continue;
            }
            if (OpenSky.judge(column, y) instanceof OpenSky.Verdict.Open) {
                // 从上往下扫，第一个露天的就是最高的地表。
                if (open.isEmpty()) {
                    open = OptionalInt.of(y);
                }
            } else if (covered.isEmpty()
                    || Math.abs(y - playerFeetY) < Math.abs(covered.getAsInt() - playerFeetY)) {
                covered = OptionalInt.of(y);
            }
        }
        // 室内的人指室内：同层的遮住脚位比屋顶还近才取它，不把落点放到天上去；
        // 露天地表更近时（人在屋里指着外面的地面）照旧落地表，不顺着扫进洞穴里。
        if (playerUnderCover && covered.isPresent()
                && Math.abs(covered.getAsInt() - playerFeetY) <= LAYER_BAND
                && (open.isEmpty()
                        || Math.abs(covered.getAsInt() - playerFeetY) < Math.abs(open.getAsInt() - playerFeetY))) {
            return covered;
        }
        return open;
    }
}
