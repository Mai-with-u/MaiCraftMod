// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge.web;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 发一次 HTTP GET，在给定时限内把整个响应读完：联网客户端只经这一个接缝碰网络，
 * 离线测试换成按地址回放固定网页的替身。
 */
@FunctionalInterface
interface HttpGet {
    Response get(URI uri, Map<String, String> headers, long timeoutNanos, int maximumBytes) throws IOException, InterruptedException;

    /** 一次响应：状态码、内容类型、压缩方式、重定向去处、原样的字节。 */
    record Response(int status, String contentType, String contentEncoding, String location, byte[] content) {}

    /**
     * 用 Java 自带的 HTTP 客户端发请求。时限包住整个响应（连同正文），到点就取消这次下载；
     * 调用它的线程一直等到读完或到时限，不另开工作线程（HTTP 客户端自带的收发线程除外）。不自动跟重定向：去处要由调用方重新过白名单。
     */
    static HttpGet overJavaClient() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return (uri, headers, timeoutNanos, maximumBytes) -> {
            // 请求行只能是 ASCII：条目名里的中文在这里编成百分号形式。
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(uri.toASCIIString()))
                    .timeout(Duration.ofNanos(timeoutNanos)).GET();
            headers.forEach(request::header);
            CompletableFuture<HttpResponse<byte[]>> sending =
                    client.sendAsync(request.build(), info -> new BoundedDownload(maximumBytes));
            try {
                var response = sending.get(timeoutNanos, TimeUnit.NANOSECONDS);
                return new Response(response.statusCode(), header(response, "Content-Type"),
                        header(response, "Content-Encoding"), header(response, "Location"), response.body());
            } catch (TimeoutException late) {
                sending.cancel(true);
                throw new HttpTimeoutException("响应没在时限内读完");
            } catch (InterruptedException cancelled) {
                sending.cancel(true);
                throw cancelled;
            } catch (ExecutionException failed) {
                // HTTP 客户端把正文订阅器的异常包了一层：超限的原因原样交出去，别的网络错误照常往上抛。
                for (Throwable cause = failed.getCause(); cause != null; cause = cause.getCause())
                    if (cause instanceof WebKnowledgeException known) throw known;
                if (failed.getCause() instanceof IOException io) throw io;
                throw new IOException(failed.getCause());
            }
        };
    }

    private static String header(HttpResponse<byte[]> response, String name) {
        return response.headers().firstValue(name).orElse("");
    }
}
