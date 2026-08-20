package com.gewu.agent.engine.spi;

/**
 * 审计服务 SPI - 记录工具执行等操作审计日志。
 * <p>使用方实现此接口，将审计事件写入 audit_log 表或外部审计系统。
 * 框架提供 {@code NoOpAuditService} 默认实现（仅日志输出）。
 *
 * @since 1.0.0
 */
public interface AuditService {

    /**
     * 记录工具执行审计。
     *
     * @param userId    操作用户
     * @param agentId   Agent 标识
     * @param toolName  工具名
     * @param success   是否成功
     * @param durationMs 耗时（毫秒）
     */
    void recordToolExecution(String userId, String agentId, String toolName, boolean success, long durationMs);

    /**
     * 记录 Agent 执行审计。
     *
     * @param userId    操作用户
     * @param agentId   Agent 标识
     * @param sessionId 会话标识
     * @param success   是否成功
     * @param durationMs 耗时（毫秒）
     */
    default void recordAgentExecution(String userId, String agentId, String sessionId,
                                      boolean success, long durationMs) {
    }
}
