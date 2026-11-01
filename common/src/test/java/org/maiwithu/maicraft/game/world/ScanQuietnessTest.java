// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 扫描的安静判定：入账在涨就重新计时，连续不涨满 N 个游戏刻才算安静，入账变少（清账重来）同样算在涨；按刻走、不按喂的次数走。 */
class ScanQuietnessTest {

    @Test
    void 一次都没喂过不算安静() {
        assertFalse(new ScanQuietness(3).quiet(1000));
    }

    @Test
    void 入账一直在涨就永远不安静() {
        ScanQuietness quietness = new ScanQuietness(3);
        for (int tick = 1; tick <= 50; tick++) {
            quietness.observe(tick, tick);
            assertFalse(quietness.quiet(tick), "第 " + tick + " 刻还在补新方块，不算安静");
        }
    }

    @Test
    void 连续不涨满限定刻数才算安静() {
        ScanQuietness quietness = new ScanQuietness(3);
        quietness.observe(100, 10);
        quietness.observe(100, 10);
        quietness.observe(100, 10);
        assertFalse(quietness.quiet(12), "同一刻重复看不多算，到第十二刻才安静两刻");
        assertTrue(quietness.quiet(13), "连续三刻没有新入账");
    }

    @Test
    void 安静之后又涨了就重新计时() {
        ScanQuietness quietness = new ScanQuietness(3);
        quietness.observe(10, 10);
        quietness.observe(10, 11);
        quietness.observe(10, 12);
        quietness.observe(10, 13);
        assertTrue(quietness.quiet(13));
        quietness.observe(11, 14);
        assertFalse(quietness.quiet(14), "补账又开始了，重新计时");
        quietness.observe(11, 15);
        quietness.observe(11, 16);
        quietness.observe(11, 17);
        assertTrue(quietness.quiet(17));
    }

    @Test
    void 入账变少是清账重来_也重新计时() {
        ScanQuietness quietness = new ScanQuietness(2);
        quietness.observe(50, 10);
        quietness.observe(50, 11);
        quietness.observe(50, 12);
        assertTrue(quietness.quiet(12));
        quietness.observe(3, 13);
        assertFalse(quietness.quiet(13), "换了世界或清了账，从头补");
        quietness.observe(3, 14);
        quietness.observe(3, 15);
        assertTrue(quietness.quiet(15));
    }

    @Test
    void 安静按游戏刻算_不按喂的次数算() {
        // 问价的等扫描窗只有几秒，隔一两百毫秒才轮询一次：安静若按喂的次数计，
        // 二十次要喂满五秒，恰好卡死在窗里永远到不了。按刻算，喂得稀也能到。
        ScanQuietness quietness = new ScanQuietness(20);
        quietness.observe(7, 100);
        for (long tick = 105; tick < 120; tick += 5) {
            quietness.observe(7, tick);
            assertFalse(quietness.quiet(tick), "第 " + tick + " 刻还没安静满二十刻");
        }
        assertTrue(quietness.quiet(120), "满二十刻就算安静，哪怕只喂过五次");
    }

    @Test
    void 两轮问价之间隔了很久_一眼游戏刻就确认安静() {
        // 上一轮问价五秒窗到点收了，下一轮几秒后才来：中间没人喂，安静按刻照走，
        // 下一轮第一眼就能确认补账停了，报价照落，不再从头等。
        ScanQuietness quietness = new ScanQuietness(20);
        quietness.observe(7, 100);
        quietness.observe(7, 101);
        assertFalse(quietness.quiet(101));
        assertTrue(quietness.quiet(121));
    }

    @Test
    void 刻数不是正数当场拒绝() {
        assertThrows(IllegalArgumentException.class, () -> new ScanQuietness(0));
    }
}
