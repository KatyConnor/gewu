package com.gewu.infrastructure.mcp;

import java.util.List;

public interface McpClient {

    void connect() throws Exception;

    void close();

    boolean isConnected();

    List<McpToolDefinition> listTools();

    McpToolResult callTool(String toolName, String arguments);
}
