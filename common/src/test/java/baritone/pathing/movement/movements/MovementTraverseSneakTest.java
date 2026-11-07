// SPDX-License-Identifier: GPL-3.0-only
package baritone.pathing.movement.movements;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 搭桥整段蹲着：往前垫块、走上刚垫好的那一块都按住潜行，不在每块之间起身；本来就有的路面照常走，
 * 设置里假定走不掉边时不蹲。
 */
class MovementTraverseSneakTest {

    @Test
    void 往前垫块和走上刚垫好的那一块都蹲着() {
        assertTrue(MovementTraverse.sneaksWhileBridging(false, false));
    }

    @Test
    void 路面本来就在_照常走不蹲() {
        assertFalse(MovementTraverse.sneaksWhileBridging(true, false));
    }

    @Test
    void 假定走不掉边时不蹲() {
        assertFalse(MovementTraverse.sneaksWhileBridging(false, true));
    }
}
