package com.gewu.agent.engine.core;

import com.gewu.agent.engine.cognition.PerceptionEngine;
import com.gewu.agent.engine.llm.model.Message;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Agent 执行任务 - 描述一次 Agent 执行请求。
 * <p>使用方构建此对象提交给 {@link AgentEngine} 执行。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTask {

    /** Agent 标识（直接对话模式可为 null） */
    private String agentId;
    /** 会话标识 */
    private String sessionId;
    /** 操作用户 */
    private String userId;
    /** 用户消息 */
    private String message;
    /** 历史消息（为空时从 SessionContextService 加载） */
    private List<Message> history;
    /** LLM 供应商标识（显式指定，优先于 AgentSpec） */
    private String modelProvider;
    /** 模型名称（显式指定，优先于 AgentSpec） */
    private String modelName;
    /** Agent 模式：assistant / expert / creative / precise */
    private String agentMode;
    /** 思维模式：chain-of-thought / tree-of-thought / react / step-by-step / socratic */
    private String thinkingStyle;
    /** 采样温度（覆盖模式默认值） */
    private Double temperature;
    /** 最大 token 数（覆盖默认值） */
    private Integer maxTokens;
    /** 感知引擎产出的结构化意图（运行时填充，调用方一般不设） */
    private PerceptionEngine.Intent intent;
}
