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
 * <p>仗打到一半被别的事插进来（自卫插曲、吃饭）时，那段里箭的变化按谁认下算谁的：插进来的事自己记了消耗、捡回的
 * 归它，剩下的归这一仗——最后一箭的扣减服务器隔一两刻才同步，常常正落在插进来的那段里，它不是插进来的事射的。
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
     * 被插进来的事占了一段后回来接着打：那段里背包净少的，减去插进来的事自己认下的（消耗减捡回），剩下的记成这一仗的
     * （多半是走开前最后一箭隔了一两刻才同步的扣减）；剩下是负数就当这一仗捡回了。还没记过第一笔、读不到背包时什么都不做。
     *
     * @param claimedTaken    插进来的那些事认下的消耗（支）
     * @param claimedPickedUp 插进来的那些事认下的捡回（支）
     */
    public void resumeAt(OptionalInt carried, int claimedTaken, int claimedPickedUp) {
        if (carried.isEmpty() || atStart < 0) return;
        int now = carried.getAsInt();
        int dropped = latest - now;
        int own = dropped - (claimedTaken - claimedPickedUp);
        if (dropped != 0 || own != 0) {
            LOG.info("[箭] 插进来的事做完回来接着打：背包里 {} 支，走开时 {} 支；插进来的认下消耗 {} 捡回 {}，剩下 {} 支算这一仗的",
                    now, latest, claimedTaken, claimedPickedUp, own);
        }
        if (own > 0) taken += own;
        else pickedUp += -own;
        atStart = now + (atStart - latest) + own;
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
