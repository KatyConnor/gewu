package com.gewu.application.agent.adapter;

import com.gewu.agent.engine.hitl.ApprovalRequest;
import com.gewu.agent.engine.hitl.HitlGateway;
import com.gewu.agent.engine.hitl.HumanDecision;
import com.gewu.application.orchestration.OrchestrationService;
import com.gewu.application.sse.SseEventManager;
import com.gewu.common.ulid.Ulid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * HitlGateway SPI 适配器 - 桥接审批表 + SSE 通知实现真实人工介入流程。
 * <p>请求审批时创建 DB 审批记录并通过 SSE 推送通知；人工决策通过 {@link OrchestrationService}
 * 的 approve/reject 回调注入，恢复阻塞的 Mono。
 * <p>注意：{@link OrchestrationService} 通过 {@link ObjectProvider} 延迟解析，
 * 避免 OrchestrationService -> OrchestrationEngine -> Orchestrator -> HitlGateway -> 本类 -> OrchestrationService 循环依赖。
 * <p>启用条件：{@code agent.engine.adapter.enabled=true}
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "agent.engine.adapter.enabled", havingValue = "true")
public class DbHitlGatewayAdapter implements HitlGateway {

    private final ObjectProvider<OrchestrationService> orchestrationServiceProvider;
    private final SseEventManager sseEventManager;
    /** 分布式决策回传（gewu.sse.distributed=true 时注入；单实例为 null） */
    private final ObjectProvider<com.gewu.application.sse.SseBroadcastService> broadcastProvider;

    public DbHitlGatewayAdapter(ObjectProvider<OrchestrationService> orchestrationServiceProvider,
                                 SseEventManager sseEventManager,
                                 ObjectProvider<com.gewu.application.sse.SseBroadcastService> broadcastProvider) {
        this.orchestrationServiceProvider = orchestrationServiceProvider;
        this.sseEventManager = sseEventManager;
        this.broadcastProvider = broadcastProvider;
    }

    /** 待审批 Mono Sink 注册表（approvalId -> Sink），用于异步恢复阻塞的 Mono */
    private final Map<String, Sinks.One<HumanDecision>> pendingApprovals = new ConcurrentHashMap<>();

    /** 审批 ID 到执行实例 ID 的映射（用于 SSE 通知） */
    private final Map<String, String> approvalToSession = new ConcurrentHashMap<>();

    @Override
    public Mono<HumanDecision> requestApproval(ApprovalRequest request) {
        String approvalId = request.getApprovalId() != null ? request.getApprovalId() : Ulid.next();
        request.setApprovalId(approvalId);

        // 创建 DB 审批记录（延迟解析 OrchestrationService，打破循环依赖）
        int timeoutMinutes = request.getTimeoutSeconds() != null ? request.getTimeoutSeconds() / 60 : 30;
        OrchestrationService orchestrationService = orchestrationServiceProvider.getIfAvailable();
        if (orchestrationService != null) {
            orchestrationService.createApproval(
                    request.getExecutionId(),
                    request.getNodeId(),
                    mapType(request.getType()),
                    request.getSummary(),
                    timeoutMinutes);
        }

        // 建立 Mono Sink 等待人工决策
        Sinks.One<HumanDecision> sink = Sinks.one();
        pendingApprovals.put(approvalId, sink);
        approvalToSession.put(approvalId, request.getExecutionId());

        // SSE 推送审批通知
        sseEventManager.sendEvent(request.getExecutionId(), "approval_required", Map.of(
                "approvalId", approvalId,
                "executionId", request.getExecutionId(),
                "nodeId", request.getNodeId(),
                "type", request.getType() != null ? request.getType() : "APPROVE_REJECT",
                "summary", request.getSummary() != null ? request.getSummary() : ""
        ));

        log.info("HITL 审批请求: approvalId={}, executionId={}, nodeId={}", approvalId, request.getExecutionId(), request.getNodeId());

        // 超时处理：超时后自动拒绝
        return sink.asMono().timeout(
                java.time.Duration.ofSeconds(request.getTimeoutSeconds() != null ? request.getTimeoutSeconds() : 1800),
                Mono.fromSupplier(() -> {
                    log.warn("HITL 审批超时: approvalId={}", approvalId);
                    pendingApprovals.remove(approvalId);
                    approvalToSession.remove(approvalId);
                    return HumanDecision.builder()
                            .decision("REJECTED")
                            .value("审批超时自动拒绝")
                            .operatorId("system")
                            .build();
                }));
    }

    /**
     * 提交人工决策 - 恢复阻塞的 Mono。
     * <p>由 {@link com.gewu.interfaceapi.controller.ApprovalController} 的 approve/reject 调用，
     * 经 {@link OrchestrationService} 转发到此处。
     * <p>多副本：本实例不持有挂起审批时经 Redis 频道回传给持有实例（T5.1）。
     */
    @Override
    public void submitDecision(String approvalId, HumanDecision decision) {
        Sinks.One<HumanDecision> sink = pendingApprovals.remove(approvalId);
        approvalToSession.remove(approvalId);
        if (sink != null) {
            sink.tryEmitValue(decision);
            log.info("HITL 决策提交: approvalId={}, decision={}", approvalId, decision.getDecision());
            return;
        }
        com.gewu.application.sse.SseBroadcastService broadcaster = broadcastProvider.getIfAvailable();
        if (broadcaster != null) {
            // 决策在另一实例提交：经频道回传给持有挂起审批的实例
            broadcaster.relayDecision(approvalId, decision);
            log.info("HITL 决策跨实例回传: approvalId={}, decision={}", approvalId, decision.getDecision());
            return;
        }
        log.warn("HITL 决策提交但无待审批记录: approvalId={}", approvalId);
    }

    /** 本地决策回传（多副本订阅器路由命中时调用），不产生跨实例广播 */
    public void completeLocal(String approvalId, HumanDecision decision) {
        Sinks.One<HumanDecision> sink = pendingApprovals.remove(approvalId);
        approvalToSession.remove(approvalId);
        if (sink != null) {
            sink.tryEmitValue(decision);
            log.info("HITL 决策回传恢复: approvalId={}, decision={}", approvalId, decision.getDecision());
        } else {
            log.warn("HITL 决策回传但本实例无挂起审批: approvalId={}", approvalId);
        }
    }

    private String mapType(String type) {
        if (type == null) return "MANUAL_REVIEW";
        return switch (type) {
            case "APPROVE_REJECT" -> "MANUAL_REVIEW";
            case "INPUT", "SELECT", "EDIT" -> "TAKEOVER";
            default -> "MANUAL_REVIEW";
        };
    }
}