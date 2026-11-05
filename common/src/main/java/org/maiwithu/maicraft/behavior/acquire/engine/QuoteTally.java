// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.engine;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * 动手时问一轮价的账：向各来源问了几次、各花了多久，做法类来源排做法时为原料问遍各来源几遍、
 * 有几遍用了这一轮记下的价。这一轮问得慢时据此写一行日志，实机卡顿时看得出是哪样东西、哪个来源拖慢了。
 * 一轮问了很久还没问完时，问下一个来源前先报一行进度：客户端卡死、进程没等这一轮问完就没了，
 * 最后一行也指得出卡在哪个来源前面。
 */
final class QuoteTally {

    /** 一轮问价花的时间超过这么多毫秒就算慢、写一行日志：和估算的慢日志一样按 50 毫秒，客户端一刻是 50 毫秒。 */
    static final long SLOW_MILLIS = 50;
    /** 慢日志里列几个最费时的来源。 */
    private static final int SLOWEST_SHOWN = 3;
    /** 一轮问价过了这么多毫秒还没问完，问下一个来源前报第一行进度。 */
    static final long PROGRESS_AFTER_MILLIS = 1_000;
    /** 报过一行进度以后，再过这么多毫秒才报下一行，卡住几十秒也只有几行。 */
    static final long PROGRESS_EVERY_MILLIS = 5_000;

    /** 这一轮要的东西与件数，写进日志。 */
    private final String wanted;
    /** 取此刻的纳秒数；实机用系统时钟，测试换成手拨的钟。 */
    private final LongSupplier clock;
    private final long startedNanos;
    /** 下一行进度要等这一轮问到多少毫秒才报。 */
    private long nextProgressMillis = PROGRESS_AFTER_MILLIS;

    private final Map<String, Long> nanosBySource = new HashMap<>();
    private final Map<String, Integer> quotesBySource = new HashMap<>();
    private final Map<String, Long> slowestQuoteBySource = new HashMap<>();
    private int ingredientAsks;
    private int recalled;

    /** 这一轮从此刻开始计时。 */
    QuoteTally(String wanted, LongSupplier clock) {
        this.wanted = wanted;
        this.clock = clock;
        this.startedNanos = clock.getAsLong();
    }

    /** 这一轮问到现在过了多少毫秒。 */
    long elapsedMillis() {
        return (clock.getAsLong() - startedNanos) / 1_000_000;
    }

    /**
     * 问下一个来源之前看一眼时间：这一轮过了 {@value #PROGRESS_AFTER_MILLIS} 毫秒还没问完，报一行进度——要的是什么、
     * 已经多少毫秒、接下来问哪个来源、到现在问了几次、几遍用了记下的；报过以后隔 {@value #PROGRESS_EVERY_MILLIS} 毫秒
     * 才报下一行。没到线时为空。
     */
    Optional<String> progressBefore(String nextSource) {
        long millis = elapsedMillis();
        if (millis <= nextProgressMillis) return Optional.empty();
        nextProgressMillis = millis + PROGRESS_EVERY_MILLIS;
        return Optional.of("[问价] 问各来源哪里能拿到 " + wanted + " 已经 " + millis + " 毫秒还没问完，接下来问 " + nextSource
                + "；到现在向来源问价 " + quotes() + " 次（其中排做法时为原料问遍各来源 " + ingredientAsks + " 遍，另有 "
                + recalled + " 遍用了这一轮记下的）");
    }

    /** 向一个来源问了一次价、花了这么多纳秒；做法类来源回头问原料价的时间也算在它这一次里。 */
    void quoted(String source, long nanos) {
        nanosBySource.merge(source, nanos, Long::sum);
        quotesBySource.merge(source, 1, Integer::sum);
        slowestQuoteBySource.merge(source, nanos, Math::max);
    }

    /** 排做法时为一样原料问遍了一次各来源。 */
    void askedAround() {
        ingredientAsks++;
    }

    /** 排做法时问的这样原料这一轮问过，用了记下的价，没再问来源。 */
    void recalled() {
        recalled++;
    }

    /** 一轮问价用了这么多毫秒算不算慢：超过线才算，正好压在线上的不记。 */
    static boolean slow(long millis) {
        return millis > SLOW_MILLIS;
    }

    /**
     * 慢日志那一行：要的是什么、用了多久、向来源一共问了几次价、排做法时为原料问遍各来源几遍、
     * 几遍用了这一轮记下的，再列最费时的几个来源。
     */
    String report(long millis) {
        return "[问价] 问各来源哪里能拿到 " + wanted + " 用了 " + millis + " 毫秒，向来源问价 " + quotes()
                + " 次（其中排做法时为原料问遍各来源 " + ingredientAsks + " 遍，另有 " + recalled
                + " 遍用了这一轮记下的）；最费时的来源（含它回头问原料价的时间）：" + slowestSources();
    }

    // 到现在向各来源一共问了几次价。
    private int quotes() {
        return quotesBySource.values().stream().mapToInt(Integer::intValue).sum();
    }

    // 最费时的几个来源：名字、问了几次、一共几毫秒、最慢的一次几毫秒，按花的时间从多到少。
    private String slowestSources() {
        return String.join("；", nanosBySource.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(SLOWEST_SHOWN)
                .map(entry -> entry.getKey() + " " + quotesBySource.get(entry.getKey()) + " 次共 "
                        + entry.getValue() / 1_000_000 + " 毫秒（最慢一次 "
                        + slowestQuoteBySource.get(entry.getKey()) / 1_000_000 + " 毫秒）")
                .toList());
    }
}
