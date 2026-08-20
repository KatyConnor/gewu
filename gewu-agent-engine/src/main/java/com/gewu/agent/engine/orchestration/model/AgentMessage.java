package com.gewu.agent.engine.orchestration.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Agent 间标准化通信消息信封。
 * <p>替代裸字符串传递，支持结构化 payload、路由指令、追踪上下文。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentMessage {

    /** 消息 ID */
    private String messageId;
    /** 发送方 Agent ID */
    private String fromAgent;
    /** 接收方 Agent ID（null 表示广播） */
    private String toAgent;
    /** 协作会话 ID */
    private String conversationId;
    /** 消息类型 */
    private MessageType messageType;
    /** 消息负载（结构化数据） */
    private Object payload;
    /** 回复的消息 ID */
    private String replyTo;
    /** 时间戳 */
    private long timestamp;
    /** 追踪上下文 */
    private Map<String, Object> traceContext;
    /** 委托权限 */
    private List<String> delegatedPermissions;
    /** 预算上下文 */
    private Object budgetContext;

    /**
     * 消息类型枚举。
     */
    public enum MessageType {
        /** 任务委托 */
        TASK_DELEGATE,
        /** 任务结果 */
        TASK_RESULT,
        /** 查询 */
        QUERY,
        /** 查询响应 */
        QUERY_RESPONSE,
        /** 审查请求 */
        REVIEW_REQUEST,
        /** 审查结果 */
        REVIEW_RESULT,
        /** 冲突上报 */
        CONFLICT_RAISE,
        /** 上下文同步 */
        CONTEXT_SYNC,
        /** 控制权移交 */
        HANDOFF,
        /** 结束 */
        FINISH,
        /** 心跳 */
        HEARTBEAT
    }

    public static AgentMessage delegate(String from, String to, String conversationId, Object payload) {
        return AgentMessage.builder()
                .messageId(java.util.UUID.randomUUID().toString())
                .fromAgent(from)
                .toAgent(to)
                .conversationId(conversationId)
                .messageType(MessageType.TASK_DELEGATE)
                .payload(payload)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static AgentMessage result(String from, String to, String conversationId, Object payload) {
        return AgentMessage.builder()
                .messageId(java.util.UUID.randomUUID().toString())
                .fromAgent(from)
                .toAgent(to)
                .conversationId(conversationId)
                .messageType(MessageType.TASK_RESULT)
                .payload(payload)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static AgentMessage handoff(String from, String to, String conversationId) {
        return AgentMessage.builder()
                .messageId(java.util.UUID.randomUUID().toString())
                .fromAgent(from)
                .toAgent(to)
                .conversationId(conversationId)
                .messageType(MessageType.HANDOFF)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static AgentMessage finish(String from, String conversationId) {
        return AgentMessage.builder()
                .messageId(java.util.UUID.randomUUID().toString())
                .fromAgent(from)
                .toAgent(null)
                .conversationId(conversationId)
                .messageType(MessageType.FINISH)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}