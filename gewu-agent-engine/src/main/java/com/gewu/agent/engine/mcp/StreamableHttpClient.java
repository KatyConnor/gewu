package com.gewu.agent.engine.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Streamable HTTP 传输的 MCP 客户端（MCP 2025-03-26 规范，T3.2）。
 * <p>单一端点 POST JSON-RPC；响应可为 {@code application/json}（直接 JSON-RPC）
 * 或 {@code text/event-stream}（SSE 包裹的 JSON-RPC 响应）。
 * initialize 响应携带的 {@code Mcp-Session-Id} 头在后续请求回传；
 * 握手完成后发送 {@code notifications/initialized} 通知。
 *
 * @since 1.0.0
 */
@Slf4j
public class StreamableHttpClient implements McpClient {

    private final String endpointUrl;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final AtomicInteger requestId = new AtomicInteger(0);

    /** 会话 ID：initialize 响应头下发，后续请求回传 */
    private volatile String sessionId;
    private boolean initialized;

    public StreamableHttpClient(String endpointUrl, ObjectMapper objectMapper) {
        this.endpointUrl = endpointUrl;
        this.mapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public void connect() throws Exception {
        initialize();
        initialized = true;
        log.info("MCP streamable-http client connected: url={}, sessionId={}", endpointUrl, sessionId);
    }

    private void initialize() throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", "2024-11-05");
        params.putObject("capabilities");
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "agent-engine");
        clientInfo.put("version", "1.0.0");

        JsonNode response = sendRequest("initialize", params);
        log.debug("MCP initialize response: {}", response);
        // MCP 协议要求：initialize 响应后发送 initialized 通知
        sendNotification("notifications/initialized");
    }

    @Override
    public List<McpToolDefinition> listTools() {
        try {
            JsonNode response = sendRequest("tools/list", mapper.createObjectNode());
            List<McpToolDefinition> tools = new ArrayList<>();
            JsonNode toolsNode = response.path("tools");
            if (toolsNode.isArray()) {
                for (JsonNode tool : toolsNode) {
                    tools.add(McpToolDefinition.builder()
                            .name(tool.path("name").asText())
                            .description(tool.path("description").asText(""))
                            .inputSchema(tool.path("inputSchema").toString())
                            .build());
                }
            }
            return tools;
        } catch (Exception e) {
            log.error("MCP listTools failed: url={}", endpointUrl, e);
            return new ArrayList<>();
        }
    }

    @Override
    public McpToolResult callTool(String toolName, String arguments) {
        try {
            ObjectNode params = mapper.createObjectNode();
            params.put("name", toolName);
            JsonNode argsNode = mapper.readTree(arguments != null ? arguments : "{}");
            params.set("arguments", argsNode);

            JsonNode response = sendRequest("tools/call", params);
            JsonNode content = response.path("content");
            boolean isError = response.path("isError").asBoolean(false);

            StringBuilder output = new StringBuilder();
            if (content.isArray()) {
                for (JsonNode item : content) {
                    if ("text".equals(item.path("type").asText(""))) {
                        output.append(item.path("text").asText(""));
                    }
                }
            }

            return McpToolResult.builder()
                    .success(!isError)
                    .output(output.toString())
                    .error(isError ? output.toString() : null)
                    .build();
        } catch (Exception e) {
            log.error("MCP callTool failed: tool={}, url={}", toolName, endpointUrl, e);
            return McpToolResult.builder()
                    .success(false)
                    .error("MCP 调用失败: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 发送 JSON-RPC 请求：响应按 Content-Type 分派解析。
     * <ul>
     *   <li>application/json - 响应体即 JSON-RPC 响应</li>
     *   <li>text/event-stream - 逐行扫描 data: 载荷，取匹配请求 id 的 JSON-RPC 响应</li>
     * </ul>
     * 响应携带 Mcp-Session-Id 头时记录会话并在后续请求回传。
     */
    private JsonNode sendRequest(String method, JsonNode params) throws Exception {
        int id = requestId.incrementAndGet();
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.set("params", params);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpointUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)));
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }

        HttpResponse<java.io.InputStream> response =
                httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        String responseSessionId = response.headers().firstValue("Mcp-Session-Id").orElse(null);
        if (responseSessionId != null && !responseSessionId.isBlank()) {
            this.sessionId = responseSessionId;
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String body = new String(response.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            throw new IOException("MCP HTTP " + status + ": " + body);
        }

        String contentType = response.headers().firstValue("Content-Type").orElse("application/json");
        JsonNode responseNode;
        if (contentType.contains("text/event-stream")) {
            responseNode = readJsonRpcFromSse(response.body(), id);
        } else {
            try (java.io.InputStream is = response.body()) {
                responseNode = mapper.readTree(is);
            }
        }
        if (responseNode == null) {
            throw new IOException("MCP 响应缺少匹配 id=" + id + " 的 JSON-RPC 结果");
        }
        if (responseNode.has("error")) {
            JsonNode error = responseNode.get("error");
            throw new IOException("MCP error: " + error.path("message").asText("unknown"));
        }
        return responseNode.path("result");
    }

    /** 从 SSE 流中提取匹配请求 id 的 JSON-RPC 响应（忽略通知与其他请求的响应） */
    private JsonNode readJsonRpcFromSse(java.io.InputStream body, int requestId) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body,
                java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    continue;
                }
                try {
                    JsonNode node = mapper.readTree(data);
                    if (node.has("id") && node.get("id").asInt(-1) == requestId) {
                        return node;
                    }
                } catch (Exception ignore) {
                    // 非 JSON 载荷（如注释行）跳过
                }
            }
        }
        return null;
    }

    /** 发送通知（无 id，服务器 202/200 即视为送达） */
    private void sendNotification(String method) throws Exception {
        ObjectNode notification = mapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpointUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(notification)));
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            log.warn("MCP 通知发送未确认: method={}, status={}", method, response.statusCode());
        }
    }

    @Override
    public void close() {
        initialized = false;
        if (sessionId != null) {
            // 规范建议 DELETE 终止会话；失败不阻断关闭
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(endpointUrl))
                        .timeout(Duration.ofSeconds(5))
                        .header("Mcp-Session-Id", sessionId)
                        .DELETE()
                        .build();
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                log.debug("MCP 会话终止请求失败（忽略）: {}", e.getMessage());
            }
        }
        log.info("MCP streamable-http client closed: url={}", endpointUrl);
    }

    @Override
    public boolean isConnected() {
        return initialized;
    }
}
