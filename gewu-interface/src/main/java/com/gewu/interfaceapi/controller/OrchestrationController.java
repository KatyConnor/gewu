package com.gewu.interfaceapi.controller;

import com.gewu.agent.engine.core.event.AgentEvent;
import com.gewu.application.orchestration.OrchestrationCatalogService;
import com.gewu.application.orchestration.OrchestrationService;
import com.gewu.common.context.UserContext;
import com.gewu.common.result.Result;
import com.gewu.domain.orchestration.OrchestrationExecutionEntity;
import com.gewu.domain.orchestration.OrchestrationGraphEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
    private final OrchestrationCatalogService orchestrationCatalogService;

    /** Webhook 总开关（WFC-03）：默认关闭，开启前须完成安全评审（48 号 R5） */
    @org.springframework.beans.factory.annotation.Value("${agent.engine.webhook.enabled:false}")
    private boolean webhookEnabled;

    // ==================== 编排图 CRUD ====================

    @PostMapping("/graphs")
    @Operation(summary = "创建编排图", description = "创建草稿状态的编排图定义")
    public Result<OrchestrationGraphEntity> createGraph(@Valid @RequestBody CreateGraphRequest request) {
        String userId = UserContext.currentUserId();
        OrchestrationGraphEntity graph = orchestrationService.createGraph(
                request.getName(), request.getGraphDefinition(),
                request.getGraphType(), request.getMode(), userId);
        return Result.success(graph);
    }

    @PutMapping("/graphs/{graphId}")
    @Operation(summary = "更新编排图定义", description = "仅草稿状态可编辑；保存前执行图结构校验（ERROR 级问题阻断保存）")
    public Result<OrchestrationGraphEntity> updateGraph(
            @PathVariable String graphId,
            @Valid @RequestBody UpdateGraphRequest request) {
        OrchestrationGraphEntity graph = orchestrationService.updateGraph(
                graphId, request.getName(), request.getGraphDefinition(),
                request.getGraphType(), request.getMode(), UserContext.currentUserId());
        return Result.success(graph);
    }

    @GetMapping("/catalog/roles")
    @Operation(summary = "查询角色目录", description = "设计器 AGENT 节点 roleCode 下拉数据源")
    public Result<List<OrchestrationCatalogService.RoleOption>> listRoleCatalog() {
        return Result.success(orchestrationCatalogService.listRoles());
    }

    @GetMapping("/catalog/tools")
    @Operation(summary = "查询工具目录", description = "代码工具与配置工具合并目录，设计器 TOOL 节点 toolName 下拉数据源")
    public Result<List<OrchestrationCatalogService.ToolOption>> listToolCatalog() {
        return Result.success(orchestrationCatalogService.listTools());
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
    @Operation(summary = "激活编排图", description = "draft -> active，同时将当前定义发布为不可变版本快照（WFO-01）")
    public Result<Void> activateGraph(@PathVariable String graphId) {
        orchestrationService.activateGraph(graphId, UserContext.currentUserId());
        return Result.success();
    }

    @PutMapping("/graphs/{graphId}/deactivate")
    @Operation(summary = "下架编排图", description = "active -> draft，进入可编辑状态；执行与审批数据保留（WFO-02）")
    public Result<Void> deactivateGraph(@PathVariable String graphId) {
        orchestrationService.deactivateGraph(graphId, UserContext.currentUserId());
        return Result.success();
    }

    @GetMapping("/graphs/{graphId}/versions")
    @Operation(summary = "查询编排图版本列表", description = "按版本号倒序返回不可变版本快照（WFO-02）")
    public Result<List<com.gewu.domain.orchestration.OrchestrationGraphVersionEntity>> listGraphVersions(
            @PathVariable String graphId) {
        return Result.success(orchestrationService.listGraphVersions(graphId));
    }

    @PostMapping("/graphs/{graphId}/versions/{versionId}/rollback")
    @Operation(summary = "回滚到历史版本", description = "将指定版本快照写回草稿定义（仅 draft 可回滚，需重新激活才可执行）")
    public Result<OrchestrationGraphEntity> rollbackGraphVersion(
            @PathVariable String graphId, @PathVariable String versionId) {
        return Result.success(orchestrationService.rollbackGraphVersion(
                graphId, versionId, UserContext.currentUserId()));
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

    @GetMapping("/executions/{executionId}/nodes")
    @Operation(summary = "查询执行的节点级记录", description = "按时间升序返回各节点的执行状态，供设计器回放着色")
    public Result<List<com.gewu.domain.orchestration.OrchestrationNodeExecutionEntity>> listExecutionNodes(
            @PathVariable String executionId) {
        return Result.success(orchestrationService.listNodeExecutions(executionId));
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

    // ==================== 定时触发（WFC-02） ====================

    @PutMapping("/graphs/{graphId}/schedule")
    @Operation(summary = "保存编排图定时触发配置", description = "Cron 为 Spring CronExpression 6 位语法；保存即校验并预计算下次触发时间")
    public Result<com.gewu.domain.orchestration.OrchestrationScheduleEntity> upsertSchedule(
            @PathVariable String graphId, @Valid @RequestBody ScheduleRequest request) {
        return Result.success(orchestrationService.upsertSchedule(graphId, request.getCronExpr(),
                request.getTimezone(), request.getInputTemplate(),
                request.getEnabled() == null || request.getEnabled(), UserContext.currentUserId()));
    }

    @GetMapping("/graphs/{graphId}/schedule")
    @Operation(summary = "查询编排图定时触发配置", description = "未配置返回 null")
    public Result<com.gewu.domain.orchestration.OrchestrationScheduleEntity> getSchedule(
            @PathVariable String graphId) {
        return Result.success(orchestrationService.getSchedule(graphId));
    }

    // ==================== Webhook 触发（WFC-03） ====================

    @PutMapping("/graphs/{graphId}/webhook")
    @Operation(summary = "保存编排图 Webhook 配置", description = "首次创建或 regenerate=true 时生成新 token，明文仅本次响应返回一次（库内只存 SM3 哈希）")
    public Result<OrchestrationService.WebhookCredential> upsertWebhook(
            @PathVariable String graphId, @RequestBody WebhookRequest request) {
        boolean enabled = request.getEnabled() == null || request.getEnabled();
        boolean regenerate = Boolean.TRUE.equals(request.getRegenerate());
        return Result.success(orchestrationService.upsertWebhook(
                graphId, enabled, regenerate, UserContext.currentUserId()));
    }

    @GetMapping("/graphs/{graphId}/webhook")
    @Operation(summary = "查询编排图 Webhook 配置", description = "未配置返回 null；只含哈希不含明文")
    public Result<com.gewu.domain.orchestration.OrchestrationWebhookEntity> getWebhook(
            @PathVariable String graphId) {
        return Result.success(orchestrationService.getWebhook(graphId));
    }

    /**
     * Webhook 匿名触发端点（WFC-03）：token 即凭证。
     * <p>总开关 agent.engine.webhook.enabled（默认关）；token 未命中/已停用/图不可执行
     * 统一 404（不暴露存在性）；命中同步执行（triggerType=WEBHOOK）。
     */
    @PostMapping(value = "/webhooks/{token}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Webhook 触发编排图（匿名）", description = "body 原样作为执行输入；错误 token 返回 404")
    public org.springframework.http.ResponseEntity<Result<OrchestrationExecutionEntity>> triggerByWebhook(
            @PathVariable String token,
            @RequestBody(required = false) String body) {
        if (!webhookEnabled) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }
        OrchestrationExecutionEntity execution = orchestrationService.triggerByWebhook(token, body);
        if (execution == null) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }
        return org.springframework.http.ResponseEntity.accepted().body(Result.success(execution));
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
        @NotBlank(message = "编排图名称不能为空")
        private String name;
        @NotBlank(message = "编排图定义不能为空")
        private String graphDefinition;
        private String graphType;
        private String mode;
    }

    @Data
    public static class UpdateGraphRequest {
        private String name;
        @NotBlank(message = "编排图定义不能为空")
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

    @Data
    public static class ScheduleRequest {
        @NotBlank(message = "Cron 表达式不能为空")
        private String cronExpr;
        private String timezone;
        private String inputTemplate;
        /** 启停开关，缺省启用 */
        private Boolean enabled;
    }

    @Data
    public static class WebhookRequest {
        /** 启停开关，缺省启用 */
        private Boolean enabled;
        /** 重置 token（旧 token 立即失效） */
        private Boolean regenerate;
    }
}
