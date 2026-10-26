// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.landing;

import java.util.List;
import java.util.function.Predicate;

/**
 * 方案轮换的次序：对准连续对不上、手里这种来不及备好时，按排好的优先次序（先水、
 * 免伤的、干草最后）往下一个找。现成的直接算数，要放的必须身上带着；轮换不改次序，只跳过用不了的。
 */
public final class PlanRotation {

    private PlanRotation() {}

    /**
     * 从 from 的下一种起找下一个能用的办法，返回它在候选里的位置；一个都找不到返回 -1。
     * carried 回答身上（背包与副手）带没带这一种，现场读不到带没带就找不到可换的。
     */
    public static int nextIndex(List<LandingPlan> candidates, int from, Predicate<LandingAid> carried) {
        for (int index = from + 1; index < candidates.size(); index++) {
            LandingPlan next = candidates.get(index);
            if (next.existing() || carried.test(next.aid())) return index;
        }
        return -1;
    }
}
