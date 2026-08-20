package com.gewu.agent.engine.mcp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MCP 服务器配置描述（框架内配置模型，替代业务侧持久化实体）。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpServerDescriptor {

    /** 服务器标识 */
    private String id;
    /** 名称 */
    private String name;
    /** 传输方式：stdio / sse / streamable_http */
    private String transport;
    /** stdio 启动命令 */
    private String command;
    /** stdio 参数（JSON 数组字符串） */
    private String args;
    /** stdio 环境变量（JSON 对象字符串） */
    private String env;
    /** SSE / HTTP 端点地址 */
    private String url;
    /** 状态：1 启用 / 0 禁用 */
    private Integer status;
}
