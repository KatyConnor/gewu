package com.gewu.agent.engine.spi;

/**
 * 权限评估服务 SPI - 工具调用前的权限检查。
 * <p>使用方实现此接口，基于角色 / 规则 / 策略评估 Agent 是否有权调用某工具。
 * 框架提供 {@code NoOpPermissionService} 默认实现（全允许）。
 *
 * @since 1.0.0
 */
public interface PermissionService {

    /**
     * 评估 Agent 对工具的调用权限。
     *
     * @param agentId   Agent 标识
     * @param toolName  工具名
     * @param resource  资源标识（可空）
     * @return 权限评估结果
     */
    PermissionResult evaluate(String agentId, String toolName, String resource);
}
