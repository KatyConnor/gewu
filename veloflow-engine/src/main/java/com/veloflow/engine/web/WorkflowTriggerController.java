package com.veloflow.engine.web;

import com.veloflow.engine.commons.FlowResult;
import com.veloflow.engine.commons.VeloflowErrorCode;
import com.veloflow.engine.definition.WorkflowInstanceService;
import com.veloflow.engine.persistence.model.WorkflowWebhook;
import com.veloflow.engine.trigger.WorkflowEventService;
import com.veloflow.engine.trigger.WorkflowTriggerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Veloflow 触发体系 API（53 号 §3.1）：定时/Webhook 配置管理 + 匿名触发端点。
 * <p>匿名端点 token 即凭证（库内只存 SM3 哈希），未命中/停用/总开关关闭统一 404
 * 不暴露存在性；总开关 veloflow.webhook.enabled 默认关闭。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/workflows")
@RequiredArgsConstructor
@Tag(name = "Veloflow 触发体系")
@ConditionalOnProperty(name = "veloflow.rest.enabled", havingValue = "true")
public class WorkflowTriggerController {

    private final WorkflowTriggerService triggerService;
    private final WorkflowEventService eventService;
    private final WorkflowInstanceService instanceService;

    @Value("${veloflow.webhook.enabled:false}")
    private boolean webhookEnabled;

    // ==================== 定时触发 ====================

    @PutMapping("/{workflowId}/schedule")
    @Operation(summary = "保存定时触发配置")
    public FlowResult<Object> upsertSchedule(@PathVariable String workflowId,
                                             @Valid @RequestBody ScheduleRequest request) {
        return FlowResult.success(triggerService.upsertSchedule(workflowId,
                request.getCronExpr(), request.getTimezone(), request.getInputTemplate(),
                request.getEnabled() == null || request.getEnabled()));
    }

    @GetMapping("/{workflowId}/schedule")
    @Operation(summary = "查询定时触发配置")
    public FlowResult<Object> getSchedule(@PathVariable String workflowId) {
        return FlowResult.success(triggerService.getSchedule(workflowId));
    }

    // ==================== Webhook ====================

    @PutMapping("/{workflowId}/webhook")
    @Operation(summary = "保存 Webhook 配置", description = "token 明文仅本次响应返回一次（库内只存 SM3 哈希）")
    public FlowResult<WorkflowTriggerService.WebhookCredential> upsertWebhook(
            @PathVariable String workflowId, @RequestBody WebhookRequest request) {
        boolean enabled = request.getEnabled() == null || request.getEnabled();
        return FlowResult.success(triggerService.upsertWebhook(workflowId, enabled,
                Boolean.TRUE.equals(request.getRegenerate())));
    }

    @GetMapping("/{workflowId}/webhook")
    @Operation(summary = "查询 Webhook 配置", description = "只含哈希不含明文")
    public FlowResult<WorkflowWebhook> getWebhook(@PathVariable String workflowId) {
        return FlowResult.success(triggerService.getWebhook(workflowId));
    }

    /**
     * Webhook 匿名触发：body 原样作为执行输入；未命中/停用/总开关关闭统一 404。
     * 流程含 respond 节点且在 sync-timeout 内完成 → 200 + respondPayload；否则 202。
     */
    @PostMapping(value = "/webhooks/{token}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Webhook 匿名触发",
            description = "未命中/停用/总开关关闭统一 404；respond 流程同步返回 200，超时回退 202")
    public ResponseEntity<FlowResult<Map<String, String>>> triggerByWebhook(
            @PathVariable String token, @RequestBody(required = false) String body) {
        if (!webhookEnabled) {
            return ResponseEntity.notFound().build();
        }
        if (body != null && body.length() > 64 * 1024) {
            log.warn("Webhook 请求体超限拒绝: tokenPrefix={}, length={}", token, body.length());
            return ResponseEntity.notFound().build();
        }
        WorkflowTriggerService.WebhookTriggerResult result = triggerService.triggerByWebhook(token, body);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instanceId", result.instanceId());
        if (result.responded()) {
            payload.put("status", "responded");
            payload.put("respondPayload", result.respondPayload());
            return ResponseEntity.ok(FlowResult.success(payload));
        }
        payload.put("status", "accepted");
        return ResponseEntity.accepted().body(FlowResult.success(payload));
    }

    // ==================== 消息交付（53 号 §3.3 receive-message） ====================

    /** 按消息键跨实例交付：命中全局最早等待中的 receive-message 节点并推进 */
    @PostMapping("/messages/{messageKey}")
    @Operation(summary = "消息交付（按消息键）", description = "命中等待中的 receive-message 节点并推进")
    public FlowResult<Object> deliverMessageByKey(@PathVariable String messageKey,
                                                  @RequestBody(required = false) MessageRequest request) {
        return FlowResult.success(instanceService.deliverMessage(null, messageKey,
                request != null ? request.getPayload() : null));
    }

    // ==================== 事件交付（53 号 §3.3 event-wait / event-trigger） ====================

    /** 事件注入：唤醒匹配 event-wait 等待行 + 自动发起匹配 event-trigger 已发布流程 */
    @PostMapping("/events/{eventType}")
    @Operation(summary = "事件交付", description = "唤醒 event-wait 订阅并发起 event-trigger 流程")
    public FlowResult<Object> deliverEvent(@PathVariable String eventType,
                                           @RequestBody(required = false) MessageRequest request) {
        return FlowResult.success(eventService.onEvent(eventType,
                request != null ? request.getPayload() : null));
    }

    // ==================== DTO ====================

    @Data
    public static class ScheduleRequest {
        @NotBlank(message = "Cron 表达式不能为空")
        private String cronExpr;
        private String timezone;
        private String inputTemplate;
        private Boolean enabled;
    }

    @Data
    public static class WebhookRequest {
        private Boolean enabled;
        private Boolean regenerate;
    }

    @Data
    public static class MessageRequest {
        /** 消息/事件载荷（JSON 文本，作为节点输出/触发输入原样透传） */
        private String payload;
        private String messageKey;
    }
}
