// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.planning;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 目标柱整列是水时往附近找最近干地落点：挑最近的、找遍了没有就给空。 */
class NearestDryLandingTest {

    /** 干地落点图：柱 (x,z) → 能站的脚位高度；没摆的柱当整柱是水。 */
    private final Map<String, Integer> dry = new HashMap<>();

    private void dryAt(int x, int z, int y) {
        dry.put(x + "," + z, y);
    }

    @Test
    void picksTheNearestDryColumn() {
        // 实机（goal 818）：目标 (134,32) 整列是水，岸在三四格外——斜向近的那格比正方向远的先中。
        dryAt(134, 36, 62);
        dryAt(138, 32, 64);
        var found = NearestDryLanding.near(134, 32, 8, this::landingOf, null);
        assertTrue(found.isPresent());
        assertEquals(134, found.get().x());
        assertEquals(36, found.get().z());
        assertEquals(62, found.get().y());
    }

    @Test
    void nothingDryNearbyGivesEmpty() {
        // 开阔水中央：附近八格内没有一格干地，不替玩家挪目的地。
        assertTrue(NearestDryLanding.near(0, 0, 8, this::landingOf, "minecraft:overworld").isEmpty());
    }

    @Test
    void drySpotBeyondTheRadiusDoesNotCount() {
        dryAt(0, 12, 64);
        assertTrue(NearestDryLanding.near(0, 0, 8, this::landingOf, null).isEmpty(),
                "十二格外才有的岸不在贴岸这一圈里");
    }

    @Test
    void foundSpotCarriesItsDimension() {
        dryAt(2, 0, 70);
        var found = NearestDryLanding.near(0, 0, 8, this::landingOf, "minecraft:the_nether");
        assertEquals("minecraft:the_nether", found.orElseThrow().dimension());
    }

    private OptionalInt landingOf(Integer x, Integer z) {
        Integer y = dry.get(x + "," + z);
        return y == null ? OptionalInt.empty() : OptionalInt.of(y);
    }
}
