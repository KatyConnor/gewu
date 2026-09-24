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

import java.util.List;

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
    private final AgentExecutionService agentExecutionService;
    private final com.gewu.application.ai.CostAccountingService costAccountingService;
    private final com.gewu.application.quota.UserQuotaService userQuotaService;

    /**
     * 同步执行：委托 ReactAgentExecutor 全链路（安全/记忆/预算/路由），映射回 legacy 响应结构。
     * 执行账本自动落库（T3.4/T4.1）：开始创建 running 记录，完成/失败回写，
     * A/B 实验分组从 Agent modelConfig.experimentGroup 解析写入。
     */
    public LlmResponse executeAgent(AgentExecutionRequest request) {
        log.info("同步对话请求(收敛引擎): agentId={}, model={}, agentMode={}, messageLen={}",
                request.getAgentId(), request.getModel(), request.getAgentMode(),
                request.getMessage() != null ? request.getMessage().length() : 0);

        AgentTask task = buildTask(request);
        String executionId = recordStart(task);
        try {
            com.gewu.agent.engine.llm.model.LlmResponse result = agentExecutor.execute(task);
            recordComplete(executionId, result, task);
            return toLegacyResponse(result);
        } catch (RuntimeException e) {
            recordFail(executionId, e.getMessage());
            throw e;
        }
    }

    /**
     * 流式执行：委托 ReactAgentExecutor，AgentEvent 逐项映射为 legacy AgentChunk 事件。
     * 执行账本：流开始创建 running 记录，完成/错误回调回写（客户端取消不落库，与 SSE 主链路一致）。
     */
    public Flux<AgentChunk> executeAgentStream(AgentExecutionRequest request) {
        log.info("流式对话请求(收敛引擎): agentId={}, model={}, agentMode={}, messageLen={}",
                request.getAgentId(), request.getModel(), request.getAgentMode(),
                request.getMessage() != null ? request.getMessage().length() : 0);

        AgentTask task = buildTask(request);
        String executionId = recordStart(task);
        StringBuilder contentTracker = new StringBuilder();
        return agentExecutor.executeStream(task)
                .doOnNext(event -> {
                    if ("content".equals(event.getType()) && event.getContent() != null) {
                        contentTracker.append(event.getContent());
                    }
                    // 截断重试重新生成：清空账本内容追踪，以最终重试结果为准
                    if (AgentEvent.CONTENT_RESET.equals(event.getType())) {
                        contentTracker.setLength(0);
                    }
                })
                .map(this::toChunk)
                .doOnComplete(() -> recordComplete(executionId,
                        com.gewu.agent.engine.llm.model.LlmResponse.builder()
                                .content(contentTracker.toString()).build(), task))
                .doOnError(e -> recordFail(executionId, e.getMessage()));
    }

    // ==================== 执行账本（T3.4/T4.1） ====================

    /**
     * 执行账本记录入口（Wenshi 路径专用，S7 基准评测补盲区）：
     * 解析会话级 Agent 绑定与实验分组，创建 running 记录；失败返回 null 不阻断。
     */
    public String beginExecutionRecord(AgentExecutionRequest request) {
        String agentId = resolveAgentId(request.getAgentId(), request.getSessionId());
        // 真暂停校验：已暂停的 Agent 拒绝新对话（wenshi 链路入口）。
        // 必须在 try 之外——账本记录本身失败不阻断，但暂停拦截要抛给调用方生效。
        if (agentId != null && !agentId.isBlank()) {
            checkNotPaused(agentMapper.selectById(agentId));
        }
        try {
            if (agentId == null || agentId.isBlank()) {
                return null;
            }
            return agentExecutionService.createExecution(
                    agentId, request.getSessionId(), request.getMessage(),
                    resolveExperimentGroup(agentId)).getExecutionId();
        } catch (Exception e) {
            log.warn("执行记录创建失败（忽略）: cause={}", e.getMessage());
            return null;
        }
    }

    /** 结束执行账本记录：error 为空记完成，否则记失败（失败静默） */
    public void endExecutionRecord(String executionId, String content, Integer tokens, String error) {
        if (executionId == null) return;
        try {
            if (error != null) {
                agentExecutionService.failExecution(executionId, error);
            } else {
                agentExecutionService.completeExecution(executionId, content, tokens);
            }
        } catch (Exception e) {
            log.warn("执行记录结束回写失败（忽略）: executionId={}, cause={}", executionId, e.getMessage());
        }
    }

    /** 创建执行记录并返回 executionId（账本失败不阻断对话主链路） */
    private String recordStart(AgentTask task) {
        if (task.getAgentId() == null || task.getAgentId().isBlank()) {
            return null;
        }
        try {
            return agentExecutionService.createExecution(
                    task.getAgentId(), task.getSessionId(), task.getMessage(),
                    resolveExperimentGroup(task.getAgentId())).getExecutionId();
        } catch (Exception e) {
            log.warn("执行记录创建失败（忽略，不阻断对话）: agentId={}, cause={}",
                    task.getAgentId(), e.getMessage());
            return null;
        }
    }

    private void recordComplete(String executionId, com.gewu.agent.engine.llm.model.LlmResponse response,
                                AgentTask task) {
        if (executionId == null) {
            return;
        }
        try {
            Integer tokens = response != null && response.getUsage() != null
                    ? response.getUsage().getTotalTokens() : null;
            agentExecutionService.completeExecution(executionId,
                    response != null ? response.getContent() : null, tokens);
        } catch (Exception e) {
            log.warn("执行记录完成回写失败（忽略）: executionId={}, cause={}", executionId, e.getMessage());
        }
        // 会话维度成本核算（T4.1）：真实 usage 优先，缺失时按字符估算
        if (task != null && task.getSessionId() != null && response != null) {
            try {
                if (response.getUsage() != null) {
                    costAccountingService.recordUsage(task.getSessionId(), task.getModelName(),
                            response.getUsage().getPromptTokens(),
                            response.getUsage().getCompletionTokens(), 0);
                } else {
                    costAccountingService.recordEstimatedUsage(task.getSessionId(), task.getModelName(),
                            task.getMessage(), response.getContent());
                }
            } catch (Exception e) {
                log.warn("会话成本核算失败（忽略）: sessionId={}, cause={}",
                        task.getSessionId(), e.getMessage());
            }
        }
    }

    private void recordFail(String executionId, String error) {
        if (executionId == null) {
            return;
        }
        try {
            agentExecutionService.failExecution(executionId, error);
        } catch (Exception e) {
            log.warn("执行记录失败回写失败（忽略）: executionId={}, cause={}", executionId, e.getMessage());
        }
    }

    /** 从 Agent modelConfig JSON 解析 A/B 实验分组 */
    private String resolveExperimentGroup(String agentId) {
        try {
            Agent agent = agentMapper.selectById(agentId);
            if (agent == null || agent.getModelConfig() == null || agent.getModelConfig().isBlank()) {
                return null;
            }
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(agent.getModelConfig());
            return node.has("experimentGroup") ? node.path("experimentGroup").asText(null) : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 适配映射 ====================

    /**
     * 构建引擎任务：会话级 Agent 绑定解析 + provider/model 预解析（引擎要求显式指定）。
     */
    private AgentTask buildTask(AgentExecutionRequest request) {
        String agentId = resolveAgentId(request.getAgentId(), request.getSessionId());
        Agent agent = loadAgent(agentId);
        String[] pm = messageBuilder.resolveProviderAndModel(agent, request.getModel());

        AgentTask task = AgentTask.builder()
                .agentId(agentId)
                .sessionId(request.getSessionId())
                .userId(UserContext.currentUserId())
                .message(request.getMessage())
                .modelProvider(pm[0])
                .modelName(pm[1])
                .agentMode(request.getAgentMode())
                .thinkingStyle(request.getThinkingStyle())
                .modelRouteEnabled(request.getModelRouteEnabled())
                .build();
        applyUserQuota(task);
        return task;
    }

    /**
     * 用户套餐配额预检（配额体系）：熔断开关开且任一窗口耗尽 → 拒绝新任务；
     * 否则把剩余配额与熔断开关注入任务，引擎据此控制执行中的告警与收尾。
     * 未绑定套餐/无登录上下文（系统内部调用）不限制。
     */
    private void applyUserQuota(AgentTask task) {
        if (task.getUserId() == null || task.getUserId().isBlank()) {
            return;
        }
        try {
            com.gewu.application.quota.dto.QuotaPreflightResultDTO preflight =
                    userQuotaService.preflight(task.getUserId());
            if (preflight.isShouldBlock()) {
                throw BusinessException.of(ResultCode.FORBIDDEN,
                        "套餐配额已用尽（窗口利用率 "
                                + Math.round(preflight.getMaxUtilization() * 100) + "%），"
                                + "请等待配额恢复、提升套餐或关闭熔断开关后重试");
            }
            // 未绑定套餐：显式下发不限量哨兵（用户实报：null 分支漏了语义，
            // 导致未绑定用户仍被旧默认 82K token 预算中途熔断）
            task.setQuotaTokenBudget(preflight.getRemainingTokens() != null
                    ? preflight.getRemainingTokens() : Long.MAX_VALUE / 2);
            task.setQuotaBlockEnabled(preflight.isBlockEnabled());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // 配额预检故障不阻断任务（降级为无配额限制）
            log.warn("用户配额预检失败（跳过限制）: user={}, cause={}", task.getUserId(), e.getMessage());
        }
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
        List<AgentChunk.PlanStepInfo> plan = null;
        if (event.getPlan() != null) {
            plan = event.getPlan().stream()
                    .map(s -> AgentChunk.PlanStepInfo.builder()
                            .id(s.getId()).text(s.getText()).status(s.getStatus()).build())
                    .toList();
        }
        return AgentChunk.builder()
                .type(event.getType())
                .content(event.getContent())
                .reasoning(event.getReasoning())
                .toolCall(toolCallInfo)
                .toolResult(toolResultInfo)
                .errorMessage(event.getErrorMessage())
                .finishReason(event.getFinishReason())
                .planTitle(event.getPlanTitle())
                .plan(plan)
                .metadata(event.getMetadata())
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
        checkNotPaused(agent);
        return agent;
    }

    /** 真暂停校验：Agent status=0（已暂停）时拒绝新对话（"我的智能体"页暂停按钮的落地语义） */
    private void checkNotPaused(Agent agent) {
        if (agent != null && agent.getStatus() != null && agent.getStatus() == 0) {
            throw BusinessException.of(ResultCode.AGENT_PAUSED);
        }
    }
}
