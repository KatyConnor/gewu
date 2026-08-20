package com.gewu.application.agent;

import com.gewu.application.agent.dto.AgentChunk;
import com.gewu.application.agent.dto.AgentExecutionRequest;
import com.gewu.agent.engine.core.AgentExecutor;
import com.gewu.agent.engine.core.AgentTask;
import com.gewu.agent.engine.core.event.AgentEvent;
import com.gewu.common.context.UserContext;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.agent.Agent;
import com.gewu.domain.session.Session;
import com.gewu.infrastructure.llm.LlmResponse;
import com.gewu.infrastructure.llm.ToolCall;
import com.gewu.infrastructure.mapper.AgentMapper;
import com.gewu.infrastructure.mapper.SessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Agent 执行引擎 - 双引擎收敛后的统一入口。
 * <p>原先在此类内维护一套与 agent-engine 重复的 ReAct 循环（无安全/记忆/预算/路由能力），
 * 现委托给框架 {@link ReactAgentExecutor}（经 {@link AgentExecutor} SPI 注入），
 * 聊天主链路由此获得安全纵深五层、记忆注入、预算熔断、感知与模型路由、语义缓存与执行统计。
 * 对外契约（infrastructure LlmResponse / AgentChunk 事件流）保持不变，前端与调用方无感。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentExecutionEngine {

    private final AgentExecutor agentExecutor;
    private final AgentMessageBuilder messageBuilder;
    private final AgentMapper agentMapper;
    private final SessionMapper sessionMapper;

    /**
     * 同步执行：委托 ReactAgentExecutor 全链路（安全/记忆/预算/路由），映射回 legacy 响应结构。
     */
    public LlmResponse executeAgent(AgentExecutionRequest request) {
        log.info("同步对话请求(收敛引擎): agentId={}, model={}, agentMode={}, messageLen={}",
                request.getAgentId(), request.getModel(), request.getAgentMode(),
                request.getMessage() != null ? request.getMessage().length() : 0);

        AgentTask task = buildTask(request);
        com.gewu.agent.engine.llm.model.LlmResponse result = agentExecutor.execute(task);
        return toLegacyResponse(result);
    }

    /**
     * 流式执行：委托 ReactAgentExecutor，AgentEvent 逐项映射为 legacy AgentChunk 事件。
     */
    public Flux<AgentChunk> executeAgentStream(AgentExecutionRequest request) {
        log.info("流式对话请求(收敛引擎): agentId={}, model={}, agentMode={}, messageLen={}",
                request.getAgentId(), request.getModel(), request.getAgentMode(),
                request.getMessage() != null ? request.getMessage().length() : 0);

        AgentTask task = buildTask(request);
        return agentExecutor.executeStream(task)
                .map(this::toChunk);
    }

    // ==================== 适配映射 ====================

    /**
     * 构建引擎任务：会话级 Agent 绑定解析 + provider/model 预解析（引擎要求显式指定）。
     */
    private AgentTask buildTask(AgentExecutionRequest request) {
        String agentId = resolveAgentId(request.getAgentId(), request.getSessionId());
        Agent agent = loadAgent(agentId);
        String[] pm = messageBuilder.resolveProviderAndModel(agent, request.getModel());

        return AgentTask.builder()
                .agentId(agentId)
                .sessionId(request.getSessionId())
                .userId(UserContext.currentUserId())
                .message(request.getMessage())
                .modelProvider(pm[0])
                .modelName(pm[1])
                .agentMode(request.getAgentMode())
                .thinkingStyle(request.getThinkingStyle())
                .build();
    }

    /**
     * 引擎响应 -> legacy 响应（保持 AiChatController 及既有调用方的结构契约）。
     */
    private LlmResponse toLegacyResponse(com.gewu.agent.engine.llm.model.LlmResponse response) {
        java.util.List<ToolCall> toolCalls = null;
        if (response.getToolCalls() != null) {
            toolCalls = response.getToolCalls().stream()
                    .map(tc -> ToolCall.builder()
                            .id(tc.getId())
                            .name(tc.getName())
                            .arguments(tc.getArguments())
                            .build())
                    .toList();
        }
        LlmResponse.Usage usage = null;
        if (response.getUsage() != null) {
            usage = LlmResponse.Usage.builder()
                    .promptTokens(response.getUsage().getPromptTokens())
                    .completionTokens(response.getUsage().getCompletionTokens())
                    .totalTokens(response.getUsage().getTotalTokens())
                    .build();
        }
        return LlmResponse.builder()
                .content(response.getContent())
                .toolCalls(toolCalls)
                .usage(usage)
                .finishReason(response.getFinishReason())
                .build();
    }

    /**
     * 引擎事件 -> legacy 事件分片（字段一一对应，引擎新增事件类型透传给前端）。
     */
    private AgentChunk toChunk(AgentEvent event) {
        AgentChunk.ToolCallInfo toolCallInfo = null;
        if (event.getToolCall() != null) {
            toolCallInfo = AgentChunk.ToolCallInfo.builder()
                    .id(event.getToolCall().getId())
                    .name(event.getToolCall().getName())
                    .arguments(event.getToolCall().getArguments())
                    .build();
        }
        AgentChunk.ToolResultInfo toolResultInfo = null;
        if (event.getToolResult() != null) {
            toolResultInfo = AgentChunk.ToolResultInfo.builder()
                    .toolCallId(event.getToolResult().getToolCallId())
                    .name(event.getToolResult().getName())
                    .result(event.getToolResult().getResult())
                    .build();
        }
        return AgentChunk.builder()
                .type(event.getType())
                .content(event.getContent())
                .reasoning(event.getReasoning())
                .toolCall(toolCallInfo)
                .toolResult(toolResultInfo)
                .errorMessage(event.getErrorMessage())
                .build();
    }

    // ==================== 辅助方法 ====================

    /**
     * 解析 agentId：优先用请求传入的 agentId，为空时回退读会话绑定的 agent.
     * <p>使"会话级绑定"生效--前端建会话时绑定 agent，后续对话即使不传 agentId 也能按该 agent 执行.
     */
    private String resolveAgentId(String agentId, String sessionId) {
        if (agentId != null && !agentId.isBlank()) {
            return agentId;
        }
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        Session session = sessionMapper.selectById(sessionId);
        return session != null ? session.getAgent() : null;
    }

    private Agent loadAgent(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return null;
        }
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw BusinessException.of(ResultCode.AGENT_NOT_FOUND);
        }
        return agent;
    }
}
