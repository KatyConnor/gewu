package com.gewu.agent.engine.spi;

import java.util.List;
import java.util.Map;

/**
 * 追踪服务 SPI - 编排执行轨迹的记录与查询。
 * <p>共享知识层的追踪组件，贯穿所有 Agent 调用，记录执行轨迹。
 * 框架提供默认空实现（NoOp），使用方实现以对接持久化存储。
 *
 * @since 1.0.0
 */
public interface TraceService {

    /**
     * 记录执行轨迹。
     */
    void recordTrace(String executionId, String nodeId, String phase, String action, String detail);

    /**
     * 查询执行实例的轨迹。
     */
    default List<Map<String, Object>> queryTraces(String executionId) {
        return List.of();
    }

    /**
     * 查询协作会话的轨迹。
     */
    default List<Map<String, Object>> queryCollaborationTraces(String collaborationId) {
        return List.of();
    }

    /**
     * 开启一个 Span（供 OTel/Micrometer 桥接适配器实现，返回不透明句柄）。
     *
     * @param executionId 执行实例 ID
     * @param nodeId      节点 ID（可 null）
     * @param operation   操作名（如 llm_call / tool_call / iteration）
     * @return Span 句柄（未实现时返回 null，调用方按空安全处理）
     */
    default Object startSpan(String executionId, String nodeId, String operation) {
        return null;
    }

    /**
     * 结束 Span（成功）。
     */
    default void endSpan(Object span) {
    }

    /**
     * 结束 Span（失败）。
     */
    default void endSpanWithError(Object span, Throwable error) {
    }
}