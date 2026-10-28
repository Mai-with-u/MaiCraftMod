// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 扫描的安静判定：入账在涨就重新计时，连续不涨满 N 刻才算安静，入账变少（清账重来）同样算在涨。 */
class ScanQuietnessTest {

    @Test
    void 一次都没喂过不算安静() {
        assertFalse(new ScanQuietness(3).quiet());
    }

    @Test
    void 入账一直在涨就永远不安静() {
        ScanQuietness quietness = new ScanQuietness(3);
        for (int i = 1; i <= 50; i++) {
            quietness.observe(i);
            assertFalse(quietness.quiet(), "第 " + i + " 刻还在补新方块，不算安静");
        }
    }

    @Test
    void 连续不涨满限定刻数才算安静() {
        ScanQuietness quietness = new ScanQuietness(3);
        quietness.observe(100);
        assertFalse(quietness.quiet());
        quietness.observe(100);
        quietness.observe(100);
        assertFalse(quietness.quiet(), "只安静了两刻，还不到 N");
        quietness.observe(100);
        assertTrue(quietness.quiet(), "连续三刻没有新入账");
    }

    @Test
    void 安静之后又涨了就重新计时() {
        ScanQuietness quietness = new ScanQuietness(3);
        quietness.observe(10);
        quietness.observe(10);
        quietness.observe(10);
        quietness.observe(10);
        assertTrue(quietness.quiet());
        quietness.observe(11);
        assertFalse(quietness.quiet(), "补账又开始了，重新计时");
        quietness.observe(11);
        quietness.observe(11);
        quietness.observe(11);
        assertTrue(quietness.quiet());
    }

    @Test
    void 入账变少是清账重来_也重新计时() {
        ScanQuietness quietness = new ScanQuietness(2);
        quietness.observe(50);
        quietness.observe(50);
        quietness.observe(50);
        assertTrue(quietness.quiet());
        quietness.observe(3);
        assertFalse(quietness.quiet(), "换了世界或清了账，从头补");
        quietness.observe(3);
        quietness.observe(3);
        assertTrue(quietness.quiet());
    }

    @Test
    void 刻数不是正数当场拒绝() {
        assertThrows(IllegalArgumentException.class, () -> new ScanQuietness(0));
    }
}
