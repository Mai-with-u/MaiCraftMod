// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.GZIPInputStream;

import static org.maiwithu.maicraft.mcp.knowledge.web.WebKnowledgeException.Kind.SITE_REFUSED;
import static org.maiwithu.maicraft.mcp.knowledge.web.WebKnowledgeException.Kind.TIMEOUT;
import static org.maiwithu.maicraft.mcp.knowledge.web.WebKnowledgeException.Kind.UNREACHABLE;
import static org.maiwithu.maicraft.mcp.knowledge.web.WebKnowledgeException.Kind.UNREADABLE;

/**
 * 去白名单站点取一页文字：先核对站点的 robots 规则，同一个站两次请求之间至少隔一段时间（站点写了 Crawl-delay 按它的），
 * 带 gzip 取回再有界解压；跟重定向时每一跳重新过白名单。所有等待都在调用它的 MCP 请求线程上，不碰游戏线程。
 */
public final class WebHttpClient implements WebFetch {
    /** 同一个站两次请求之间至少隔多久：站点没写 Crawl-delay 时按每秒一次的礼貌速度。 */
    static final Duration MINIMUM_INTERVAL = Duration.ofSeconds(1);
    private static final Duration ROBOTS_TTL = Duration.ofHours(6);
    private static final String USER_AGENT = "MaiCraftKnowledge/1.0 (MaiCraft Minecraft mod; on-demand lookup)";
    private static final int MIB = 1024 * 1024;

    private final HttpGet http;
    private final Clock clock;
    private final long minimumIntervalNanos;
    private final Map<String, HostState> hosts = new ConcurrentHashMap<>();

    public WebHttpClient() {
        this(HttpGet.overJavaClient(), Clock.systemUTC(), MINIMUM_INTERVAL);
    }

    // 离线测试注入回放网页的替身、时钟与更短的间隔，验证 robots、限速、重定向与解压，不碰真实站点。
    WebHttpClient(HttpGet http, Clock clock, Duration minimumInterval) {
        this.http = http;
        this.clock = clock;
        this.minimumIntervalNanos = minimumInterval.toNanos();
    }

    /** 一个站的现场：同一时刻只有一个请求在发，记着它的 robots 规则和上一次请求的时刻。 */
    private static final class HostState {
        final ReentrantLock lock = new ReentrantLock();
        RobotsRules robots;
        Instant robotsUntil = Instant.EPOCH;
        long lastRequest;
        boolean requested;
    }

    @Override public Page get(URI original, long deadline) throws IOException, InterruptedException {
        URI uri = original;
        for (int redirects = 0; redirects <= 3; redirects++) {
            WebAccess.requireFetch(uri);
            HostState host = hosts.computeIfAbsent(WebAccess.host(uri), ignored -> new HostState());
            if (!host.lock.tryLock(remaining(deadline, uri), TimeUnit.NANOSECONDS)) throw timeout(uri);
            try {
                // 同一个站的请求排队过同一道间隔；robots 读不到就不读这个站，站点的拒绝页不当成资料。
                RobotsRules robots = robots(uri, host, deadline);
                if (!robots.allows(uri)) throw new WebKnowledgeException(SITE_REFUSED, uri.getHost() + " 的 robots 规则不让程序读这一页");
                boolean sitemap = WebAccess.sitemap(uri);
                HttpGet.Response response = fetch(uri, host, robots.delayMillis(), deadline, (sitemap ? 16 : 4) * MIB);
                if (isRedirect(response.status())) {
                    // 每一跳都回到循环开头重新过白名单：站点把人带去别处时不跟过去。
                    if (response.location().isBlank()) throw new WebKnowledgeException(UNREADABLE, "站点让跳转却没写去处");
                    uri = uri.resolve(response.location());
                    continue;
                }
                return page(uri, response, sitemap);
            } finally {
                host.lock.unlock();
            }
        }
        throw new WebKnowledgeException(UNREADABLE, "跳转超过 3 次，没有读到页面");
    }

    // robots 规则每个站读一次，6 小时后重读；站点没有 robots.txt（404）就是都能读。
    private RobotsRules robots(URI uri, HostState host, long deadline) throws IOException, InterruptedException {
        if (host.robots != null && host.robotsUntil.isAfter(clock.instant())) return host.robots;
        URI policy = URI.create("https://" + uri.getHost() + "/robots.txt");
        HttpGet.Response response = fetch(policy, host, 0, deadline, MIB / 4);
        String text = response.status() == 404 ? "" : text(response, false);
        if (response.status() != 404 && (response.status() != 200 || text.stripLeading().startsWith("<")))
            throw new WebKnowledgeException(SITE_REFUSED, "读不到 " + uri.getHost() + " 的 robots 规则（HTTP " + response.status() + "），没有去读这个站");
        host.robots = new RobotsRules(text);
        host.robotsUntil = clock.instant().plus(ROBOTS_TTL);
        return host.robots;
    }

