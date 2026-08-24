package com.gewu.interfaceapi.controller;

import com.gewu.agent.engine.core.event.AgentEvent;
import com.gewu.application.orchestration.OrchestrationService;
import com.gewu.common.context.UserContext;
import com.gewu.common.result.Result;
import com.gewu.domain.orchestration.OrchestrationExecutionEntity;
import com.gewu.domain.orchestration.OrchestrationGraphEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 编排引擎 API - 管理编排图定义、执行实例、自主目标。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/orchestration")
@RequiredArgsConstructor
@Tag(name = "编排引擎", description = "编排图管理、执行控制、自主目标")
public class OrchestrationController {

    private final OrchestrationService orchestrationService;

    // ==================== 编排图 CRUD ====================

    @PostMapping("/graphs")
    @Operation(summary = "创建编排图", description = "创建草稿状态的编排图定义")
    public Result<OrchestrationGraphEntity> createGraph(@RequestBody CreateGraphRequest request) {
        String userId = UserContext.currentUserId();
        OrchestrationGraphEntity graph = orchestrationService.createGraph(
                request.getName(), request.getGraphDefinition(),
                request.getGraphType(), request.getMode(), userId);
        return Result.success(graph);
    }

    @GetMapping("/graphs")
    @Operation(summary = "查询编排图列表")
    public Result<List<OrchestrationGraphEntity>> listGraphs(
            @RequestParam(required = false) String status) {
        return Result.success(orchestrationService.listGraphs(status));
    }

    @GetMapping("/graphs/{graphId}")
    @Operation(summary = "查询编排图详情")
    public Result<OrchestrationGraphEntity> getGraph(@PathVariable String graphId) {
        OrchestrationGraphEntity graph = orchestrationService.getGraph(graphId);
        if (graph == null) {
            return Result.fail(18001, "编排图不存在");
        }
        return Result.success(graph);
    }

    @PutMapping("/graphs/{graphId}/activate")
    @Operation(summary = "激活编排图", description = "将编排图从 draft 状态切换为 active")
    public Result<Void> activateGraph(@PathVariable String graphId) {
        orchestrationService.activateGraph(graphId, UserContext.currentUserId());
        return Result.success();
    }

    @DeleteMapping("/graphs/{graphId}")
    @Operation(summary = "删除编排图")
    public Result<Void> deleteGraph(@PathVariable String graphId) {
        orchestrationService.deleteGraph(graphId);
        return Result.success();
    }

    // ==================== 执行管理 ====================

    @PostMapping("/graphs/{graphId}/execute")
    @Operation(summary = "同步执行编排图", description = "阻塞执行编排图并返回最终结果")
    public Result<OrchestrationExecutionEntity> executeGraph(
            @PathVariable String graphId,
            @RequestBody ExecuteRequest request) {
        String userId = UserContext.currentUserId();
        OrchestrationExecutionEntity result = orchestrationService.executeGraph(
                graphId, userId, request.getSessionId(), request.getInput());
        return Result.success(result);
    }

    @PostMapping(value = "/graphs/{graphId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式执行编排图", description = "SSE 流式推送执行进度事件")
    public Flux<AgentEvent> executeGraphStream(
            @PathVariable String graphId,
            @RequestBody ExecuteRequest request) {
        String userId = UserContext.currentUserId();
        return orchestrationService.executeGraphStream(
                graphId, userId, request.getSessionId(), request.getInput());
    }

    @GetMapping("/executions")
    @Operation(summary = "查询执行实例列表")
    public Result<List<OrchestrationExecutionEntity>> listExecutions(
            @RequestParam(required = false) String graphId,
            @RequestParam(required = false) String status) {
        return Result.success(orchestrationService.listExecutions(graphId, status));
    }

    @GetMapping("/executions/{executionId}")
    @Operation(summary = "查询执行实例详情")
    public Result<OrchestrationExecutionEntity> getExecution(@PathVariable String executionId) {
        OrchestrationExecutionEntity entity = orchestrationService.getExecution(executionId);
        if (entity == null) {
            return Result.fail(18002, "执行实例不存在");
        }
        return Result.success(entity);
    }

    @PostMapping("/executions/{executionId}/pause")
    @Operation(summary = "暂停执行", description = "协作式暂停：当前节点执行完毕后生效，流以 graph_complete(PAUSED) 结束")
    public Result<Void> pauseExecution(@PathVariable String executionId) {
        orchestrationService.pauseExecution(executionId);
        return Result.success();
    }

    @PostMapping("/executions/{executionId}/resume")
    @Operation(summary = "恢复执行", description = "恢复 DB 状态；返回 resumable 表示引擎存在断点检查点，可调用 resume/stream 续跑")
    public Result<Boolean> resumeExecution(@PathVariable String executionId) {
        boolean resumable = orchestrationService.resumeExecution(executionId);
        return Result.success(resumable);
    }

    @PostMapping(value = "/executions/{executionId}/resume/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "断点续跑事件流", description = "从引擎暂停检查点恢复执行（SSE），跳过已完成节点")
    public Flux<AgentEvent> resumeExecutionStream(@PathVariable String executionId) {
        return orchestrationService.resumeExecutionStream(executionId);
    }

    @PostMapping("/executions/{executionId}/cancel")
    @Operation(summary = "取消执行", description = "运行中发协作信号优雅结束；已暂停的执行丢弃检查点")
    public Result<Void> cancelExecution(@PathVariable String executionId) {
        orchestrationService.cancelExecution(executionId);
        return Result.success();
    }

    // ==================== 自主目标 ====================

    @PostMapping(value = "/goals", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "提交自主目标", description = "SSE 流式推送目标分解与执行进度")
    public Flux<AgentEvent> executeGoal(@RequestBody GoalRequest request) {
        return orchestrationService.executeGoal(
                request.getDescription(), UserContext.currentUserId(),
                request.getType(), request.getMaxIterations());
    }

    // ==================== 请求 DTO ====================

    @Data
    public static class CreateGraphRequest {
        private String name;
        private String graphDefinition;
        private String graphType;
        private String mode;
    }

    @Data
    public static class ExecuteRequest {
        private String sessionId;
        private String input;
    }

    @Data
    public static class GoalRequest {
        private String description;
        private String type;
        private Integer maxIterations;
    }
}
