// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.touhoulittlemaid;

/**
 * 好感分档：车万女仆的好感点数满 64、192、384 各升一级（0–3 级），界面上的爱心数就是它。
 *
 * @param level  等级 0–3
 * @param points 好感点数
 * @param toNext 离下一级还差几点；满级为 null
 */
public record Affection(int level, int points, Integer toNext) {

    /** 各级的门槛点数：1 级 64、2 级 192、3 级 384。 */
    private static final int[] THRESHOLDS = {64, 192, 384};

    /** 按点数分档。 */
    public static Affection of(int points) {
        int level = 0;
        while (level < THRESHOLDS.length && points >= THRESHOLDS[level]) level++;
        Integer toNext = level < THRESHOLDS.length ? THRESHOLDS[level] - points : null;
        return new Affection(level, points, toNext);
    }
}
