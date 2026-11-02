// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.cook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 身上几件同样的东西都能拿去锻造台升级时：附魔多的先，一样多挑磨损少的，都一样取背包里靠前的。 */
class ItemToUpgradeTest {

    @Test
    void 附魔多的先升级() {
        var plain = new ItemToUpgrade.Candidate(0, 0, 0);
        var enchanted = new ItemToUpgrade.Candidate(5, 4, 300);
        assertEquals(5, ItemToUpgrade.pick(List.of(plain, enchanted)).orElseThrow().slotIndex());
    }

    @Test
    void 附魔一样多_挑磨损少的() {
        var worn = new ItemToUpgrade.Candidate(1, 3, 900);
        var fresh = new ItemToUpgrade.Candidate(7, 3, 10);
        assertEquals(7, ItemToUpgrade.pick(List.of(worn, fresh)).orElseThrow().slotIndex());
    }

    @Test
    void 都一样_取背包里靠前的() {
        var later = new ItemToUpgrade.Candidate(9, 0, 0);
        var earlier = new ItemToUpgrade.Candidate(2, 0, 0);
        assertEquals(2, ItemToUpgrade.pick(List.of(later, earlier)).orElseThrow().slotIndex());
    }

    @Test
    void 一件都没有_挑不出() {
        assertTrue(ItemToUpgrade.pick(List.of()).isEmpty());
    }
}
