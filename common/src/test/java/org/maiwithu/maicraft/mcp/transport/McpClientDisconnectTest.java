// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.transport;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 客户端主动断开 MCP 连接的处理测试：断流异常识别为常态调试事件，且不在已关闭或已发头通道上二次写回 500。 */
class McpClientDisconnectTest {

    /** 模拟底层交换流：可注入读请求体或写响应体时的断流异常，并记录状态码发送次数与关闭状态。 */
    private static final class DisconnectExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final InputStream requestStream;
        private final IOException writeFailure;
        int responseCode = -1;
        int headerSendCount;
        boolean closed;

        DisconnectExchange(InputStream requestStream, IOException writeFailure) {
            this.requestStream = requestStream;
            this.writeFailure = writeFailure;
            requestHeaders.set("Content-Type", "application/json");
        }

        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return URI.create("/mcp"); }
        @Override public String getRequestMethod() { return "POST"; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { closed = true; }
        @Override public InputStream getRequestBody() { return requestStream; }

        @Override
        public OutputStream getResponseBody() {
            return new OutputStream() {
                @Override public void write(int b) throws IOException {
                    if (writeFailure != null) throw writeFailure;
                }

                @Override public void write(byte[] b, int off, int len) throws IOException {
                    if (writeFailure != null) throw writeFailure;
                }
            };
        }

        @Override
        public void sendResponseHeaders(int rCode, long responseLength) throws IOException {
            if (headerSendCount > 0) {
                throw new IOException("headers already sent");
            }
            headerSendCount++;
            responseCode = rCode;
        }

        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 50000); }
        @Override public int getResponseCode() { return responseCode; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 0); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) {}
        @Override public void setStreams(InputStream i, OutputStream o) {}
        @Override public HttpPrincipal getPrincipal() { return null; }
    }

    @Test
    void classifiesClientDisconnectChainsWithoutSwallowingServerBugs() {
        // 常见套接字重置、断管、流已关、提前结束及嵌套包装都归为客户端断流；权限与业务异常保持原级别报错。
        assertTrue(EmbeddedMcpService.isClientDisconnect(new ClosedChannelException()));
        assertTrue(EmbeddedMcpService.isClientDisconnect(new EOFException("premature EOF")));
        assertTrue(EmbeddedMcpService.isClientDisconnect(new IOException("Connection reset by peer")));
        assertTrue(EmbeddedMcpService.isClientDisconnect(new IOException("Broken pipe")));
        assertTrue(EmbeddedMcpService.isClientDisconnect(new IOException("stream is closed")));
        assertTrue(EmbeddedMcpService.isClientDisconnect(
                new RuntimeException(new IOException("An established connection was aborted by the software in your host machine"))));

        assertFalse(EmbeddedMcpService.isClientDisconnect(new IOException("Permission denied")));
        assertFalse(EmbeddedMcpService.isClientDisconnect(new IllegalStateException("unexpected state")));
    }

    @Test
    void disconnectWhileReadingOrWritingClosesCleanlyWithoutSendingSecondary500() throws Exception {
        try (EmbeddedMcpService service = new EmbeddedMcpService(McpConfig.local(0))) {
            // 读请求体半途对端重置连接：直接安静关闭交换对象，不向已断连接补写 500。
            InputStream resetOnRead = new InputStream() {
                @Override public int read() throws IOException {
                    throw new IOException("Connection reset by peer");
                }
            };
            DisconnectExchange abortedRead = new DisconnectExchange(resetOnRead, null);
            service.handle(abortedRead);
            assertEquals(0, abortedRead.headerSendCount, "读请求体断流时不应再发 500 响应头");
            assertTrue(abortedRead.closed, "断流后必须关闭交换通道");

            // 响应头 200 已发出、写响应体时对端断管：不再二次调 sendResponseHeaders(500) 触发 headers already sent。
            byte[] initJson = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"
                    .getBytes(StandardCharsets.UTF_8);
            DisconnectExchange brokenWrite = new DisconnectExchange(
                    new ByteArrayInputStream(initJson), new IOException("Broken pipe"));
            service.handle(brokenWrite);
            assertEquals(1, brokenWrite.headerSendCount, "响应头发出后断流不得二次重发 500 状态头");
            assertEquals(200, brokenWrite.responseCode);
            assertTrue(brokenWrite.closed);
        }
    }

    @Test
    void abruptTcpResetLeavesEmbeddedServiceHealthyForSubsequentRequests() throws Exception {
        // 真实 TCP 套接字发半截 POST 后立即强制重置断开，服务不崩且紧接着的握手请求正常返回 200。
        try (EmbeddedMcpService service = new EmbeddedMcpService(McpConfig.local(0))) {
            service.start();
            try (Socket raw = new Socket("127.0.0.1", service.port())) {
                raw.setSoLinger(true, 0);
                OutputStream out = raw.getOutputStream();
                out.write(("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n"
                        + "Content-Length: 512\r\n\r\n{\"jsonrpc\":").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }

            HttpClient client = HttpClient.newHttpClient();
            URI uri = URI.create("http://127.0.0.1:" + service.port() + "/mcp");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            assertEquals("maicraft", body.getAsJsonObject("result").getAsJsonObject("serverInfo").get("name").getAsString());
        }
    }
}
