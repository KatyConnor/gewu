package com.gewu.agent.engine.mcp;

import java.util.List;

/**
 * {@link McpServerConfigSource} 的 NoOp 默认实现 - 不提供 MCP 服务器配置。
 *
 * @since 1.0.0
 */
public class NoOpMcpServerConfigSource implements McpServerConfigSource {

    @Override
    public McpServerDescriptor loadServer(String serverId) {
        return null;
    }

    @Override
    public List<McpServerDescriptor> loadAllServers() {
        return List.of();
    }
}
