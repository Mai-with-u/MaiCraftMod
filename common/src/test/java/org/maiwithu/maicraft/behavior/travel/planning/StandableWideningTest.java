// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.planning;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** 出行目的地的容差放宽：原容差一圈站不上时逐格放宽到最近有能站的格子的一圈，最多再放三格，附近全站不上就不放。 */
class StandableWideningTest {

    private static final TravelDestination TARGET =
            TravelDestination.confirmed(new WorldPosition(100, 30, -200, null), 2);

    /** 能站的格子集合；没列的都当站不上。 */
    private static StandableWidening.Standable only(String... cells) {
        Set<String> set = new HashSet<>(Set.of(cells));
        return (x, y, z) -> set.contains(x + "," + y + "," + z);
    }

    @Test
    void widensToTheNearestRingWithAStandableCell() {
        // 目标那一格到 3 格内全站不上，4 格外有一格能站：放宽到 2+2=4 的那一圈。
        TravelDestination widened = StandableWidening.widen(TARGET, only("104,30,-200"));
        assertEquals(4, widened.radius());
        assertEquals(TARGET.position(), widened.position(), "放宽只动容差，不挪目的地");
    }

    @Test
    void keepsDestinationWhenTheOriginalRingAlreadyHasAStandableCell() {
        // 原容差 2 格内就有能站的：原样返回，不放宽也不另建目的地。
        TravelDestination same = StandableWidening.widen(TARGET, only("101,30,-200"));
        assertSame(TARGET, same);
    }

    @Test
    void givesUpAfterThreeExtraBlocksWhenNothingIsStandableNearby() {
        // 附近 5 格全站不上：不凭空放宽，原样返回交回上层按走不到结算。
        TravelDestination same = StandableWidening.widen(TARGET, only("130,30,-230"));
        assertSame(TARGET, same);
    }

    @Test
    void unreadableCellsDoNotCountAsStandable() {
        // 环扫路过没加载、读不到的格子按站不上算，不凭空放宽目的地。
        StandableWidening.Standable nothing = (x, y, z) -> false;
        assertSame(TARGET, StandableWidening.widen(TARGET, nothing));
    }

    @Test
    void widenedDestinationKeepsDeviationNote() {
        // 开阔水挪到岸格的目的地再放宽：偏差说明原样带上，收场才写得出"目标在水中央、偏了几格"。
        TravelDestination detoured = TravelDestination.confirmed(
                new WorldPosition(100, 30, -200, null), 2, "目标在水中央，落到了岸上");
        TravelDestination widened = StandableWidening.widen(detoured, only("102,30,-200"));
        assertEquals("目标在水中央，落到了岸上", widened.arrivalNote());
        assertEquals(new BlockPos(100, 30, -200), widened.center());
    }
}
