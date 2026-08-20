package com.gewu.application.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.core.event.AgentEvent;
import com.gewu.agent.engine.hitl.HitlGateway;
import com.gewu.agent.engine.hitl.HumanDecision;
import com.gewu.agent.engine.orchestration.OrchestrationEngine;
import com.gewu.agent.engine.orchestration.model.AutonomousGoal;
import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import com.gewu.agent.engine.orchestration.model.OrchestrationGraph;
import com.gewu.agent.engine.orchestration.model.OrchestrationResult;
import com.gewu.common.ulid.Ulid;
import com.gewu.domain.orchestration.ApprovalRequestEntity;
import com.gewu.domain.orchestration.OrchestrationExecutionEntity;
import com.gewu.domain.orchestration.OrchestrationGraphEntity;
import com.gewu.infrastructure.mapper.ApprovalRequestMapper;
import com.gewu.infrastructure.mapper.OrchestrationExecutionMapper;
import com.gewu.infrastructure.mapper.OrchestrationGraphMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排应用服务 - 管理编排图定义、执行实例、审批请求。
 * <p>桥接 {@link OrchestrationEngine}（内存执行）与数据库持久化，
 * 提供编排图的 CRUD、同步/流式执行、暂停/恢复/取消、审批管理能力。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrchestrationService {

    private final OrchestrationEngine orchestrationEngine;
    private final OrchestrationGraphMapper graphMapper;
    private final OrchestrationExecutionMapper executionMapper;
    private final ApprovalRequestMapper approvalMapper;
    private final ObjectMapper objectMapper;
    private final com.gewu.application.governance.FourPhasePipeline fourPhasePipeline;
    private final com.gewu.infrastructure.trace.OrchestrationTracer orchestrationTracer;

    /** HITL 网关（延迟解析，避免与 DbHitlGatewayAdapter 循环依赖） */
    @Autowired(required = false)
    private ObjectProvider<HitlGateway> hitlGatewayProvider;

    /** 在途执行上下文注册表：取消时提取最终状态快照（版本化变量 + 当前节点） */
    private final Map<String, OrchestrationContext> liveContexts = new java.util.concurrent.ConcurrentHashMap<>();

    // ==================== 编排图 CRUD ====================

    /**
     * 创建编排图定义。
     */
    public OrchestrationGraphEntity createGraph(String name, String graphDefinitionJson,
                                                 String graphType, String mode, String userId) {
        OrchestrationGraphEntity entity = new OrchestrationGraphEntity();
        entity.setId(Ulid.next());
        entity.setGraphName(name);
        entity.setGraphDefinition(graphDefinitionJson);
        entity.setGraphType(graphType != null ? graphType : "AD_HOC");
        entity.setOrchestrationMode(mode != null ? mode : "PIPELINE");
        entity.setVersion("1");
        entity.setStatus("draft");
        entity.setCreatedBy(userId);
        entity.setUpdatedBy(userId);
        graphMapper.insert(entity);
        log.info("创建编排图: id={}, name={}, mode={}", entity.getId(), name, mode);
        return entity;
    }

    /**
     * 查询编排图详情。
     */
    public OrchestrationGraphEntity getGraph(String graphId) {
        return graphMapper.selectById(graphId);
    }

    /**
     * 查询编排图列表。
     */
    public List<OrchestrationGraphEntity> listGraphs(String status) {
        LambdaQueryWrapper<OrchestrationGraphEntity> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            wrapper.eq(OrchestrationGraphEntity::getStatus, status);
        }
        wrapper.orderByDesc(OrchestrationGraphEntity::getCreatedAt);
        return graphMapper.selectList(wrapper);
    }

    /**
     * 激活编排图（draft -> active）。
     */
    public void activateGraph(String graphId, String userId) {
        OrchestrationGraphEntity entity = graphMapper.selectById(graphId);
        if (entity == null) {
            throw new IllegalArgumentException("编排图不存在: " + graphId);
        }
        entity.setStatus("active");
        entity.setUpdatedBy(userId);
        graphMapper.updateById(entity);
        log.info("激活编排图: id={}", graphId);
    }

    /**
     * 删除编排图（逻辑删除）。
     */
    public void deleteGraph(String graphId) {
        graphMapper.deleteById(graphId);
        log.info("删除编排图: id={}", graphId);
    }

    // ==================== 执行管理 ====================

    /**
     * 同步执行编排图。
     */
    public OrchestrationExecutionEntity executeGraph(String graphId, String userId,
                                                      String sessionId, String input) {
        OrchestrationGraphEntity graphEntity = graphMapper.selectById(graphId);
        if (graphEntity == null) {
            throw new IllegalArgumentException("编排图不存在: " + graphId);
        }

        OrchestrationGraph graph = deserializeGraph(graphEntity);
        String executionId = Ulid.next();
        OrchestrationContext ctx = OrchestrationContext.builder()
                .executionId(executionId)
                .userId(userId)
                .sessionId(sessionId)
                .variables(new HashMap<>(Map.of("input", input != null ? input : "")))
                .build();

        // 创建执行记录
        OrchestrationExecutionEntity execEntity = createExecutionEntity(executionId, graphId, userId, sessionId, graphEntity);
        execEntity.setStatus("RUNNING");
        execEntity.setStartedAt(Instant.now().toEpochMilli());
        executionMapper.updateById(execEntity);
        liveContexts.put(executionId, ctx);

        try {
            // OTel 追踪：编排执行全生命周期根 Span（其下挂四环/LLM/工具子 Span）
            io.micrometer.tracing.Span rootSpan = orchestrationTracer.startSpan(executionId, graphId, "orchestration_execute");
            OrchestrationResult result;
            try {
                // 四环协同：运算环同步执行，评估/治理/审计环由管线异步触发
                result = fourPhasePipeline.executeGraph(graph, ctx);
                orchestrationTracer.endSpan(rootSpan);
            } catch (RuntimeException e) {
                orchestrationTracer.endSpanWithError(rootSpan, e);
                throw e;
            }
            updateExecutionResult(execEntity, result);
            executionMapper.updateById(execEntity);
            log.info("编排执行完成: executionId={}, status={}", executionId, result.getStatus());
        } catch (Exception e) {
            execEntity.setStatus("FAILED");
            execEntity.setErrorMessage(e.getMessage());
            execEntity.setCompletedAt(Instant.now().toEpochMilli());
            executionMapper.updateById(execEntity);
            log.error("编排执行失败: executionId={}", executionId, e);
            throw e;
        } finally {
            liveContexts.remove(executionId);
        }

        return execEntity;
    }

    /**
     * 流式执行编排图（返回 SSE 事件流）。
     */
    public Flux<AgentEvent> executeGraphStream(String graphId, String userId, String sessionId, String input) {
        OrchestrationGraphEntity graphEntity = graphMapper.selectById(graphId);
        if (graphEntity == null) {
            return Flux.error(new IllegalArgumentException("编排图不存在: " + graphId));
        }

        OrchestrationGraph graph = deserializeGraph(graphEntity);
        String executionId = Ulid.next();
        OrchestrationContext ctx = OrchestrationContext.builder()
                .executionId(executionId)
                .userId(userId)
                .sessionId(sessionId)
                .variables(new HashMap<>(Map.of("input", input != null ? input : "")))
                .build();

        // 创建执行记录
        OrchestrationExecutionEntity execEntity = createExecutionEntity(executionId, graphId, userId, sessionId, graphEntity);
        execEntity.setStatus("RUNNING");
        execEntity.setStartedAt(Instant.now().toEpochMilli());
        executionMapper.updateById(execEntity);
        liveContexts.put(executionId, ctx);

        return orchestrationEngine.executeStream(graph, ctx)
                .doOnNext(event -> {
                    // 更新当前节点
                    if (event.getNodeId() != null) {
                        execEntity.setCurrentNodeId(event.getNodeId());
                    }
                })
                .doOnComplete(() -> {
                    execEntity.setStatus("SUCCEEDED");
                    execEntity.setCompletedAt(Instant.now().toEpochMilli());
                    executionMapper.updateById(execEntity);
                    liveContexts.remove(executionId);
                    // 四环协同：流式成功完成后触发评估/治理/审计环
                    fourPhasePipeline.postProcess(
                            OrchestrationResult.success(executionId,
                                    execEntity.getFinalOutput() != null ? execEntity.getFinalOutput() : ""),
                            userId, sessionId, execEntity.getStartedAt() != null ? execEntity.getStartedAt() : 0L);
                    log.info("编排流式执行完成: executionId={}", executionId);
                })
                .doOnError(e -> {
                    execEntity.setStatus("FAILED");
                    execEntity.setErrorMessage(e.getMessage());
                    execEntity.setCompletedAt(Instant.now().toEpochMilli());
                    executionMapper.updateById(execEntity);
                    liveContexts.remove(executionId);
                    // 四环协同：流式失败后触发评估/治理/审计环（治理环记录违规轨迹）
                    fourPhasePipeline.postProcess(
                            OrchestrationResult.failure(executionId, e.getMessage()),
                            userId, sessionId, execEntity.getStartedAt() != null ? execEntity.getStartedAt() : 0L);
                    log.error("编排流式执行失败: executionId={}", executionId, e);
                })
                .doOnCancel(() -> {
                    execEntity.setVariables(buildCancelSnapshot(ctx, execEntity));
                    execEntity.setStatus("CANCELLED");
                    execEntity.setCompletedAt(Instant.now().toEpochMilli());
                    executionMapper.updateById(execEntity);
                    liveContexts.remove(executionId);
                    log.info("编排流式执行被取消: executionId={}", executionId);
                });
    }

    /**
     * 执行自主目标。
     */
    public Flux<AgentEvent> executeGoal(String description, String userId, String type,
                                        Integer maxIterations) {
        AutonomousGoal goal = AutonomousGoal.builder()
                .goalId(Ulid.next())
                .description(description)
                .type(type != null ? type : "FEATURE")
                .maxIterations(maxIterations != null ? maxIterations : 5)
                .build();

        log.info("提交自主目标: goalId={}, type={}, description={}", goal.getGoalId(), goal.getType(), description);
        return orchestrationEngine.executeGoal(goal);
    }

    /**
     * 查询执行实例。
     */
    public OrchestrationExecutionEntity getExecution(String executionId) {
        return executionMapper.selectById(executionId);
    }

    /**
     * 查询执行实例列表。
     */
    public List<OrchestrationExecutionEntity> listExecutions(String graphId, String status) {
        LambdaQueryWrapper<OrchestrationExecutionEntity> wrapper = new LambdaQueryWrapper<>();
        if (graphId != null && !graphId.isBlank()) {
            wrapper.eq(OrchestrationExecutionEntity::getGraphId, graphId);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(OrchestrationExecutionEntity::getStatus, status);
        }
        wrapper.orderByDesc(OrchestrationExecutionEntity::getCreatedAt);
        return executionMapper.selectList(wrapper);
    }

    // ==================== 暂停/恢复/取消 ====================

    /**
     * 暂停执行（标记状态为 PAUSED）。
     */
    public void pauseExecution(String executionId) {
        OrchestrationExecutionEntity entity = executionMapper.selectById(executionId);
        if (entity == null) {
            throw new IllegalArgumentException("执行实例不存在: " + executionId);
        }
        if (!"RUNNING".equals(entity.getStatus())) {
            throw new IllegalStateException("仅 RUNNING 状态可暂停，当前状态: " + entity.getStatus());
        }
        entity.setStatus("PAUSED");
        executionMapper.updateById(entity);
        log.info("暂停编排执行: executionId={}", executionId);
    }

    /**
     * 恢复执行（标记状态为 RUNNING）。
     */
    public void resumeExecution(String executionId) {
        OrchestrationExecutionEntity entity = executionMapper.selectById(executionId);
        if (entity == null) {
            throw new IllegalArgumentException("执行实例不存在: " + executionId);
        }
        if (!"PAUSED".equals(entity.getStatus())) {
            throw new IllegalStateException("仅 PAUSED 状态可恢复，当前状态: " + entity.getStatus());
        }
        entity.setStatus("RUNNING");
        executionMapper.updateById(entity);
        log.info("恢复编排执行: executionId={}", executionId);
    }

    /**
     * 取消执行：置 CANCELLED 前从在途上下文提取最终状态快照
     * （VersionedContext 当前版本变量 + 当前节点），写入执行记录供事后审计与恢复分析。
     */
    public void cancelExecution(String executionId) {
        OrchestrationExecutionEntity entity = executionMapper.selectById(executionId);
        if (entity != null && ("RUNNING".equals(entity.getStatus()) || "PAUSED".equals(entity.getStatus()))) {
            OrchestrationContext ctx = liveContexts.get(executionId);
            if (ctx != null) {
                entity.setVariables(buildCancelSnapshot(ctx, entity));
            }
            entity.setStatus("CANCELLED");
            entity.setCompletedAt(Instant.now().toEpochMilli());
            executionMapper.updateById(entity);
            liveContexts.remove(executionId);
            log.info("取消编排执行: executionId={}", executionId);
        }
    }

    /**
     * 构建取消时刻的上下文快照 JSON（版本号 + 变量终态 + 当前节点）。
     */
    private String buildCancelSnapshot(OrchestrationContext ctx, OrchestrationExecutionEntity entity) {
        try {
            Map<String, Object> snapshot = new HashMap<>();
            snapshot.put("stateVersion", ctx.getStateVersion());
            snapshot.put("variables", ctx.snapshotVariables());
            if (entity.getCurrentNodeId() != null) {
                snapshot.put("currentNodeId", entity.getCurrentNodeId());
            }
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            log.warn("取消快照序列化失败: executionId={}", entity.getId(), e);
            return entity.getVariables();
        }
    }

    // ==================== 审批管理 ====================

    /**
     * 查询待审批列表。
     */
    public List<ApprovalRequestEntity> listPendingApprovals() {
        return approvalMapper.selectList(
                new LambdaQueryWrapper<ApprovalRequestEntity>()
                        .eq(ApprovalRequestEntity::getStatus, "pending")
                        .orderByAsc(ApprovalRequestEntity::getTimeoutAt));
    }

    /**
     * 批准审批。
     */
    public void approve(String requestId, String approver, String comment) {
        ApprovalRequestEntity entity = approvalMapper.selectById(requestId);
        if (entity == null) {
            throw new IllegalArgumentException("审批请求不存在: " + requestId);
        }
        if (!"pending".equals(entity.getStatus())) {
            throw new IllegalStateException("审批请求已处理: " + entity.getStatus());
        }
        entity.setStatus("approved");
        entity.setApprover(approver);
        entity.setApprovalComment(comment);
        entity.setApprovedAt(Instant.now().toEpochMilli());
        approvalMapper.updateById(entity);
        log.info("审批通过: requestId={}, approver={}", requestId, approver);
        notifyHitlGateway(requestId, "APPROVED", approver, comment);
    }

    /**
     * 驳回审批。
     */
    public void reject(String requestId, String approver, String comment) {
        ApprovalRequestEntity entity = approvalMapper.selectById(requestId);
        if (entity == null) {
            throw new IllegalArgumentException("审批请求不存在: " + requestId);
        }
        if (!"pending".equals(entity.getStatus())) {
            throw new IllegalStateException("审批请求已处理: " + entity.getStatus());
        }
        entity.setStatus("rejected");
        entity.setApprover(approver);
        entity.setApprovalComment(comment);
        entity.setApprovedAt(Instant.now().toEpochMilli());
        approvalMapper.updateById(entity);
        log.info("审批驳回: requestId={}, approver={}", requestId, approver);
        notifyHitlGateway(requestId, "REJECTED", approver, comment);
    }

    /**
     * 通知 HITL 网关决策已提交，恢复阻塞的 Mono。
     */
    private void notifyHitlGateway(String approvalId, String decision, String operatorId, String value) {
        if (hitlGatewayProvider != null) {
            try {
                HitlGateway gateway = hitlGatewayProvider.getIfAvailable();
                if (gateway != null) {
                    gateway.submitDecision(approvalId, HumanDecision.builder()
                            .decision(decision)
                            .value(value)
                            .operatorId(operatorId != null ? operatorId : "system")
                            .build());
                }
            } catch (Exception e) {
                log.debug("notifyHitlGateway failed (可能非 HITL 场景): {}", e.getMessage());
            }
        }
    }

    /**
     * 创建审批请求（供编排引擎 HITL 节点调用）。
     */
    public ApprovalRequestEntity createApproval(String executionId, String nodeId,
                                                 String approvalType, String payloadJson,
                                                 long timeoutMinutes) {
        ApprovalRequestEntity entity = new ApprovalRequestEntity();
        entity.setId(Ulid.next());
        entity.setExecutionId(executionId);
        entity.setNodeId(nodeId);
        entity.setApprovalType(approvalType != null ? approvalType : "MANUAL_REVIEW");
        entity.setPayload(payloadJson);
        entity.setStatus("pending");
        entity.setTimeoutAt(Instant.now().toEpochMilli() + timeoutMinutes * 60_000);
        approvalMapper.insert(entity);
        log.info("创建审批请求: id={}, executionId={}, nodeId={}", entity.getId(), executionId, nodeId);
        return entity;
    }

    // ==================== 辅助方法 ====================

    private OrchestrationExecutionEntity createExecutionEntity(String executionId, String graphId,
                                                                String userId, String sessionId,
                                                                OrchestrationGraphEntity graphEntity) {
        OrchestrationExecutionEntity entity = new OrchestrationExecutionEntity();
        entity.setId(executionId);
        entity.setGraphId(graphId);
        entity.setGraphSnapshot(graphEntity.getGraphDefinition());
        entity.setUserId(userId);
        entity.setSessionId(sessionId);
        entity.setStatus("PENDING");
        entity.setIterationCount(0);
        entity.setTokenUsed(0L);
        entity.setCreatedBy(userId);
        entity.setUpdatedBy(userId);
        executionMapper.insert(entity);
        return entity;
    }

    private void updateExecutionResult(OrchestrationExecutionEntity entity, OrchestrationResult result) {
        entity.setStatus(result.getStatus());
        entity.setFinalOutput(result.getFinalOutput());
        entity.setErrorMessage(result.getErrorMessage());
        entity.setTokenUsed(result.getTokenUsed());
        entity.setCompletedAt(Instant.now().toEpochMilli());
        if (result.getDurationMs() > 0) {
            entity.setStartedAt(Instant.now().toEpochMilli() - result.getDurationMs());
        }
    }

    private OrchestrationGraph deserializeGraph(OrchestrationGraphEntity entity) {
        try {
            return objectMapper.readValue(entity.getGraphDefinition(), OrchestrationGraph.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("编排图定义反序列化失败: " + entity.getId(), e);
        }
    }
}
