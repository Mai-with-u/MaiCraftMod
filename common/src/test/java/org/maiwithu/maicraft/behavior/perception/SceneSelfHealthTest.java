// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 对外展示的血量：一位小数向上取，还活着就不显示 0；整数血原样。 */
class SceneSelfHealthTest {

    @Test
    void 不到一点的存活血量向上取到零点一() {
        assertEquals(0.9, SceneSelf.shownHealth(0.83f));
        assertEquals(0.1, SceneSelf.shownHealth(0.01f));
    }

    @Test
    void 整数与半点血原样() {
        assertEquals(18.0, SceneSelf.shownHealth(18f));
        assertEquals(17.5, SceneSelf.shownHealth(17.5f));
    }

    @Test
    void 没血了显示零() {
        assertEquals(0.0, SceneSelf.shownHealth(0f));
        assertEquals(0.0, SceneSelf.shownHealth(-1f));
    }
}
