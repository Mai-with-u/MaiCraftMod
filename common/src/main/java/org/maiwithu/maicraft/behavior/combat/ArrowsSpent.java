// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import java.util.OptionalInt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 这一仗箭的去向：背包里箭的只数每少一支记一支"射出去"，每多一支记一支"捡回来"，净少的就是开打到现在的差。
 * 射出几箭（游戏确认了放箭的那几下）另有一本，几个数一起写进结果：取消拉弓时被原版松手放出去的、
 * 无限附魔不耗箭的、联动远程武器不用箭的，都只会让这些数不一样，不会让哪一个失实。
 * 点名战斗与自卫共用；读不到背包（没接上角色）时不给数。
 *
 * <p>仗打到一半被别的事插进来（自卫插曲、吃饭）时，插进来的那段里箭的变化不是这一仗的：
 * 回来接着打的第一刻把起点挪到当时的只数，那段的变化由插进来的那件事自己交代。
 */
public final class ArrowsSpent {

    private static final Logger LOG = LoggerFactory.getLogger(ArrowsSpent.class);

    private int atStart = -1;
    private int latest = -1;
    /** 少掉的只数合计（每次减少都加进来）。 */
    private int taken;
    /** 多出来的只数合计（每次增加都加进来：射出去插在近处又捡回的）。 */
    private int pickedUp;

    /** 每刻记一笔此刻背包里的箭数；第一笔当作开打时的。读不到时这一刻不记。 */
    public void note(OptionalInt carried) {
        if (carried.isEmpty()) return;
        int now = carried.getAsInt();
        if (atStart < 0) {
            atStart = now;
            LOG.info("[箭] 开打时背包里 {} 支", now);
        } else if (now < latest) {
            taken += latest - now;
            LOG.info("[箭] 背包里的箭 {} -> {} 支；开打时 {} 支", latest, now, atStart);
        } else if (now > latest) {
            pickedUp += now - latest;
            LOG.info("[箭] 背包里的箭 {} -> {} 支，捡回；开打时 {} 支", latest, now, atStart);
        }
        latest = now;
    }

    /**
     * 被插进来的事占了一段后回来接着打：这一刻的只数当作新的起点，那段里的变化不记进这一仗。
     * 还没记过第一笔、读不到背包时什么都不做。
     */
    public void resumeAt(OptionalInt carried) {
        if (carried.isEmpty() || atStart < 0) return;
        int now = carried.getAsInt();
        if (now != latest) {
            LOG.info("[箭] 插进来的事做完回来接着打：背包里 {} 支，走开时 {} 支，那段的变化不记进这一仗", now, latest);
        }
        atStart += now - latest;
        latest = now;
    }

    /** 净少了几支（捡回的比射出的多就是负数）；一次都没读到背包为空。 */
    public OptionalInt spent() {
        return atStart < 0 ? OptionalInt.empty() : OptionalInt.of(atStart - latest);
    }

    /** 结果里写的那一项：净少了几支，读不到为 null。 */
    public Integer spentOrNull() {
        OptionalInt spent = spent();
        return spent.isEmpty() ? null : spent.getAsInt();
    }

    /** 少掉的只数合计：射出去的（不管游戏确认没确认）加上别的原因离开背包的。 */
    public int taken() {
        return taken;
    }

    /** 捡回来的只数合计。 */
    public int pickedUp() {
        return pickedUp;
    }
}
