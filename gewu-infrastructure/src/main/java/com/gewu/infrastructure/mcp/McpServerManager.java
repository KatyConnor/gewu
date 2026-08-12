package com.gewu.infrastructure.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.domain.agent.McpServer;
import com.gewu.infrastructure.mapper.McpServerMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class McpServerManager {

    private final McpServerMapper mcpServerMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();

    public McpClient getOrConnect(String serverId) {
        return clients.computeIfAbsent(serverId, this::createAndConnect);
    }

    private McpClient createAndConnect(String serverId) {
        McpServer server = mcpServerMapper.selectById(serverId);
        if (server == null) {
            throw new IllegalArgumentException("MCP Server 不存在: " + serverId);
        }

        McpClient client;
        String transport = server.getTransport() != null ? server.getTransport() : "stdio";

        if ("stdio".equals(transport)) {
            List<String> args = parseArgs(server.getArgs());
            Map<String, String> env = parseEnv(server.getEnv());
            client = new StdioMcpClient(server.getCommand(), args, env);
        } else if ("sse".equals(transport) || "streamable_http".equals(transport)) {
            client = new SseMcpClient(server.getUrl());
        } else {
            throw new IllegalArgumentException("不支持的传输方式: " + transport);
        }

        try {
            client.connect();
        } catch (Exception e) {
            log.error("MCP Server 连接失败: serverId={}, name={}", serverId, server.getName(), e);
            throw new RuntimeException("MCP Server 连接失败: " + e.getMessage(), e);
        }

        return client;
    }

    public void disconnect(String serverId) {
        McpClient client = clients.remove(serverId);
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("MCP Server 断开连接时出错: serverId={}", serverId, e);
            }
        }
    }

    public void disconnectAll() {
        clients.keySet().forEach(this::disconnect);
    }

    public List<McpToolDefinition> listTools(String serverId) {
        McpClient client = getOrConnect(serverId);
        return client.listTools();
    }

    private List<String> parseArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(argsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("解析 MCP args 失败: {}", argsJson, e);
            return List.of();
        }
    }

    private Map<String, String> parseEnv(String envJson) {
        if (envJson == null || envJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(envJson, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("解析 MCP env 失败: {}", envJson, e);
            return Map.of();
        }
    }
}
