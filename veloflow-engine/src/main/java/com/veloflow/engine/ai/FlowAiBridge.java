package com.veloflow.engine.ai;

/**
 * AI 桥接 SPI（51 号 §2.5/§九，P4）：工作流 AI 节点（llm/agent/orchestration/knowledge）
 * 经此桥调用宿主 AI 能力——AI 深度归编排轨，工作流只做调用与消费。
 * <p>引擎零宿主依赖：宿主实现本接口注册为 Spring Bean 即自动接入
 * （参照 FlowIdentityProvider 模式）；未接入时 AI 节点激活以可读错误完成（实例 failed）。
 *
 * @since 1.0.0
 */
public interface FlowAiBridge {

    /** 大模型直调（编排同款客户端栈由宿主选择） */
    LlmResult invokeLlm(LlmSpec spec);

    /** 智能体执行（宿主 ReAct 运行时），sessionId 贯通工作流实例可追溯 */
    AgentResult invokeAgent(AgentSpec spec);

    /** 编排图同步执行（active 校验 + 时长帽由宿主实现约束） */
    OrchestrationResult invokeOrchestration(String graphId, String inputJson, String workflowInstanceId);

    /** 知识检索（宿主检索源：知识库/Wenshi 搜索适配） */
    KnowledgeResult searchKnowledge(String knowledgeBaseId, String query, int topK);

    /** 大模型调用规约 */
    record LlmSpec(String modelProvider, String modelName, String prompt,
                   Double temperature, Integer maxTokens) {
    }

    /** 大模型调用结果（totalTokens 供实例级预算记账） */
    record LlmResult(String content, Integer totalTokens, String model) {
    }

    /** 智能体调用规约 */
    record AgentSpec(String agentId, String task, String workflowInstanceId, String workflowName) {
    }

    /** 智能体执行结果 */
    record AgentResult(String output, Integer totalTokens) {
    }

    /** 编排执行结果 */
    record OrchestrationResult(String status, String executionId, String output, String errorMessage) {
    }

    /** 检索命中片段 */
    record KnowledgeChunk(String title, String snippet, String source) {
    }

    /** 检索结果 */
    record KnowledgeResult(java.util.List<KnowledgeChunk> chunks, String sourceSummary) {
    }
}
