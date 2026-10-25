// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

/** 联网客户端用替身 HTTP 回放网页：robots 规则、站外跳转、同站限速、解压、超时与连不上都不碰真实站点。 */
class WebHttpClientTest {
    private static final URI STONE = URI.create("https://minecraft.wiki/w/Stone");
    private static final URI ZH_STONE = WebAccess.article("https://zh.minecraft.wiki/w/石头");

    @Test
    void robotsRulesRefuseThePageWithoutRequestingIt() {
        FakeHttp http = new FakeHttp();
        http.page("https://minecraft.wiki/robots.txt", "User-agent: *\nDisallow: /w/Stone\n");
        http.page(STONE.toString(), "<html>stone</html>");
        WebKnowledgeException refused = assertThrows(WebKnowledgeException.class, () -> client(http).get(STONE, soon()));
        assertEquals(WebKnowledgeException.Kind.SITE_REFUSED, refused.kind());
        assertFalse(http.requested.contains(STONE.toString()), "robots 不让读的那一页不发请求");
    }

    @Test
    void redirectsLeavingTheAllowedSitesAreNotFollowed() {
        FakeHttp http = new FakeHttp();
        http.robotsAllowAll("minecraft.wiki");
        http.responses.put(STONE.toString(), new HttpGet.Response(302, "text/html", "", "https://evil.example/w/Stone", new byte[0]));
        WebKnowledgeException refused = assertThrows(WebKnowledgeException.class, () -> client(http).get(STONE, soon()));
        assertEquals(WebKnowledgeException.Kind.SITE_REFUSED, refused.kind());
        assertTrue(http.requested.stream().noneMatch(uri -> uri.contains("evil.example")), "跳去白名单外的地址不跟过去");
    }

    @Test
    void gzipPagesAreExpandedAndTheChineseSiteIsAskedForSimplifiedChinese() throws Exception {
        FakeHttp http = new FakeHttp();
        http.robotsAllowAll("zh.minecraft.wiki");
        http.responses.put(ZH_STONE.toASCIIString(), new HttpGet.Response(200, "text/html; charset=UTF-8", "gzip", "",
                gzip("<html>石头是一种方块</html>")));
        WebFetch.Page page = client(http).get(ZH_STONE, soon());
        assertEquals("<html>石头是一种方块</html>", page.body());
        assertTrue(http.lastHeaders.get("Accept-Language").startsWith("zh-CN"), "中文站要简体");
        assertEquals("gzip", http.lastHeaders.get("Accept-Encoding"));
    }

    @Test
    void requestsToTheSameSiteKeepTheMinimumInterval() throws Exception {
        FakeHttp http = new FakeHttp();
        http.robotsAllowAll("minecraft.wiki");
        http.page(STONE.toString(), "<html>stone</html>");
        long started = System.nanoTime();
        // 先读 robots 再读条目，同一个站的两次请求之间至少隔 150 毫秒。
        new WebHttpClient(http, Clock.systemUTC(), Duration.ofMillis(150)).get(STONE, soon());
        assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(150), "同一个站的请求按间隔排开");
    }

    @Test
    void slowAndUnreachableSitesAreToldApart() {
        FakeHttp slow = new FakeHttp();
        slow.robotsAllowAll("minecraft.wiki");
        slow.failure = new HttpTimeoutException("slow");
        assertEquals(WebKnowledgeException.Kind.TIMEOUT,
                assertThrows(WebKnowledgeException.class, () -> client(slow).get(STONE, soon())).kind());

        FakeHttp offline = new FakeHttp();
        offline.robotsAllowAll("minecraft.wiki");
        offline.failure = new ConnectException("Connection refused");
        WebKnowledgeException unreachable = assertThrows(WebKnowledgeException.class, () -> client(offline).get(STONE, soon()));
        assertEquals(WebKnowledgeException.Kind.UNREACHABLE, unreachable.kind());
        assertTrue(unreachable.getMessage().contains("minecraft.wiki"), "说清连不上的是哪个站");
    }

    @Test
    void missingPagesAndPassedDeadlinesHaveTheirOwnKinds() {
        FakeHttp http = new FakeHttp();
        http.robotsAllowAll("minecraft.wiki");
        http.responses.put(STONE.toString(), new HttpGet.Response(404, "text/html", "", "", new byte[0]));
        assertEquals(WebKnowledgeException.Kind.NOT_FOUND,
                assertThrows(WebKnowledgeException.class, () -> client(http).get(STONE, soon())).kind());

        FakeHttp untouched = new FakeHttp();
        assertEquals(WebKnowledgeException.Kind.TIMEOUT,
                assertThrows(WebKnowledgeException.class, () -> client(untouched).get(STONE, System.nanoTime() - 1)).kind());
        assertTrue(untouched.requested.isEmpty(), "期限已过就不再发请求");
    }

    private static WebHttpClient client(FakeHttp http) {
        return new WebHttpClient(http, Clock.systemUTC(), Duration.ZERO);
    }

    private static long soon() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    }

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    /** 按地址回放固定响应的 HTTP 替身：记下请求过哪些地址、最后一次带了什么请求头；可以设成一律超时或连不上。 */
    private static final class FakeHttp implements HttpGet {
        final Map<String, Response> responses = new HashMap<>();
        final List<String> requested = new ArrayList<>();
        Map<String, String> lastHeaders = Map.of();
        IOException failure;

        void page(String uri, String html) {
            responses.put(URI.create(uri).toASCIIString(), new Response(200, "text/html", "", "", html.getBytes(StandardCharsets.UTF_8)));
        }

        void robotsAllowAll(String host) {
            responses.put("https://" + host + "/robots.txt",
                    new Response(200, "text/plain", "", "", "User-agent: *\nAllow: /\n".getBytes(StandardCharsets.UTF_8)));
        }

        @Override public Response get(URI uri, Map<String, String> headers, long timeoutNanos, int maximumBytes) throws IOException {
            requested.add(uri.toASCIIString());
            lastHeaders = Map.copyOf(headers);
            if (failure != null && !uri.getPath().equals("/robots.txt")) throw failure;
            Response response = responses.get(uri.toASCIIString());
            if (response == null) return new Response(404, "text/html", "", "", new byte[0]);
            return response;
        }
    }
}
