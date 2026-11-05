// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/** 客户端还活着的记录：到点记一行，不到点不记；记过之后计时往后推。 */
class ClientPulseTest {

    @Test
    void 不到点不记() {
        AtomicLong now = new AtomicLong(0L);
        ClientPulse pulse = new ClientPulse(now::get);
        now.addAndGet(ClientPulse.INTERVAL_NANOS - 1);
        pulse.tick();
        assertEquals(0, pulse.reportCount());
    }

    @Test
    void 到点记一行并把计时推后() {
        AtomicLong now = new AtomicLong(0L);
        ClientPulse pulse = new ClientPulse(now::get);
        now.addAndGet(ClientPulse.INTERVAL_NANOS);
        pulse.tick();
        assertEquals(1, pulse.reportCount());
        // 刚记过：这一刻再来不重复记。
        pulse.tick();
        assertEquals(1, pulse.reportCount());
        // 又过了一个间隔：再记一行。
        now.addAndGet(ClientPulse.INTERVAL_NANOS);
        pulse.tick();
        assertEquals(2, pulse.reportCount());
    }
}
