package com.gewu.agent.engine.mcp;

import java.util.List;

/**
 * MCP（Model Context Protocol）客户端接口。
 * <p>支持 stdio 与 streamable_http / sse 传输方式；实现 AutoCloseable
 * 以支持 try-with-resources 管理（T3.2）。
 *
 * @since 1.0.0
 */
public interface McpClient extends AutoCloseable {

    /** 建立连接并完成 initialize 握手 */
    void connect() throws Exception;

    /** 关闭连接 */
    void close();

    /** 是否已连接 */
    boolean isConnected();

    /** 列出服务器提供的工具 */
    List<McpToolDefinition> listTools();

    /** 调用工具 */
    McpToolResult callTool(String toolName, String arguments);
}
