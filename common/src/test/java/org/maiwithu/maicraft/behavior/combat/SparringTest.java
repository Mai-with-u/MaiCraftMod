// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 切磋什么时候收手：停手线、再一刀他就倒、他停手、他走开、半分钟没人打中；切磋对手打中的从零数。 */
class SparringTest {

    private static Sparring.Moment moment(double me, double him, double distance, boolean reaches, boolean standsDown,
                                          long lastTheyHit, long lastAnyHit) {
        return new Sparring.Moment(1000, me, him, distance, reaches, standsDown, lastTheyHit, lastAnyHit, 7);
    }

    private static Optional<Sparring.Ending> at(Sparring.Moment moment) {
        return Sparring.ending(moment, Sparring.DEFAULT_STOP_AT);
    }

    @Test
    void keepsGoingWhileBothAreAboveTheLine() {
        assertTrue(at(moment(15, 15, 3, true, false, 990, 995)).isEmpty());
    }

    @Test
    void stopsAtEitherLine() {
        assertEquals(Sparring.Ending.ME_AT_LINE, at(moment(6, 15, 3, true, false, 990, 995)).orElseThrow());
        assertEquals(Sparring.Ending.THEY_AT_LINE, at(moment(15, 5, 3, true, false, 990, 995)).orElseThrow());
    }

    // 他还在停手线上、但我这一刀（7 点）会把他打倒：不砍；停手线为 0 时不管。
    @Test
    void sparesTheFinishingBlowUnlessFightingToTheEnd() {
        Sparring.Moment close = new Sparring.Moment(1000, 15, 7, 3, true, false, 990, 995, 8);
        assertEquals(Sparring.Ending.FINISHING_BLOW, Sparring.ending(close, 6).orElseThrow());
        assertTrue(Sparring.ending(close, 0).isEmpty());
    }

    // 5 秒没打中我，又退到够不着或者蹲下、收起兵器：他停手了。只是没打中、还贴着我拿着剑不算。
    @Test
    void noticesTheyStopped() {
        assertEquals(Sparring.Ending.THEY_STOPPED, at(moment(15, 15, 6, false, false, 890, 995)).orElseThrow());
        assertEquals(Sparring.Ending.THEY_STOPPED, at(moment(15, 15, 2, true, true, 890, 995)).orElseThrow());
        assertTrue(at(moment(15, 15, 2, true, false, 890, 995)).isEmpty());
    }

    @Test
    void stopsWhenTheyLeaveOrNobodyLandsAHit() {
        assertEquals(Sparring.Ending.THEY_LEFT, at(moment(15, 15, 13, false, false, 990, 995)).orElseThrow());
        assertEquals(Sparring.Ending.NO_HITS, at(moment(15, 15, 2, true, false, 990, 300)).orElseThrow());
    }

    // 读不到他的血：只看我这边。
    @Test
    void unknownHealthOnlyWatchesMine() {
        assertTrue(at(moment(15, Double.NaN, 3, true, false, 990, 995)).isEmpty());
    }

    @Test
    void partnerCountsHitsFromZeroEachBout() {
        SparPartner partner = new SparPartner();
        UUID alex = UUID.randomUUID();
        partner.begin(alex);
        partner.tookHit(10);
        partner.tookHit(20);
        assertEquals(2, partner.hitsTaken());
        assertEquals(20, partner.lastHitTaken());
        partner.end();
        partner.begin(alex);
        assertEquals(0, partner.hitsTaken());
    }
}
