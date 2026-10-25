// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge.web;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * 联网查资料：在白名单站点里按标题搜（Minecraft Wiki 中英文站），按地址读一篇（Wiki 与 MC百科）。
 *
 * <p>全部在调用它的 MCP 请求线程上做，不碰游戏线程。一次查询有总期限，同时最多做两次，
 * 读过的条目在内存里留一会儿（有篇数与字数上限），同一篇翻页、搜完再读都不再联网。
 * 启动开关关着时不发任何请求。
 */
public final class WebKnowledgeService {
    /** 一次查询的总期限：搜索加读开头一句，或者读一篇，都要在这么久里答完。 */
    public static final Duration BUDGET = Duration.ofSeconds(10);
    /** 搜索最多给几条：每条都要读一次开头一句，条数决定一次搜索要等多久。 */
    static final int RESULTS = 3;
    private static final int CONCURRENT_QUERIES = 2;
    private static final Duration ARTICLE_TTL = Duration.ofHours(1);
    private static final int CACHED_ARTICLES = 32;
    private static final int CACHED_CHARS = 2 * 1024 * 1024;

    private final boolean enabled;
    private final String minecraftVersion;
    private final Clock clock;
    private final WikiTitleIndex titles;
    private final MinecraftWikiSource wiki;
    private final McmodSource mcmod;
    private final Semaphore slots = new Semaphore(CONCURRENT_QUERIES);
    private final LinkedHashMap<URI, WikiArticle> cache = new LinkedHashMap<>(16, .75f, true);
    private int cachedChars;

    /**
     * @param fetch            取网页的那一层（生产用 {@link WebHttpClient}）
     * @param enabled          启动开关：关着时一个请求也不发，lookup 照实说关着
     * @param minecraftVersion 本实例的 Minecraft 版本：随回答给出，网页写的版本对不上时一眼看得出
     */
    public WebKnowledgeService(WebFetch fetch, boolean enabled, String minecraftVersion) {
        this(fetch, enabled, minecraftVersion, Clock.systemUTC());
    }

    // 离线测试注入时钟，验证缓存过期不用真的等一小时。
    WebKnowledgeService(WebFetch fetch, boolean enabled, String minecraftVersion, Clock clock) {
        this.enabled = enabled;
        this.minecraftVersion = minecraftVersion;
        this.clock = clock;
        this.titles = new WikiTitleIndex(fetch, clock);
        this.wiki = new MinecraftWikiSource(fetch);
        this.mcmod = new McmodSource(fetch);
    }

    /** 关着的联网查询：离线测试、接口快照与没有网络栈的场合用，一个请求也不发。 */
    public static WebKnowledgeService off() {
        return new WebKnowledgeService((uri, deadline) -> {
            throw new WebKnowledgeException(WebKnowledgeException.Kind.UNREACHABLE, "联网查询关着");
        }, false, "unknown");
    }

    public boolean enabled() { return enabled; }

    public String minecraftVersion() { return minecraftVersion; }

    /**
     * 一次搜索的回答：搜的哪个站（是不是中文站：没搜到时建议换另一种语言的名字）、标题对得上的总条数、
     * 排在最前的几条（带开头一句），以及哪几条没读到开头一句、为什么。
     */
    public record SearchAnswer(String site, boolean chineseSite, int total, List<Found> results, List<String> unreadSummaries) {}

    /** 搜到的一条：标题、地址、开头一句（没读到时为空串）。 */
    public record Found(String title, URI url, String summary) {}

    /**
     * 按标题搜：查询里有汉字就搜中文站，否则搜英文站。排在最前的几条逐条读开头一句（读到的整篇进缓存，
     * 接着读它不用再联网）；期限快到或某一条读不了时，那几条不带开头一句，原因如实交回。
     */
    public SearchAnswer search(String query) throws IOException, InterruptedException {
        acquire();
        try {
            long deadline = System.nanoTime() + BUDGET.toNanos();
            String host = query.codePoints().anyMatch(WebKnowledgeService::isHan) ? WebAccess.WIKI_ZH : WebAccess.WIKI_EN;
            WikiTitleIndex.Search found = titles.search(host, query, RESULTS, deadline);
            List<Found> results = new ArrayList<>();
            Map<String, List<String>> unread = new LinkedHashMap<>();
            for (WikiTitleIndex.Hit hit : found.hits()) {
                String summary = "";
                try {
                    summary = article(hit.uri(), deadline).summary();
                } catch (IOException failed) {
                    // 一条读不了不拖累别的：标题和地址照给；同样的原因合在一起说一次（期限到了时后面几条都是这一句）。
                    unread.computeIfAbsent(reason(failed), reason -> new ArrayList<>()).add(hit.title());
                }
                results.add(new Found(hit.title(), hit.uri(), summary));
            }
            List<String> reasons = new ArrayList<>();
            unread.forEach((reason, missed) -> reasons.add(String.join("、", missed) + "：" + reason));
            return new SearchAnswer(WebAccess.siteName(URI.create("https://" + host + "/")), host.equals(WebAccess.WIKI_ZH),
                    found.total(), results, reasons);
        } finally {
            slots.release();
        }
    }

    /** 按地址读一篇：先看缓存，过期或没读过才联网。地址要先经 {@link WebAccess#article} 整理。 */
    public WikiArticle read(URI article) throws IOException, InterruptedException {
        acquire();
        try {
            return article(article, System.nanoTime() + BUDGET.toNanos());
        } finally {
            slots.release();
        }
    }

    private WikiArticle article(URI uri, long deadline) throws IOException, InterruptedException {
        synchronized (cache) {
            WikiArticle known = cache.get(uri);
            if (known != null && known.fetchedAt().plus(ARTICLE_TTL).isAfter(clock.instant())) return known;
        }
        WikiArticle fresh = WebAccess.wiki(uri) ? wiki.read(uri, deadline) : mcmod.read(uri, deadline);
        remember(uri, fresh);
        return fresh;
    }

    // 缓存按最近读过的留着：超过篇数或总字数就先丢最久没读的那篇。
    private void remember(URI uri, WikiArticle article) {
        synchronized (cache) {
            WikiArticle old = cache.put(uri, article);
            cachedChars += article.chars() - (old == null ? 0 : old.chars());
            while (cache.size() > 1 && (cache.size() > CACHED_ARTICLES || cachedChars > CACHED_CHARS)) {
                URI eldest = cache.keySet().iterator().next();
                cachedChars -= cache.remove(eldest).chars();
            }
        }
    }

    // 同时最多两次联网查询；满了立刻回答忙，不排队等一个调用方可能已经放弃的请求。
    private void acquire() throws WebKnowledgeException {
        if (!slots.tryAcquire())
            throw new WebKnowledgeException(WebKnowledgeException.Kind.BUSY,
                    "同时在做的联网查询已经有 " + CONCURRENT_QUERIES + " 个，这一次没开始");
    }

    private static String reason(IOException failed) {
        return failed.getMessage() == null || failed.getMessage().isBlank() ? failed.getClass().getSimpleName() : failed.getMessage();
    }

    private static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }
}
