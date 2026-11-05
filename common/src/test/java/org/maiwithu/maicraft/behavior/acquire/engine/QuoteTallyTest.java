// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * 动手问价那一轮的账：超过慢日志的线才算慢；慢日志那一行说清要的是什么、用了多久、问了几次、
 * 几次用了记下的、最费时的是哪几个来源。问了一秒多还没问完时问下一个来源前报一行进度，之后隔五秒才再报。
 */
class QuoteTallyTest {

    @Test
    void 正好压在线上不算慢_超过一毫秒才算() {
        assertFalse(QuoteTally.slow(0));
        assertFalse(QuoteTally.slow(QuoteTally.SLOW_MILLIS));
        assertTrue(QuoteTally.slow(QuoteTally.SLOW_MILLIS + 1));
    }

    @Test
    void 慢日志写清要什么_问了几次_几次用了记下的_最费时的来源排在前面() {
        QuoteTally tally = new QuoteTally("#minecraft:planks ×2", () -> 0L);
        // 自己做问了一次、里面回头问原料价花了 900 毫秒；采掘被问了三次，最慢一次 300 毫秒。
        tally.quoted("自己做", 900_000_000L);
        tally.quoted("采掘", 100_000_000L);
        tally.quoted("采掘", 300_000_000L);
        tally.quoted("采掘", 200_000_000L);
        tally.quoted("记得的箱子", 1_000_000L);
        tally.quoted("记得的木头产地", 0L);
        tally.askedAround();
        tally.askedAround();
        tally.recalled();

        String line = tally.report(1234);

        assertTrue(line.contains("#minecraft:planks ×2"), line);
        assertTrue(line.contains("用了 1234 毫秒"), line);
        assertTrue(line.contains("向来源问价 6 次"), line);
        assertTrue(line.contains("为原料问遍各来源 2 遍"), line);
        assertTrue(line.contains("另有 1 遍用了这一轮记下的"), line);
        assertTrue(line.contains("采掘 3 次共 600 毫秒（最慢一次 300 毫秒）"), line);
        // 最费时的排在前面，只列三个：花得最少的那个不出现。
        assertTrue(line.indexOf("自己做 1 次") < line.indexOf("采掘 3 次"), line);
        assertTrue(line.indexOf("采掘 3 次") < line.indexOf("记得的箱子 1 次"), line);
        assertFalse(line.contains("记得的木头产地"), line);
    }

    @Test
    void 问了一秒多还没问完时报一行进度_五秒内不再报() {
        // 手拨的钟：按毫秒拨，换成纳秒给账本。
        long[] nowMillis = {0};
        QuoteTally tally = new QuoteTally("#minecraft:planks ×1", () -> nowMillis[0] * 1_000_000L);
        tally.quoted("自己做", 0L);
        tally.askedAround();

        nowMillis[0] = QuoteTally.PROGRESS_AFTER_MILLIS;
        assertEquals(Optional.empty(), tally.progressBefore("采掘"), "正好一秒还不报");

        nowMillis[0] = 1_200;
        String line = tally.progressBefore("采掘").orElseThrow();
        assertTrue(line.contains("#minecraft:planks ×1 已经 1200 毫秒还没问完，接下来问 采掘"), line);
        assertTrue(line.contains("向来源问价 1 次"), line);
        assertTrue(line.contains("为原料问遍各来源 1 遍"), line);

        // 报过以后五秒内问别的来源都不再报；过了五秒再报一行，卡死时最后一行指得出卡在哪个来源前面。
        nowMillis[0] = 1_200 + QuoteTally.PROGRESS_EVERY_MILLIS;
        assertEquals(Optional.empty(), tally.progressBefore("记得的箱子"));
        nowMillis[0] = 1_201 + QuoteTally.PROGRESS_EVERY_MILLIS;
        assertTrue(tally.progressBefore("记得的箱子").orElseThrow().contains("接下来问 记得的箱子"));
    }
}
