package com.gewu.agent.engine.spi;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具配置（框架内工具抽象，描述一个可被 Agent 调用的工具）。
 * <p>由 {@link PersistenceService} SPI 加载。工具类型决定执行通道：
 * <ul>
 *   <li>{@code http} - 通过 HTTP 端点调用</li>
 *   <li>{@code mcp} - 通过 MCP 协议调用</li>
 *   <li>{@code code_execute} - 通过沙箱执行代码</li>
 * </ul>
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolConfig {

    /** 工具名（唯一标识） */
    private String toolName;
    /** 描述 */
    private String description;
    /** JSON Schema 参数定义 */
    private String requestSchema;
    /** 工具类型：http / mcp / code_execute */
    private String toolType;
    /** HTTP 工具的端点地址 */
    private String endpoint;
    /** MCP 工具关联的 Server ID */
    private String mcpServerId;
    /** 执行超时（毫秒） */
    private Integer timeoutMs;
    /** 排序 */
    private Integer sortOrder;
    /** 状态：1 启用 / 0 禁用 */
    private Integer status;
}
