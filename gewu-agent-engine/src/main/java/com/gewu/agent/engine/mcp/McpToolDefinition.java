package com.gewu.agent.engine.mcp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MCP 工具定义。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpToolDefinition {

    private String name;
    private String description;
    /** JSON Schema 输入参数定义 */
    private String inputSchema;
}
