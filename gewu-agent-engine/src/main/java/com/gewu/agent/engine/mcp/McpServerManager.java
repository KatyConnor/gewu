package com.gewu.agent.engine.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 服务器管理器 - 注册 / 发现 / 连接 MCP 服务器。
 * <p>服务器配置通过 {@link McpServerConfigSource} SPI 加载（不绑定数据库）。
 * 连接后客户端缓存复用，避免重复握手。
 *
 * @since 1.0.0
 */
@Slf4j
public class McpServerManager {

    private final McpServerConfigSource configSource;
    private final ObjectMapper objectMapper;
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();

    public McpServerManager(McpServerConfigSource configSource) {
        this(configSource, new ObjectMapper());
    }

    /** 复用 Spring 容器 ObjectMapper（D-14：消除私有 new） */
    public McpServerManager(McpServerConfigSource configSource, ObjectMapper objectMapper) {
        this.configSource = configSource;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    /** 获取或建立与指定服务器的连接 */
    public McpClient getOrConnect(String serverId) {
        return clients.computeIfAbsent(serverId, this::createAndConnect);
    }

    private McpClient createAndConnect(String serverId) {
        if (configSource == null) {
            throw new IllegalArgumentException("未配置 McpServerConfigSource，无法加载 MCP Server: " + serverId);
        }
        McpServerDescriptor server = configSource.loadServer(serverId);
        if (server == null) {
            throw new IllegalArgumentException("MCP Server 不存在: " + serverId);
        }

        McpClient client;
        String transport = server.getTransport() != null ? server.getTransport() : "stdio";

        if ("stdio".equals(transport)) {
            List<String> args = parseArgs(server.getArgs());
            Map<String, String> env = parseEnv(server.getEnv());
            client = new StdioMcpClient(server.getCommand(), args, env);
        } else if ("streamable_http".equals(transport)) {
            // MCP 2025-03-26 Streamable HTTP（推荐：含会话管理与 initialized 握手）
            client = new StreamableHttpClient(server.getUrl(), objectMapper);
        } else if ("sse".equals(transport)) {
            // 兼容既有 sse 配置（已废弃，建议迁移 streamable_http）
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

    /** 断开指定服务器连接 */
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

    /** 断开所有连接 */
    public void disconnectAll() {
        clients.keySet().forEach(this::disconnect);
    }

    /** 列出指定服务器的工具 */
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
