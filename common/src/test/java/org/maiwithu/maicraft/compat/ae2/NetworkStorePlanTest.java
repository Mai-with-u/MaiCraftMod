// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 往网络里存几件的打算：count 是件数，整组截量、跨组凑满、实有不足都存准，对着纯函数跑。 */
class NetworkStorePlanTest {

    @Test
    void 要8件_整格64_拆出正好8件不整格塞() {
        // 第五轮实机出的问题：count=8 却把一整格 64 件快速移动进了网络。截量落在拆光标这一手。
        assertEquals(new NetworkStorePlan.TrimToCursor(0, 64, 8),
                NetworkStorePlan.next(8, List.of(64)));
    }

    @Test
    void 跨组凑满_整格先走_尾数拆出来() {
        // 要 100 件：先快速移动一整格 64，剩 36 件从下一格拆。
        assertEquals(new NetworkStorePlan.QuickMove(0), NetworkStorePlan.next(100, List.of(64, 64)));
        assertEquals(new NetworkStorePlan.TrimToCursor(0, 64, 36), NetworkStorePlan.next(36, List.of(64, 64)));
    }

    @Test
    void 要的比实有多_有几格存几格_存完为止() {
        assertEquals(new NetworkStorePlan.QuickMove(0), NetworkStorePlan.next(200, List.of(64, 32)));
        assertEquals(new NetworkStorePlan.QuickMove(0), NetworkStorePlan.next(136, List.of(32)));
        assertEquals(NetworkStorePlan.Done.class, NetworkStorePlan.next(104, List.of()).getClass());
    }

    @Test
    void 整格用得上的先挑最大的那格_存完正好腾出一整格() {
        assertEquals(new NetworkStorePlan.QuickMove(1), NetworkStorePlan.next(40, List.of(16, 32)));
    }

    @Test
    void 空手或够数了都不再动手() {
        assertEquals(NetworkStorePlan.Done.class, NetworkStorePlan.next(8, List.of()).getClass());
        assertEquals(NetworkStorePlan.Done.class, NetworkStorePlan.next(0, List.of(64)).getClass());
    }
}
