package com.gewu.application.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.llm.model.ToolDefinition;
import com.gewu.agent.engine.tool.Tool;
import com.gewu.agent.engine.tool.ToolContext;
import com.gewu.agent.engine.tool.ToolProvider;
import com.gewu.agent.engine.tool.ToolResult;
import com.veloflow.engine.definition.WorkflowInstanceService;
import com.veloflow.engine.definition.dto.WorkflowInstanceDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 启动工作流 Agent 工具（51 号 §开放问题 4 裁定，P4）：与 run_orchestration_graph
 * 对称——Agent 会话中可直接发起 Veloflow 工作流实例并等待终态。
 * <p>安全边界：仅允许已发布流程；单次等待时长帽
 * {@code agent.engine.tool.workflow.timeout-seconds}（默认 300s），
 * 超时实例仍在后台执行并如实告知；触发类型 MANUAL，发起人记当前用户。
 */
@Slf4j
@ToolProvider(category = "WORKFLOW")
public class RunWorkflowTool implements Tool {

    /** 等待轮询专用池（CR-022 模式：有界队列 + CallerRunsPolicy，daemon） */
    private static final ThreadPoolExecutor TOOL_WAIT_EXECUTOR = new ThreadPoolExecutor(
            1, 4, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(32),
            runnable -> {
                Thread thread = new Thread(runnable, "wf-agent-tool");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    private static final String PARAMETERS_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "workflowId": {"type": "string", "description": "要启动的工作流 ID（须为已发布状态）"},
                "input": {"type": "string", "description": "流程变量（JSON 文本，作为触发变量传入）"}
              },
              "required": ["workflowId"]
            }""";

    /** WorkflowInstanceService 延迟解析（同 RunOrchestrationGraphTool 断环先例） */
    private final ObjectProvider<WorkflowInstanceService> instanceServiceProvider;
    private final ObjectMapper objectMapper;

    @Value("${agent.engine.tool.workflow.timeout-seconds:300}")
    private long timeoutSeconds = 300;

    public RunWorkflowTool(ObjectProvider<WorkflowInstanceService> instanceServiceProvider,
                           ObjectMapper objectMapper) {
        this.instanceServiceProvider = instanceServiceProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("run_workflow")
                .description("启动一个已发布的工作流（人工审批/任务流转类流程）并等待其执行完成。"
                        + "适用于用户明确要求执行某个业务流程/审批流程的场景。返回实例状态与最终输出。")
                .parameters(PARAMETERS_SCHEMA)
                .build();
    }

    @Override
    public ToolResult invoke(String arguments, ToolContext context) {
        long start = System.currentTimeMillis();
        String workflowId;
        String input;
        try {
            JsonNode args = objectMapper.readTree(arguments == null ? "{}" : arguments);
            workflowId = args.path("workflowId").asText(null);
            input = args.path("input").asText("");
        } catch (Exception e) {
            return ToolResult.failure("参数解析失败（需要 JSON：workflowId 必填、input 可选）: " + e.getMessage(),
                    System.currentTimeMillis() - start);
        }
        if (workflowId == null || workflowId.isBlank()) {
            return ToolResult.failure("缺少必填参数 workflowId", System.currentTimeMillis() - start);
        }
        WorkflowInstanceService instanceService = instanceServiceProvider.getIfAvailable();
        if (instanceService == null) {
            return ToolResult.failure("工作流服务不可用", System.currentTimeMillis() - start);
        }
        String userId = context != null ? context.getUserId() : null;
        CompletableFuture<WorkflowInstanceDTO> future = CompletableFuture.supplyAsync(() -> {
            WorkflowInstanceDTO instance = instanceService.startByTrigger(workflowId,
                    userId != null ? userId : "agent", "Agent 工具发起", input, "MANUAL");
            return waitForTerminal(instanceService, instance.getInstanceId());
        }, TOOL_WAIT_EXECUTOR);
        try {
            WorkflowInstanceDTO instance = future.get(timeoutSeconds, TimeUnit.SECONDS);
            String summary = "工作流执行完成，状态: " + instance.getStatus()
                    + "；实例ID: " + instance.getInstanceId()
                    + (instance.getFinalOutput() != null && !instance.getFinalOutput().isBlank()
                            ? "\n最终输出:\n" + instance.getFinalOutput()
                            : (instance.getErrorMessage() != null
                                    ? "\n失败原因: " + instance.getErrorMessage()
                                    : "\n（无最终输出）"));
            return ToolResult.success(summary, System.currentTimeMillis() - start);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            log.warn("Agent 工具等待工作流超时: workflowId={}, timeout={}s", workflowId, timeoutSeconds);
            return ToolResult.failure("工作流执行超过等待上限（" + timeoutSeconds
                    + " 秒），仍在后台继续执行，请稍后在工作流实例列表中查看结果",
                    System.currentTimeMillis() - start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("工作流执行被中断", System.currentTimeMillis() - start);
        } catch (Exception e) {
            String message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            return ToolResult.failure("工作流执行失败: " + message, System.currentTimeMillis() - start);
        }
    }

    /** 轮询等待实例终态（completed/failed/terminated），500ms 间隔 */
    private WorkflowInstanceDTO waitForTerminal(WorkflowInstanceService instanceService, String instanceId) {
        while (true) {
            WorkflowInstanceDTO instance = instanceService.getInstance(instanceId);
            String status = instance.getStatus();
            if ("completed".equals(status) || "failed".equals(status) || "terminated".equals(status)) {
                return instance;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return instance;
            }
        }
    }
}
