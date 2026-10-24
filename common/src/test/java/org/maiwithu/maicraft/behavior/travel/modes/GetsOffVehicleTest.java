// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.modes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 下车的每刻决定：真的不坐在上面才算下来，按住潜行的刻数到顶如实放弃。 */
class GetsOffVehicleTest {

    @Test
    void notAPassengerAnyMoreCountsAsOff() {
        assertEquals(GetsOffVehicle.Off.DONE, GetsOffVehicle.decide(false, 0));
    }

    @Test
    void keepsHoldingSneakUntilTheLimit() {
        assertEquals(GetsOffVehicle.Off.HOLD, GetsOffVehicle.decide(true, 0));
        assertEquals(GetsOffVehicle.Off.HOLD, GetsOffVehicle.decide(true, GetsOffVehicle.LIMIT_TICKS - 1));
        assertEquals(GetsOffVehicle.Off.GIVE_UP, GetsOffVehicle.decide(true, GetsOffVehicle.LIMIT_TICKS));
    }
}
