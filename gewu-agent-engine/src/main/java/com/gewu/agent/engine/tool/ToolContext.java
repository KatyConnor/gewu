package com.gewu.agent.engine.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具执行上下文 - 携带执行时环境信息。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolContext {

    /** 操作用户 */
    private String userId;
    /** 会话标识 */
    private String sessionId;
    /** Agent 标识 */
    private String agentId;
    /** 执行超时（秒） */
    private int timeout;
    /** 是否启用沙箱 */
    private boolean sandboxEnabled;
    /** 沙箱镜像 */
    private String sandboxImage;
    /** 项目标识 */
    private String projectId;
}
