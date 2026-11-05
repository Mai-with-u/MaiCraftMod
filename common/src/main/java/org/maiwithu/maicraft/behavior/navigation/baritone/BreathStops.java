// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.survival.SuicideWayOut;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 水下一口气游不过去：同一段走路里，被停下那一刻头在水里、氧气到了该浮头换气的线（不到三分之一，或剩下的
 * 不够游上去），算一次"为换气停下"。换完气接着走、目的地没变还是同一段，接着数；换了目的地是新的一段，从头数。
 * 停下 3 次，离目标最近的距离都没比开始数时近 1 格以上，这段路判走不通；中间近过 1 格以上就清零重数。
 * 只看身体和距离，不认识换气需求：谁让它停下都一样，只要停下时人在水下快没气了。
 */
final class BreathStops {

    private static final Logger LOG = LoggerFactory.getLogger(BreathStops.class);

    /** 为换气停下这么多次都没靠近就判走不通。 */
    static final int LIMIT = 3;

    /** 比开始数时近这么多格才算靠近了，清零重数。 */
    static final double CLOSER = 1.0;

    /** 这一段走路的目的地；换了就从头数。 */
    private BlockPos destination;
    /** 这一段离目的地最近到过多远、在哪。 */
    private double best = Double.MAX_VALUE;
    private BlockPos nearest;
    /** 已经为换气停下几次，以及开始数那一刻的最近距离。 */
    private int stops;
    private double reference;
    /** 这一次停下已经数过：先到安全处再停、再暂停会各叫一次，同一次停下只数一回。 */
    private boolean counted;

    /** 每走一刻：记最近到过哪；换了目的地从头数；开始数之后近过 1 格以上就清零重数。 */
    void observe(BlockPos feet, BlockPos target) {
        if (!target.equals(destination)) {
            if (stops > 0) LOG.debug("[maicraft-path] 换了目的地 {}，为换气停下的次数从头数", target.toShortString());
            destination = target;
            best = Double.MAX_VALUE;
            nearest = null;
            stops = 0;
        }
        double distance = Math.sqrt(feet.distSqr(target));
        if (distance < best) {
            best = distance;
            nearest = feet;
        }
        if (stops > 0 && best <= reference - CLOSER) {
            LOG.debug("[maicraft-path] 为换气停下 {} 次后又靠近了 {} 格（最近到 {}），清零重数", stops,
                    String.format("%.1f", reference - best), nearest.toShortString());
            stops = 0;
        }
    }

    /** 这一段走路被停下：停下那一刻头在水里、氧气到线才数一次。 */
    void stopped(boolean outOfBreath) {
        if (counted || !outOfBreath) return;
        counted = true;
        if (stops == 0) reference = best;
        stops++;
        if (stops == 1) {
            LOG.debug("[maicraft-path] 水下为换气停下，开始数：离目标最近 {} 格", String.format("%.1f", best));
        } else {
            LOG.debug("[maicraft-path] 水下为换气停下第 {} 次，离目标最近 {} 格，开始数时 {} 格", stops,
                    String.format("%.1f", best), String.format("%.1f", reference));
        }
    }

    /** 换完气从原地接着走：下一次停下另算一次。 */
    void resumed() {
        counted = false;
    }

    /** 停够次数都没靠近：这段路从这里走不通。 */
    boolean exhausted() {
        return stops >= LIMIT;
    }

    int stops() {
        return stops;
    }

    /** 判死的说明：最近到过哪、离目标还有多远、为换气停下了几次；建议末尾接寻死出路（不该提时为空）。 */
    Problem giveUpProblem(Optional<String> wayOut) {
        LOG.debug("[maicraft-path] 水下为换气停下 {} 次都没靠近 1 格以上，判这段路走不通（最近到 {}，离目标 {} 格）",
                stops, nearest == null ? "?" : nearest.toShortString(), String.format("%.1f", best));
        String where = nearest == null ? "" : "最近到过 " + nearest.toShortString() + "，";
        return Problem.of(Problem.Kind.UNREACHABLE,
                "水下一口气游不过去：为换气停下了 " + stops + " 次，离目标都没近 1 格以上；"
                        + where + "离目标还有约 " + Math.round(best) + " 格",
                SuicideWayOut.appendTo("这段路要在水下憋气走太远；换一个不用潜这么深的地方或来源，或带上能在水下呼吸的东西再来",
                        wayOut));
    }
}
