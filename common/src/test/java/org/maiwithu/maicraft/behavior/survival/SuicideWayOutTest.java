// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.world.KeepInventoryRule;

/** 走投无路时提不提寻死：开着说开着那句、读不到说"如果开着"那句；关着、还在读、创造旁观极限都不提。 */
class SuicideWayOutTest {

    @Test
    void 开着和读不到各说一句() {
        assertEquals(Optional.of(SuicideWayOut.WHEN_ON),
                SuicideWayOut.line(KeepInventoryRule.Reading.ON, false, false, false));
        assertEquals(Optional.of(SuicideWayOut.WHEN_UNKNOWN),
                SuicideWayOut.line(KeepInventoryRule.Reading.UNREADABLE, false, false, false));
    }

    @Test
    void 关着_还在读_创造旁观极限都不提() {
        assertEquals(Optional.empty(), SuicideWayOut.line(KeepInventoryRule.Reading.OFF, false, false, false));
        assertEquals(Optional.empty(), SuicideWayOut.line(KeepInventoryRule.Reading.READING, false, false, false));
        assertEquals(Optional.empty(), SuicideWayOut.line(KeepInventoryRule.Reading.ON, true, false, false));
        assertEquals(Optional.empty(), SuicideWayOut.line(KeepInventoryRule.Reading.ON, false, true, false));
        assertEquals(Optional.empty(), SuicideWayOut.line(KeepInventoryRule.Reading.ON, false, false, true));
    }

    @Test
    void 接在建议末尾_没建议就只有这一句() {
        assertEquals("换条路；" + SuicideWayOut.WHEN_ON,
                SuicideWayOut.appendTo("换条路", Optional.of(SuicideWayOut.WHEN_ON)));
        assertEquals(SuicideWayOut.WHEN_ON, SuicideWayOut.appendTo(null, Optional.of(SuicideWayOut.WHEN_ON)));
        assertEquals("换条路", SuicideWayOut.appendTo("换条路", Optional.empty()));
    }
}
