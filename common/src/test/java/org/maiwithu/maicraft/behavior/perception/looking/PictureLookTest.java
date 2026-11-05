// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.looking;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 为拍画面转不转：人按着最先看，其次临时任务、手上有活，最后才是身体和屏幕。 */
class PictureLookTest {

    private static Optional<PictureLook.WhyNot> why(IdleLooking.Availability availability, boolean automation,
            boolean temporary, boolean onFoot, boolean othersScreen) {
        return PictureLook.whyNotTurn(availability, automation, temporary, onFoot, othersScreen);
    }

    @Test
    void 闲着和只能转头时都转() {
        assertEquals(Optional.empty(), why(IdleLooking.Availability.FREE, true, false, true, false));
        assertEquals(Optional.empty(), why(IdleLooking.Availability.HEAD_ONLY, true, false, true, false));
    }

    @Test
    void 人按着时最先说是人在看_哪怕同时还有别的原因() {
        assertEquals(Optional.of(PictureLook.WhyNot.HUMAN_CONTROLS),
                why(IdleLooking.Availability.TAKEN, false, true, false, true));
    }

    @Test
    void 临时任务排在手上有活之前() {
        assertEquals(Optional.of(PictureLook.WhyNot.TEMPORARY_TASK),
                why(IdleLooking.Availability.TAKEN, true, true, true, false));
        assertEquals(Optional.of(PictureLook.WhyNot.WORKING),
                why(IdleLooking.Availability.TAKEN, true, false, false, true));
    }

    @Test
    void 身体排在屏幕之前() {
        assertEquals(Optional.of(PictureLook.WhyNot.NOT_ON_FOOT),
                why(IdleLooking.Availability.FREE, true, false, false, true));
        assertEquals(Optional.of(PictureLook.WhyNot.OTHERS_SCREEN),
                why(IdleLooking.Availability.FREE, true, false, true, true));
    }
}
