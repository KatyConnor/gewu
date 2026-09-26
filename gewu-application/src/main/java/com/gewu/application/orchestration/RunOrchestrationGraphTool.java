package com.gewu.application.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.llm.model.ToolDefinition;
import com.gewu.agent.engine.tool.Tool;
import com.gewu.agent.engine.tool.ToolContext;
import com.gewu.agent.engine.tool.ToolProvider;
import com.gewu.agent.engine.tool.ToolResult;
import com.gewu.domain.orchestration.OrchestrationExecutionEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Agent 工具化调用编排图（WFC-01，EXEPLAN-ORCH-2026-09）。
 * <p>把「运行一张已激活编排图」暴露为 ReAct 工具 {@code run_orchestration_graph}：
 * 会话中 Agent 依据用户指令自主传 graphId/input 触发图执行，同步等待完成后
 * 把执行状态与最终输出回传给 Agent 汇报。执行记录 triggerType=AGENT_TOOL，
 * sessionId 贯通会话可追溯。
 * <p>安全边界：仅允许 active 图（拒绝草稿）；单次执行时长帽
 * {@code agent.engine.tool.orchestration.timeout-seconds}（默认 300s）防长图阻塞会话；
 * Token 预算由引擎预算体系兜底。
 */
@Slf4j
@ToolProvider(category = "ORCHESTRATION")
public class RunOrchestrationGraphTool implements Tool {

    /** 执行等待专用池（CR-022 模式：有界队列 + CallerRunsPolicy，daemon） */
    private static final ThreadPoolExecutor TOOL_WAIT_EXECUTOR = new ThreadPoolExecutor(
            1, 4, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(32),
            runnable -> {
                Thread thread = new Thread(runnable, "orch-agent-tool");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    private static final String PARAMETERS_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "graphId": {"type": "string", "description": "要运行的编排图 ID（须为已激活状态）"},
                "input": {"type": "string", "description": "本次执行的输入内容（作为图上下文 input 变量）"}
              },
              "required": ["graphId"]
            }""";

    /**
     * OrchestrationService 延迟解析（打破循环依赖，参照 DbHitlGatewayAdapter 先例）：
     * agentExecutor -> toolExecutor -> toolRegistry -> [Tool] -> 本工具 -> OrchestrationService
     * -> orchestrationEngine -> orchestrator -> agentExecutor，直接注入构造期成环。
     */
    private final ObjectProvider<OrchestrationService> orchestrationServiceProvider;
    private final ObjectMapper objectMapper;

    @Value("${agent.engine.tool.orchestration.timeout-seconds:300}")
    private long timeoutSeconds = 300;

    public RunOrchestrationGraphTool(ObjectProvider<OrchestrationService> orchestrationServiceProvider,
                                     ObjectMapper objectMapper) {
        this.orchestrationServiceProvider = orchestrationServiceProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("run_orchestration_graph")
                .description("运行一张已激活的编排图（多智能体协作流程）并等待执行完成。"
                        + "适用于用户明确要求执行某个编排流程/流水线的场景。返回执行状态与最终输出。")
                .parameters(PARAMETERS_SCHEMA)
                .build();
    }

    @Override
    public ToolResult invoke(String arguments, ToolContext context) {
        long start = System.currentTimeMillis();
        String graphId;
        String input;
        try {
            JsonNode args = objectMapper.readTree(arguments == null ? "{}" : arguments);
            graphId = args.path("graphId").asText(null);
            input = args.path("input").asText("");
        } catch (Exception e) {
            return ToolResult.failure("参数解析失败（需要 JSON：graphId 必填、input 可选）: " + e.getMessage(),
                    System.currentTimeMillis() - start);
        }
        if (graphId == null || graphId.isBlank()) {
            return ToolResult.failure("缺少必填参数 graphId", System.currentTimeMillis() - start);
        }
        String userId = context != null ? context.getUserId() : null;
        String sessionId = context != null ? context.getSessionId() : null;
        OrchestrationService orchestrationService = orchestrationServiceProvider.getIfAvailable();
        if (orchestrationService == null) {
            return ToolResult.failure("编排服务不可用", System.currentTimeMillis() - start);
        }
        try {
            CompletableFuture<OrchestrationExecutionEntity> future = CompletableFuture.supplyAsync(
                    () -> orchestrationService.executeGraphForAgentTool(graphId, userId, sessionId, input),
                    TOOL_WAIT_EXECUTOR);
            OrchestrationExecutionEntity execution = future.get(timeoutSeconds, TimeUnit.SECONDS);
            String summary = "编排执行完成，状态: " + execution.getStatus()
                    + "；执行ID: " + execution.getId()
                    + (execution.getFinalOutput() != null && !execution.getFinalOutput().isBlank()
                            ? "\n最终输出:\n" + execution.getFinalOutput()
                            : (execution.getErrorMessage() != null
                                    ? "\n失败原因: " + execution.getErrorMessage()
                                    : "\n（无最终输出）"));
            return ToolResult.success(summary, System.currentTimeMillis() - start);
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("Agent 工具调用编排图超时: graphId={}, timeout={}s", graphId, timeoutSeconds);
            return ToolResult.failure("编排执行超时（上限 " + timeoutSeconds + " 秒），执行已在后台终止等待，请稍后在编排执行历史中查看结果",
                    System.currentTimeMillis() - start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("编排执行被中断", System.currentTimeMillis() - start);
        } catch (Exception e) {
            String message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            return ToolResult.failure("编排执行失败: " + message, System.currentTimeMillis() - start);
        }
    }
}
