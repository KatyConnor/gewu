package com.gewu.agent.engine.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link StreamableHttpClient} Streamable HTTP 传输测试（T3.2，MCP 2025-03-26 规范）。
 * <p>用 JDK HttpServer 模拟 MCP 服务器：JSON 与 SSE 两种响应模式、
 * Mcp-Session-Id 会话管理、initialized 通知握手。
 */
@DisplayName("Streamable HTTP MCP 客户端")
class StreamableHttpClientTest {

    private HttpServer server;
    private String endpoint;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> receivedRequests = new CopyOnWriteArrayList<>();
    private volatile String lastSessionHeader;
    private volatile boolean respondWithSse;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        server.createContext("/mcp", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            receivedRequests.add(body);
            lastSessionHeader = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");

            // initialize 请求分配会话；通知（无 id）直接 202
            if (body.contains("\"method\":\"initialize\"")) {
                byte[] resp = ("{\"jsonrpc\":\"2.0\",\"id\":" + extractId(body)
                        + ",\"result\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{}}}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Mcp-Session-Id", "sess-123");
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
                return;
            }
            if (body.contains("\"method\":\"notifications/initialized\"")) {
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            if (body.contains("\"method\":\"tools/list\"")) {
                String json = "{\"jsonrpc\":\"2.0\",\"id\":" + extractId(body)
                        + ",\"result\":{\"tools\":[{\"name\":\"echo\",\"description\":\"回声\"}]}}";
                if (respondWithSse) {
                    byte[] sse = ("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, sse.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(sse);
                    }
                } else {
                    byte[] resp = json.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, resp.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(resp);
                    }
                }
                return;
            }
            if (body.contains("\"method\":\"tools/call\"")) {
                String json = "{\"jsonrpc\":\"2.0\",\"id\":" + extractId(body)
                        + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"streamable-ok\"}],\"isError\":false}}";
                byte[] resp = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
                return;
            }
            exchange.sendResponseHeaders(404, -1);
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String extractId(String body) {
        try {
            return String.valueOf(objectMapper.readTree(body).path("id").asInt());
        } catch (Exception e) {
            return "0";
        }
    }

    private StreamableHttpClient connectedClient() throws Exception {
        StreamableHttpClient client = new StreamableHttpClient(endpoint, objectMapper);
        client.connect();
        return client;
    }

    @Test
    @DisplayName("initialize 握手：捕获 Mcp-Session-Id 并发送 initialized 通知")
    void initializeHandshakeWithSessionAndNotification() throws Exception {
        try (StreamableHttpClient client = connectedClient()) {
            assertThat(client.isConnected()).isTrue();
        }

        // 请求序列：initialize -> notifications/initialized
        assertThat(receivedRequests).hasSizeGreaterThanOrEqualTo(2);
        assertThat(receivedRequests.get(0)).contains("\"method\":\"initialize\"");
        assertThat(receivedRequests.get(1)).contains("\"method\":\"notifications/initialized\"");
        assertThat(receivedRequests.get(1)).doesNotContain("\"id\"");
    }

    @Test
    @DisplayName("后续请求回传 Mcp-Session-Id 头")
    void sessionHeaderEchoed() throws Exception {
        try (StreamableHttpClient client = connectedClient()) {
            client.listTools();
        }
        // initialize 与通知阶段无会话头；listTools 携带会话
        assertThat(lastSessionHeader).isEqualTo("sess-123");
    }

    @Test
    @DisplayName("JSON 响应模式：tools/list 解析工具清单")
    void listToolsJsonResponse() throws Exception {
        try (StreamableHttpClient client = connectedClient()) {
            List<McpToolDefinition> tools = client.listTools();
            assertThat(tools).hasSize(1);
            assertThat(tools.get(0).getName()).isEqualTo("echo");
            assertThat(tools.get(0).getDescription()).isEqualTo("回声");
        }
    }

    @Test
    @DisplayName("SSE 响应模式：data: 载荷中的 JSON-RPC 响应被解析")
    void listToolsSseResponse() throws Exception {
        respondWithSse = true;
        try (StreamableHttpClient client = connectedClient()) {
            List<McpToolDefinition> tools = client.listTools();
            assertThat(tools).hasSize(1);
            assertThat(tools.get(0).getName()).isEqualTo("echo");
        }
    }

    @Test
    @DisplayName("tools/call 成功：拼接 text 输出")
    void callToolSuccess() throws Exception {
        try (StreamableHttpClient client = connectedClient()) {
            McpToolResult result = client.callTool("echo", "{\"x\":1}");
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOutput()).isEqualTo("streamable-ok");
        }
    }

    @Test
    @DisplayName("服务器错误状态抛异常")
    void serverErrorThrows() {
        // 指向服务器上不存在的路径（HttpServer 直接返回 404）
        String port = endpoint.substring(endpoint.lastIndexOf(':'), endpoint.lastIndexOf('/'));
        StreamableHttpClient client = new StreamableHttpClient(
                "http://127.0.0.1" + port + "/not-exist", objectMapper);
        assertThatThrownBy(client::connect)
                .hasMessageContaining("404");
    }

    @Test
    @DisplayName("McpServerManager 按 streamable_http 传输创建本客户端")
    void managerDispatchesStreamableHttp() {
        McpServerManager manager = new McpServerManager(
                id -> McpServerDescriptor.builder()
                        .id(id).name("mock").transport("streamable_http")
                        .url(endpoint).build(),
                objectMapper);
        McpClient client = manager.getOrConnect("server-1");
        assertThat(client).isInstanceOf(StreamableHttpClient.class);
        assertThat(manager.listTools("server-1")).hasSize(1);
        manager.disconnectAll();
    }
}
