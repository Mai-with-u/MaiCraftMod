// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.modes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 坐上载具的每刻决定：坐上了就完，出事如实分清是哪一样，够不着走过去，潜行按着先松再点，够得着才右键，连点三次为止。 */
class BoardsVehicleTest {

    private BoardsVehicle.Scene scene(boolean riding, boolean gone, boolean freeSeat, boolean inReach, int clicks) {
        return new BoardsVehicle.Scene(riding, gone, freeSeat, inReach, false, 0, clicks);
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

    /** 泅渡或贴边站位留下的潜行还按在身上：先松开再点，不把这一下白白送给原版的"潜行即想下来"。 */
    @Test
    void heldSneakReleasesBeforeClicking() {
        BoardsVehicle.Scene sneakHeld = new BoardsVehicle.Scene(false, false, true, true, true, 0, 0);
        assertEquals(BoardsVehicle.Next.RELEASE_SNEAK, BoardsVehicle.decide(sneakHeld));
    }

    /** 潜行松不开也不无限等：宽限耗尽就照常点，收场报文会带着当时的按键现场。 */
    @Test
    void sneakReleaseGivesUpWaitingAfterTheGraceAndClicksAnyway() {
        BoardsVehicle.Scene stillHeldAtGraceEnd = new BoardsVehicle.Scene(false, false, true, true, true,
                BoardsVehicle.SNEAK_RELEASE_SETTLE_TICKS + 1, 0);
        assertEquals(BoardsVehicle.Next.CLICK, BoardsVehicle.decide(stillHeldAtGraceEnd));
        // 宽限的最后一刻还在等：不点，把松开的机会用完。
        BoardsVehicle.Scene lastGraceTick = new BoardsVehicle.Scene(false, false, true, true, true,
                BoardsVehicle.SNEAK_RELEASE_SETTLE_TICKS, 0);
        assertEquals(BoardsVehicle.Next.RELEASE_SNEAK, BoardsVehicle.decide(lastGraceTick));
    }

    /** 潜行已松开：不再拦着点击，照常走右键那一步。 */
    @Test
    void releasedSneakDoesNotBlockClicking() {
        BoardsVehicle.Scene released = new BoardsVehicle.Scene(false, false, true, true, false, 3, 0);
        assertEquals(BoardsVehicle.Next.CLICK, BoardsVehicle.decide(released));
    }
}
