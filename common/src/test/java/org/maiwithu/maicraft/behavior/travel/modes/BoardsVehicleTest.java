// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.modes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 坐上载具的每刻决定：坐上了就完，出事如实分清是哪一样，够不着走过去，够得着才右键，连点三次为止。 */
class BoardsVehicleTest {

    private BoardsVehicle.Scene scene(boolean riding, boolean gone, boolean freeSeat, boolean inReach, int clicks) {
        return new BoardsVehicle.Scene(riding, gone, freeSeat, inReach, clicks);
    }

    @Test
    void alreadyRidingCountsAsDoneEvenIfLaterChecksWouldFail() {
        // 已经坐在上面：别的都不用看，坐上就是坐上。
        assertEquals(BoardsVehicle.Next.DONE,
                BoardsVehicle.decide(scene(true, true, false, false, BoardsVehicle.MAX_CLICKS)));
    }

    @Test
    void vehicleGoneFailsBeforeClicking() {
        assertEquals(BoardsVehicle.Next.GONE, BoardsVehicle.decide(scene(false, true, true, true, 0)));
    }

    @Test
    void seatTakenFailsBeforeClicking() {
        assertEquals(BoardsVehicle.Next.NO_SEAT, BoardsVehicle.decide(scene(false, false, false, true, 0)));
    }

    @Test
    void outOfReachWalksInsteadOfClicking() {
        assertEquals(BoardsVehicle.Next.WALK, BoardsVehicle.decide(scene(false, false, true, false, 0)));
    }

    @Test
    void clicksUpToTheLimitThenGivesUp() {
        assertEquals(BoardsVehicle.Next.CLICK, BoardsVehicle.decide(scene(false, false, true, true, 0)));
        assertEquals(BoardsVehicle.Next.CLICK,
                BoardsVehicle.decide(scene(false, false, true, true, BoardsVehicle.MAX_CLICKS - 1)));
        assertEquals(BoardsVehicle.Next.GIVE_UP,
                BoardsVehicle.decide(scene(false, false, true, true, BoardsVehicle.MAX_CLICKS)));
    }
}
