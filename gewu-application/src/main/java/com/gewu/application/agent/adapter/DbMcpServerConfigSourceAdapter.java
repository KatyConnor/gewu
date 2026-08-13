package com.gewu.application.agent.adapter;

import com.gewu.agent.engine.mcp.McpServerConfigSource;
import com.gewu.agent.engine.mcp.McpServerDescriptor;
import com.gewu.domain.agent.McpServer;
import com.gewu.infrastructure.mapper.McpServerMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * {@link McpServerConfigSource} 业务适配 - 从 mcp_server 表加载 MCP 服务器配置。
 *
 * @since 1.0.0
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.engine.adapter.enabled", havingValue = "true")
public class DbMcpServerConfigSourceAdapter implements McpServerConfigSource {

    private final McpServerMapper mcpServerMapper;

    @Override
    public McpServerDescriptor loadServer(String serverId) {
        McpServer server = mcpServerMapper.selectById(serverId);
        if (server == null) {
            return null;
        }
        return McpServerDescriptor.builder()
                .id(server.getId())
                .name(server.getName())
                .transport(server.getTransport())
                .command(server.getCommand())
                .args(server.getArgs())
                .env(server.getEnv())
                .url(server.getUrl())
                .status(server.getStatus())
                .build();
    }

    @Override
    public List<McpServerDescriptor> loadAllServers() {
        return mcpServerMapper.selectList(null).stream()
                .map(server -> McpServerDescriptor.builder()
                        .id(server.getId())
                        .name(server.getName())
                        .transport(server.getTransport())
                        .command(server.getCommand())
                        .args(server.getArgs())
                        .env(server.getEnv())
                        .url(server.getUrl())
                        .status(server.getStatus())
                        .build())
                .toList();
    }
}