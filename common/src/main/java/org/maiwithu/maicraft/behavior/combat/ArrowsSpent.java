// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import java.util.OptionalInt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 这一仗箭少了几支：开打时背包里箭的只数减去最近一刻的只数，路上捡回的箭已经抵掉。
 * 射出几箭（游戏确认了放箭的那几下）另有一本，两个数一起写进结果：取消拉弓时被原版松手放出去的、
 * 无限附魔不耗箭的、联动远程武器不用箭的，都只会让这两个数不一样，不会让哪一个失实。
 * 点名战斗与自卫共用；读不到背包（没接上角色）时不给数。
 */
public final class ArrowsSpent {

    private static final Logger LOG = LoggerFactory.getLogger(ArrowsSpent.class);

    private int atStart = -1;
    private int latest = -1;

    /** 每刻记一笔此刻背包里的箭数；第一笔当作开打时的。读不到时这一刻不记。 */
    public void note(OptionalInt carried) {
        if (carried.isEmpty()) return;
        if (atStart < 0) {
            atStart = carried.getAsInt();
            LOG.info("[箭] 开打时背包里 {} 支", atStart);
        } else if (latest != carried.getAsInt()) {
            LOG.info("[箭] 背包里的箭 {} -> {} 支；开打时 {} 支", latest, carried.getAsInt(), atStart);
        }
        latest = carried.getAsInt();
    }

    /** 少了几支（捡回的比射出的多就是负数）；一次都没读到背包为空。 */
    public OptionalInt spent() {
        return atStart < 0 ? OptionalInt.empty() : OptionalInt.of(atStart - latest);
    }

    /** 结果里写的那一项：少了几支，读不到为 null。 */
    public Integer spentOrNull() {
        OptionalInt spent = spent();
        return spent.isEmpty() ? null : spent.getAsInt();
    }
}
