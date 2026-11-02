// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 站定朝向：只为看东西转头时角色朝哪边不变，镜头被别的事转走才回到镜头朝向。 */
class StandingFacingTest {

    @Test
    void 没在转头看东西时朝向就是镜头朝向() {
        StandingFacing facing = new StandingFacing();
        assertEquals(37f, facing.facing(37f));
        assertFalse(facing.holding());
    }

    @Test
    void 转头看东西期间朝向停在开始转头时() {
        StandingFacing facing = new StandingFacing();
        facing.hold(10f);
        assertTrue(facing.holding());
        assertEquals(10f, facing.facing(95f));
        assertEquals(10f, facing.facing(-170f));
    }

    @Test
    void 连着看几样不改站定朝向() {
        StandingFacing facing = new StandingFacing();
        facing.hold(10f);
        facing.hold(80f);
        assertEquals(10f, facing.facing(80f));
    }

    @Test
    void 收手后镜头没动仍按站定朝向算() {
        StandingFacing facing = new StandingFacing();
        facing.hold(10f);
        facing.letGo(95f);
        assertFalse(facing.holding());
        // 新目标第一刻解析"往前"：镜头还停在看过去的方向，朝向仍是站定的那个。
        assertEquals(10f, facing.facing(95.5f));
    }

    @Test
    void 收手后镜头被转走就回到镜头朝向() {
        StandingFacing facing = new StandingFacing();
        facing.hold(10f);
        facing.letGo(95f);
        assertEquals(120f, facing.facing(120f));
        // 作废以后不会再回到站定朝向。
        assertEquals(95f, facing.facing(95f));
    }

    @Test
    void 收手后镜头没动又接着看仍是原来的站定朝向() {
        StandingFacing facing = new StandingFacing();
        facing.hold(10f);
        facing.letGo(95f);
        facing.hold(95f);
        assertEquals(10f, facing.facing(200f));
    }

    @Test
    void 跨过正负一百八十度也认得出镜头没动() {
        StandingFacing facing = new StandingFacing();
        facing.hold(0f);
        facing.letGo(179.8f);
        assertEquals(0f, facing.facing(-179.9f));
    }

    @Test
    void 换了身体站定朝向清掉() {
        StandingFacing facing = new StandingFacing();
        facing.hold(10f);
        facing.forget();
        assertEquals(60f, facing.facing(60f));
    }
}
