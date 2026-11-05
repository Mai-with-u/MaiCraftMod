// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.time.Duration;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端每刻推进时顺手记一笔"还活着"：隔一段时间记一行，隔多久没记上就知道进程停在哪一刻。
 * 客户端卡死或被强杀时不会有退出记录，只有这行能看出它最后走到哪里。
 */
final class ClientPulse {
    private static final Logger LOG = LoggerFactory.getLogger(ClientPulse.class);

    /** 多久记一行。 */
    static final long INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();

    private final LongSupplier clock;
    private long lastReportedNanos;
    private int reportCount;

    ClientPulse() {
        this(System::nanoTime);
    }

    ClientPulse(LongSupplier clock) {
        this.clock = clock;
        this.lastReportedNanos = clock.getAsLong();
    }

    /** 每刻调一次；到点记一行"还在推进"。 */
    void tick() {
        long now = clock.getAsLong();
        long elapsedNanos = now - lastReportedNanos;
        if (elapsedNanos < INTERVAL_NANOS) return;
        lastReportedNanos = now;
        reportCount++;
        LOG.info("客户端还在推进，上一次记下到这一阵过了 {} 秒", elapsedNanos / 1_000_000_000L);
    }

    /** 记了几行：测试靠它看到点没有。 */
    int reportCount() {
        return reportCount;
    }
}
