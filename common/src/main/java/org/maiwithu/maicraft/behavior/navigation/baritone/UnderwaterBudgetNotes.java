// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 走路时头潜进水里，水下呼吸预算这一刻是在数还是让开（坐着载具、走在潜泳路线段上由那段路自己上浮）：
 * 情形变了记一行调试日志，实机里看得出氧气见底时是哪道闸该管、管没管。每趟走路一份。
 */
final class UnderwaterBudgetNotes {

    private static final Logger LOG = LoggerFactory.getLogger(UnderwaterBudgetNotes.class);

    /** 上一刻是哪种情形；头在水外是空串。 */
    private String last = "";

    void note(boolean headInWater, boolean passenger, boolean submerged, int air, int maxAir, BlockPos feet) {
        String now;
        if (!headInWater) {
            now = "";
        } else if (passenger) {
            now = "坐着载具，呼吸预算让开";
        } else if (submerged) {
            now = "走在潜泳路线段上，呼吸预算让开，由那段路自己上浮";
        } else {
            now = "头在水里，按呼吸预算走";
        }
        if (now.equals(last)) return;
        last = now;
        LOG.debug("[maicraft-path] {}（氧气 {}/{}），在 {}", now.isEmpty() ? "头出了水，呼吸预算不管" : now,
                air, maxAir, feet.toShortString());
    }
}
