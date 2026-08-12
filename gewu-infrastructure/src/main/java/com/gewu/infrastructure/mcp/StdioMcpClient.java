package com.gewu.infrastructure.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class StdioMcpClient implements McpClient {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger requestId = new AtomicInteger(0);

    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;
    private boolean initialized;

    private final String command;
    private final List<String> args;
    private final Map<String, String> env;

    public StdioMcpClient(String command, List<String> args, Map<String, String> env) {
        this.command = command;
        this.args = args != null ? args : new ArrayList<>();
        this.env = env;
    }

    @Override
    public void connect() throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (!args.isEmpty()) {
            pb = new ProcessBuilder(command);
            pb.command().addAll(args);
        }
        if (env != null && !env.isEmpty()) {
            pb.environment().putAll(env);
        }
        pb.redirectErrorStream(false);
        process = pb.start();
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
        stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));

        initialize();
        initialized = true;
        log.info("MCP stdio client connected: command={}", command);
    }

    private void initialize() throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", "2024-11-05");
        params.putObject("capabilities");
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "gewu-platform");
        clientInfo.put("version", "1.0.0");

        JsonNode response = sendRequest("initialize", params);
        log.debug("MCP initialize response: {}", response);
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
            log.error("MCP listTools failed", e);
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
                    String type = item.path("type").asText("");
                    if ("text".equals(type)) {
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
            log.error("MCP callTool failed: tool={}", toolName, e);
            return McpToolResult.builder()
                    .success(false)
                    .error("MCP 调用失败: " + e.getMessage())
                    .build();
        }
    }

    private JsonNode sendRequest(String method, JsonNode params) throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", requestId.incrementAndGet());
        request.put("method", method);
        request.set("params", params);

        String json = mapper.writeValueAsString(request) + "\n";
        stdin.write(json);
        stdin.flush();

        String line = stdout.readLine();
        if (line == null) {
            throw new IOException("MCP server closed connection");
        }
        JsonNode response = mapper.readTree(line);

        if (response.has("error")) {
            JsonNode error = response.get("error");
            throw new IOException("MCP error: " + error.path("message").asText("unknown"));
        }
        return response.path("result");
    }

    @Override
    public void close() {
        initialized = false;
        try {
            if (stdin != null) stdin.close();
        } catch (IOException e) {
            log.debug("Error closing stdin", e);
        }
        try {
            if (stdout != null) stdout.close();
        } catch (IOException e) {
            log.debug("Error closing stdout", e);
        }
        if (process != null) {
            process.destroyForcibly();
        }
        log.info("MCP stdio client closed: command={}", command);
    }

    @Override
    public boolean isConnected() {
        return initialized && process != null && process.isAlive();
    }
}
