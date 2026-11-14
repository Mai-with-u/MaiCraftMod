// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.follow;

import java.util.HashMap;
import java.util.Map;

/**
 * 跟随时的同乘尝试记录：哪辆车刚没坐上先歇一阵、同一辆连着失败几次不再去坐、没空座的车只说一次。
 */
final class VehicleChoices {

    /** 一辆车没坐上，这么久内不再去坐它（刻）。 */
    static final long RETRY_TICKS = 20L * 10;
    /** 同一辆车没坐上几次就不再去坐它。 */
    static final int MAX_TRIES = 2;

    private final Map<Integer, Long> refusedAt = new HashMap<>();
    private final Map<Integer, Integer> failures = new HashMap<>();
    private Integer toldNoSeat;

    /** 这辆车此刻还能不能去试着坐。 */
    boolean mayTry(int vehicleId, long now) {
        Long refused = refusedAt.get(vehicleId);
        if (refused != null && now - refused < RETRY_TICKS) return false;
        return failures.getOrDefault(vehicleId, 0) < MAX_TRIES;
    }

    /** 记下这辆车这一次没坐上。 */
    void noteFailure(int vehicleId, long now) {
        refusedAt.put(vehicleId, now);
        failures.merge(vehicleId, 1, Integer::sum);
    }

    /** 这辆车没有空座的话之前说过了没有；没说过就记下并返回真，免得每刻复读。 */
    boolean shouldTellNoSeat(int vehicleId) {
        if (Integer.valueOf(vehicleId).equals(toldNoSeat)) return false;
        toldNoSeat = vehicleId;
        return true;
    }
}
