// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 连别人的服务器时按服务端报的死亡不掉落答：报开着、关着照答，旧版服务端没报才读不到。 */
class KeepInventoryRuleTest {

    @Test
    void 服务端报了照答_没报读不到() {
        assertEquals(KeepInventoryRule.Reading.ON, KeepInventoryRule.fromServer(true));
        assertEquals(KeepInventoryRule.Reading.OFF, KeepInventoryRule.fromServer(false));
        assertEquals(KeepInventoryRule.Reading.UNREADABLE, KeepInventoryRule.fromServer(null));
    }
}
