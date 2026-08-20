package com.gewu.agent.engine.mcp;

import java.util.List;

/**
 * MCP 服务器配置源 SPI - 从外部来源加载 MCP 服务器配置。
 * <p>使用方实现此接口，从数据库 / 配置文件加载 MCP 服务器定义。
 * 框架提供 {@code NoOpMcpServerConfigSource} 默认实现（返回空），此时 MCP 工具不可用。
 *
 * @since 1.0.0
 */
public interface McpServerConfigSource {

    /** 加载指定服务器配置，不存在返回 null */
    McpServerDescriptor loadServer(String serverId);

    /** 加载全部服务器配置 */
    default List<McpServerDescriptor> loadAllServers() {
        return List.of();
    }
}