    // 发一次请求：先按间隔等到能发，再带上站点要的语言发出去；超时与连不上分开说。
    private HttpGet.Response fetch(URI uri, HostState host, long crawlDelayMillis, long deadline, int maximumBytes)
            throws IOException, InterruptedException {
        long interval = Math.max(minimumIntervalNanos, TimeUnit.MILLISECONDS.toNanos(crawlDelayMillis));
        long pause = interval - (System.nanoTime() - host.lastRequest);
        if (host.requested && pause > 0) {
            if (pause >= remaining(deadline, uri)) throw timeout(uri);
            TimeUnit.NANOSECONDS.sleep(pause);
        }
        host.lastRequest = System.nanoTime();
        host.requested = true;
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", USER_AGENT);
        headers.put("Accept", "text/html, application/xml, text/plain");
        headers.put("Accept-Encoding", "gzip");
        // 中文站不带语言时回繁体（港澳台变体），要简体就明说 zh-CN。
        headers.put("Accept-Language", WebAccess.WIKI_EN.equals(WebAccess.host(uri)) ? "en" : "zh-CN,zh;q=0.9");
        try {
            return http.get(uri, headers, remaining(deadline, uri), maximumBytes);
        } catch (HttpTimeoutException late) {
            throw timeout(uri);
        } catch (WebKnowledgeException known) {
            throw known;
        } catch (IOException failure) {
            String reason = failure.getMessage() == null || failure.getMessage().isBlank()
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            throw new WebKnowledgeException(UNREACHABLE, "连不上 " + uri.getHost() + "（" + reason + "），可能没联网或被网络挡住");
        }
    }

    private Page page(URI uri, HttpGet.Response response, boolean sitemap) throws WebKnowledgeException {
        int status = response.status();
        if (status == 404) throw new WebKnowledgeException(WebKnowledgeException.Kind.NOT_FOUND, "站点上没有这一页（HTTP 404）");
        if (status == 429) throw new WebKnowledgeException(SITE_REFUSED, uri.getHost() + " 要求放慢（HTTP 429），过一会儿再查");
        if (status != 200) throw new WebKnowledgeException(SITE_REFUSED, uri.getHost() + " 返回 HTTP " + status);
        String type = response.contentType().toLowerCase(Locale.ROOT);
        if (!(type.contains("text/html") || type.contains("text/plain") || sitemap
                && (type.contains("xml") || type.contains("gzip") || type.contains("octet-stream"))))
            throw new WebKnowledgeException(UNREADABLE, "这个地址回的不是网页（" + response.contentType() + "）");
        return new Page(uri, text(response, sitemap), clock.instant());
    }

    // 传输压缩按响应头解开；英文站的站点地图本身就是 .gz 文件，看开头的魔数再解一层。解压也有上限。
    private static String text(HttpGet.Response response, boolean sitemap) throws WebKnowledgeException {
        int maximum = (sitemap ? 32 : 8) * MIB;
        byte[] bytes = response.content();
        if (response.contentEncoding().toLowerCase(Locale.ROOT).contains("gzip")) bytes = expand(bytes, maximum);
        if (sitemap && bytes.length > 1 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b) bytes = expand(bytes, maximum);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static byte[] expand(byte[] compressed, int maximum) throws WebKnowledgeException {
        try (var stream = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] bytes = stream.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new WebKnowledgeException(UNREADABLE, "解压后超过 " + maximum / MIB + " MiB，没有读");
            return bytes;
        } catch (WebKnowledgeException tooLarge) {
            throw tooLarge;
        } catch (IOException broken) {
            throw new WebKnowledgeException(UNREADABLE, "站点回的压缩数据解不开");
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static long remaining(long deadline, URI uri) throws WebKnowledgeException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw timeout(uri);
        return remaining;
    }

    private static WebKnowledgeException timeout(URI uri) {
        return new WebKnowledgeException(TIMEOUT, "到了这次查询的期限还没从 " + uri.getHost() + " 读完（站点太慢或连不上）");
    }
}
