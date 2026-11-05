// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.bodylanguage;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.maiwithu.maicraft.behavior.perception.bodylanguage.PlayerHitTally.Verdict.BEATING;
import static org.maiwithu.maicraft.behavior.perception.bodylanguage.PlayerHitTally.Verdict.POKE;

/** 拍一下还是连打：拿什么都一样，看 10 秒里几下、掉了多少、有没有掉到拒战线下、是不是射过来的。 */
class PlayerHitTallyTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private final PlayerHitTally tally = new PlayerHitTally();

    @Test
    void oneSwordHitIsStillAPoke() {
        assertEquals(POKE, tally.hit(ALEX, 0, 6, 14, false), "剑砍一下多半也只是叫你");
    }

    @Test
    void threeBareHandedTapsThatBarelyHurtStayPokes() {
        assertEquals(POKE, tally.hit(ALEX, 0, 1, 19, false));
        assertEquals(POKE, tally.hit(ALEX, 20, 1, 18, false));
        assertEquals(POKE, tally.hit(ALEX, 40, 1, 17, false), "10 秒 3 下只掉了 3 点");
    }

    @Test
    void threeSwordHitsLosingTwelveBecomeABeatingFromTheThird() {
        assertEquals(POKE, tally.hit(ALEX, 0, 4, 16, false));
        assertEquals(POKE, tally.hit(ALEX, 20, 4, 12, false));
        assertEquals(BEATING, tally.hit(ALEX, 40, 4, 8.5, false), "第 3 下起是连打");
        assertEquals(BEATING, tally.hit(ALEX, 60, 0.5f, 8, false), "之后打中的都算连打");
        assertEquals(POKE, tally.hit(STEVE, 60, 1, 8, false), "别人分开记");
    }

    @Test
    void droppingBelowTheRetreatLineIsABeatingAtOnce() {
        assertEquals(BEATING, tally.hit(ALEX, 0, 3, 7, false));
    }

    @Test
    void tenQuietSecondsEndTheBeating() {
        tally.hit(ALEX, 0, 4, 16, false);
        tally.hit(ALEX, 20, 4, 12, false);
        assertEquals(BEATING, tally.hit(ALEX, 40, 4, 9, false));
        assertEquals(POKE, tally.hit(ALEX, 400, 1, 19, false), "10 秒没再打中，回到拍一下");
    }

    @Test
    void arrowsAreBeatingsAndSnowballsArePokes() {
        assertEquals(BEATING, tally.hit(ALEX, 0, 3, 17, true), "箭射中一律连打");
        assertEquals(POKE, tally.hit(STEVE, 0, 0, 20, false), "雪球不掉血");
    }

    @Test
    void afterASparTheLedgerStartsFromZero() {
        tally.hit(ALEX, 0, 4, 16, false);
        tally.hit(ALEX, 20, 4, 12, false);
        tally.forget(ALEX);
        assertEquals(POKE, tally.hit(ALEX, 40, 4, 9, false), "切磋结束从零记");
    }
}
