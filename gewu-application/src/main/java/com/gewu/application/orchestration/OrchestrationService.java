package com.gewu.application.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gewu.agent.engine.core.event.AgentEvent;
import com.gewu.agent.engine.hitl.HitlGateway;
import com.gewu.agent.engine.hitl.HumanDecision;
import com.gewu.agent.engine.orchestration.OrchestrationEngine;
import com.gewu.agent.engine.orchestration.model.AutonomousGoal;
import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import com.gewu.agent.engine.orchestration.model.OrchestrationGraph;
import com.gewu.agent.engine.orchestration.model.OrchestrationMode;
import com.gewu.agent.engine.orchestration.model.OrchestrationResult;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.common.ulid.Ulid;
import com.gewu.domain.orchestration.ApprovalRequestEntity;
import com.gewu.domain.orchestration.OrchestrationExecutionEntity;
import com.gewu.domain.orchestration.OrchestrationGraphEntity;
import com.gewu.domain.orchestration.OrchestrationNodeExecutionEntity;
import com.gewu.infrastructure.mapper.ApprovalRequestMapper;
import com.gewu.infrastructure.mapper.OrchestrationExecutionMapper;
import com.gewu.infrastructure.mapper.OrchestrationGraphMapper;
import com.gewu.infrastructure.mapper.OrchestrationNodeExecutionMapper;
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
    private final GraphDefinitionValidator graphValidator;
    private final OrchestrationNodeExecutionMapper nodeExecutionMapper;

    /** HITL 网关（延迟解析，避免与 DbHitlGatewayAdapter 循环依赖） */
    @Autowired(required = false)
    private ObjectProvider<HitlGateway> hitlGatewayProvider;

    /** 在途执行上下文注册表：取消时提取最终状态快照（版本化变量 + 当前节点） */
    private final Map<String, OrchestrationContext> liveContexts = new java.util.concurrent.ConcurrentHashMap<>();

    // ==================== 编排图 CRUD ====================

    /**
     * 创建编排图定义。
     * <p>图定义语法在此校验，编排模式规范化写入 JSON 的 mode 字段（执行引擎以 JSON 内 mode 为准）。
     */
    public OrchestrationGraphEntity createGraph(String name, String graphDefinitionJson,
                                                 String graphType, String mode, String userId) {
        // 空定义不允许入库：执行时 deserializeGraph 对 null 抛 IAE 且 SSE 请求会 406
        if (graphDefinitionJson == null || graphDefinitionJson.isBlank()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "编排图定义不能为空");
        }
        String normalizedMode = mode != null && !mode.isBlank() ? mode : "PIPELINE";
        OrchestrationGraphEntity entity = new OrchestrationGraphEntity();
        entity.setId(Ulid.next());
        entity.setGraphName(name);
        entity.setGraphDefinition(normalizeDefinitionJson(graphDefinitionJson, normalizedMode));
        entity.setGraphType(graphType != null ? graphType : "AD_HOC");
        entity.setOrchestrationMode(normalizedMode);
        entity.setVersion("1");
        entity.setStatus("draft");
        entity.setCreatedBy(userId);
        entity.setUpdatedBy(userId);
        graphMapper.insert(entity);
        log.info("创建编排图: id={}, name={}, mode={}", entity.getId(), name, normalizedMode);
        return entity;
    }

    /**
     * 更新编排图定义（docs/design/46 报告 B1）。仅 draft 状态可编辑；
     * 保存前执行图结构校验，ERROR 级问题阻断保存。
     */
    public OrchestrationGraphEntity updateGraph(String graphId, String name, String graphDefinitionJson,
                                                 String graphType, String mode, String userId) {
        OrchestrationGraphEntity entity = graphMapper.selectById(graphId);
        if (entity == null) {
            throw new IllegalArgumentException("编排图不存在: " + graphId);
        }
        if (!"draft".equals(entity.getStatus())) {
            throw BusinessException.of(ResultCode.PARAM_INVALID,
                    "仅草稿状态的编排图可编辑，当前状态: " + entity.getStatus());
        }
        if (graphDefinitionJson == null || graphDefinitionJson.isBlank()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "编排图定义不能为空");
        }
        String normalizedMode = mode != null && !mode.isBlank() ? mode : entity.getOrchestrationMode();
        String normalized = normalizeDefinitionJson(graphDefinitionJson, normalizedMode);
        runDefinitionValidation(deserializeNormalized(normalized), entity.getId());
        if (name != null && !name.isBlank()) {
            entity.setGraphName(name);
        }
        if (graphType != null && !graphType.isBlank()) {
            entity.setGraphType(graphType);
        }
        entity.setOrchestrationMode(normalizedMode);
        entity.setGraphDefinition(normalized);
        entity.setUpdatedBy(userId);
        graphMapper.updateById(entity);
        log.info("更新编排图定义: id={}, mode={}", graphId, normalizedMode);
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
        if (entity.getGraphDefinition() == null || entity.getGraphDefinition().isBlank()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID,
                    "编排图定义为空，不能激活。请先编辑保存编排图: " + graphId);
        }
        entity.setStatus("active");
        entity.setUpdatedBy(userId);
        graphMapper.updateById(entity);
        log.info("激活编排图: id={}", graphId);
    }

    /**
     * 删除编排图（逻辑删除），并级联逻辑删除其执行实例与审批请求。
     */
    public void deleteGraph(String graphId) {
        List<OrchestrationExecutionEntity> executions = executionMapper.selectList(
                new LambdaQueryWrapper<OrchestrationExecutionEntity>()
                        .eq(OrchestrationExecutionEntity::getGraphId, graphId));
        if (!executions.isEmpty()) {
            List<String> executionIds = executions.stream()
                    .map(OrchestrationExecutionEntity::getId).toList();
            approvalMapper.delete(new LambdaQueryWrapper<ApprovalRequestEntity>()
                    .in(ApprovalRequestEntity::getExecutionId, executionIds));
        }
        executionMapper.delete(new LambdaQueryWrapper<OrchestrationExecutionEntity>()
                .eq(OrchestrationExecutionEntity::getGraphId, graphId));
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

        OrchestrationGraph graph = loadExecutableGraph(graphEntity);
        String executionId = Ulid.next();
        OrchestrationContext ctx = OrchestrationContext.builder()
                .executionId(executionId)
                .userId(userId)
                .sessionId(sessionId)
                .variables(buildContextVariables(graph, input))
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

        OrchestrationGraph graph = loadExecutableGraph(graphEntity);
        String executionId = Ulid.next();
        OrchestrationContext ctx = OrchestrationContext.builder()
                .executionId(executionId)
                .userId(userId)
                .sessionId(sessionId)
                .variables(buildContextVariables(graph, input))
                .build();

        // 创建执行记录
        OrchestrationExecutionEntity execEntity = createExecutionEntity(executionId, graphId, userId, sessionId, graphEntity);
        execEntity.setStatus("RUNNING");
        execEntity.setStartedAt(Instant.now().toEpochMilli());
        executionMapper.updateById(execEntity);
        liveContexts.put(executionId, ctx);

        // 图终态透传（docs/design/47 问题一）：graph_complete 的 status 决定执行记录终态，
        // 修复"失败图被无条件写 SUCCEEDED"的收尾缺环
        final java.util.concurrent.atomic.AtomicReference<String> graphStatus =
                new java.util.concurrent.atomic.AtomicReference<>("SUCCESS");
        final java.util.concurrent.atomic.AtomicReference<String> graphReason =
                new java.util.concurrent.atomic.AtomicReference<>(null);
        return orchestrationEngine.executeStream(graph, ctx)
                .doOnNext(event -> {
                    // 更新当前节点
                    if (event.getNodeId() != null) {
                        execEntity.setCurrentNodeId(event.getNodeId());
                    }
                    if (AgentEvent.GRAPH_COMPLETE.equals(event.getType()) && event.getMetadata() != null) {
                        graphStatus.set(String.valueOf(event.getMetadata().get("status")));
                        Object reason = event.getMetadata().get("reason");
                        graphReason.set(reason == null ? null : String.valueOf(reason));
                    }
                    // 节点执行记录落库（docs/design/47 问题四，幂等）
                    upsertNodeExecution(graph, executionId, event);
                })
                .doOnComplete(() -> {
                    boolean failed = "FAILED".equals(graphStatus.get());
                    execEntity.setStatus(failed ? "FAILED" : "SUCCEEDED");
                    if (failed) {
                        execEntity.setErrorMessage(graphReason.get());
                    }
                    execEntity.setCompletedAt(Instant.now().toEpochMilli());
                    executionMapper.updateById(execEntity);
                    liveContexts.remove(executionId);
                    // 四环协同：流式完成后触发评估/治理/审计环（失败图同样进入治理/审计）
                    fourPhasePipeline.postProcess(
                            failed
                                    ? OrchestrationResult.failure(executionId,
                                            graphReason.get() != null ? graphReason.get() : "编排执行失败")
                                    : OrchestrationResult.success(executionId,
                                            execEntity.getFinalOutput() != null ? execEntity.getFinalOutput() : ""),
                            userId, sessionId, execEntity.getStartedAt() != null ? execEntity.getStartedAt() : 0L);
                    log.info("编排流式执行完成: executionId={}, status={}", executionId, execEntity.getStatus());
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
     * 查询执行的节点级记录（docs/design/46 FR-14 执行回放数据源），按创建时间升序。
     */
    public List<com.gewu.domain.orchestration.OrchestrationNodeExecutionEntity> listNodeExecutions(String executionId) {
        return nodeExecutionMapper.selectList(new LambdaQueryWrapper<com.gewu.domain.orchestration.OrchestrationNodeExecutionEntity>()
                .eq(com.gewu.domain.orchestration.OrchestrationNodeExecutionEntity::getExecutionId, executionId)
                .orderByAsc(com.gewu.domain.orchestration.OrchestrationNodeExecutionEntity::getCreatedAt));
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
     * 暂停执行：DB 状态置 PAUSED + 引擎协作式信号（当前节点执行完毕后生效，
     * 流以 graph_complete(PAUSED) 优雅结束，检查点保存在引擎执行控制注册表）。
     */
    public void pauseExecution(String executionId) {
        OrchestrationExecutionEntity entity = executionMapper.selectById(executionId);
        if (entity == null) {
            throw new IllegalArgumentException("执行实例不存在: " + executionId);
        }
        if (!"RUNNING".equals(entity.getStatus())) {
            throw new IllegalStateException("仅 RUNNING 状态可暂停，当前状态: " + entity.getStatus());
        }
        orchestrationEngine.pause(executionId);
        entity.setStatus("PAUSED");
        executionMapper.updateById(entity);
        log.info("暂停编排执行: executionId={}", executionId);
    }

    /**
     * 恢复执行：优先从引擎暂停检查点断点续跑（返回续跑事件流供 SSE 订阅），
     * 无检查点时仅恢复 DB 状态（进程重启丢失检查点的场景，由外部重放处理）。
     */
    public boolean resumeExecution(String executionId) {
        OrchestrationExecutionEntity entity = executionMapper.selectById(executionId);
        if (entity == null) {
            throw new IllegalArgumentException("执行实例不存在: " + executionId);
        }
        if (!"PAUSED".equals(entity.getStatus())) {
            throw new IllegalStateException("仅 PAUSED 状态可恢复，当前状态: " + entity.getStatus());
        }
        boolean resumable = orchestrationEngine.isPausable(executionId);
        entity.setStatus("RUNNING");
        executionMapper.updateById(entity);
        log.info("恢复编排执行: executionId={}, engineCheckpoint={}", executionId, resumable);
        return resumable;
    }

    /**
     * 断点续跑事件流（resumeExecution 返回 true 时调用）：
     * 从引擎检查点恢复执行并桥接 DB 状态更新。
     */
    public Flux<AgentEvent> resumeExecutionStream(String executionId) {
        OrchestrationExecutionEntity entity = executionMapper.selectById(executionId);
        if (entity == null) {
            return Flux.error(new IllegalArgumentException("执行实例不存在: " + executionId));
        }
        OrchestrationGraph graph = null;
        try {
            OrchestrationGraphEntity graphEntity = graphMapper.selectById(entity.getGraphId());
            if (graphEntity != null) {
                graph = deserializeGraph(graphEntity);
            }
        } catch (Exception e) {
            log.warn("断点续跑加载图定义失败，节点执行记录不可用: {}", e.getMessage());
        }
        final OrchestrationGraph graphRef = graph;
        return orchestrationEngine.resume(executionId)
                .doOnNext(event -> {
                    if (graphRef != null) {
                        upsertNodeExecution(graphRef, executionId, event);
                    }
                })
                .doOnComplete(() -> finishExecution(entity, "SUCCEEDED", null))
                .doOnError(e -> finishExecution(entity, "FAILED", e.getMessage()))
                .doOnCancel(() -> finishExecution(entity, "CANCELLED", null));
    }

    private void finishExecution(OrchestrationExecutionEntity entity, String status, String error) {
        entity.setStatus(status);
        entity.setErrorMessage(error);
        entity.setCompletedAt(Instant.now().toEpochMilli());
        executionMapper.updateById(entity);
        liveContexts.remove(entity.getId());
    }

    /**
     * 取消执行：置 CANCELLED 前从在途上下文提取最终状态快照
     * （VersionedContext 当前版本变量 + 当前节点），写入执行记录供事后审计与恢复分析。
     */
    public void cancelExecution(String executionId) {
        OrchestrationExecutionEntity entity = executionMapper.selectById(executionId);
        if (entity != null && ("RUNNING".equals(entity.getStatus()) || "PAUSED".equals(entity.getStatus()))) {
            // 引擎协作式取消：运行中发信号（当前节点后优雅结束）；已暂停则丢弃检查点
            orchestrationEngine.cancel(executionId);
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
        String definition = entity.getGraphDefinition();
        if (definition == null || definition.isBlank()) {
            // 空定义图（创建后未保存内容）直接对 null 调 readValue 会抛 IAE，
            // 以可读业务错误返回（SSE 路径经全局处理器转错误帧可达前端）
            throw BusinessException.of(ResultCode.PARAM_INVALID,
                    "编排图定义为空，请先编辑保存编排图后再执行: " + entity.getId());
        }
        try {
            return objectMapper.readValue(definition, OrchestrationGraph.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("编排图定义反序列化失败: " + entity.getId(), e);
        }
    }

    /**
     * 加载可执行图：反序列化 → 编排模式兜底 → 图结构校验（docs/design/46 报告 B2/B4）。
     * <p>模式兜底：JSON 无 mode 字段时回填实体 orchestration_mode 列值（存量图兼容），
     * 使创建弹窗选择的模式对执行生效；列值非法时回退 PIPELINE。
     */
    private OrchestrationGraph loadExecutableGraph(OrchestrationGraphEntity entity) {
        OrchestrationGraph graph = deserializeGraph(entity);
        if (graph.getMode() == null && entity.getOrchestrationMode() != null) {
            try {
                graph.setMode(OrchestrationMode.valueOf(entity.getOrchestrationMode()));
            } catch (IllegalArgumentException e) {
                log.warn("编排图实体模式列非法，执行回退引擎默认: id={}, mode={}",
                        entity.getId(), entity.getOrchestrationMode());
            }
        }
        runDefinitionValidation(graph, entity.getId());
        return graph;
    }

    /**
     * 图结构校验闸：ERROR 级问题以业务异常阻断（保存/执行双闸共用），WARNING 级记录日志放行。
     */
    private void runDefinitionValidation(OrchestrationGraph graph, String graphId) {
        List<GraphDefinitionValidator.ValidationIssue> issues = graphValidator.validate(graph);
        List<String> errors = issues.stream().filter(GraphDefinitionValidator.ValidationIssue::isError)
                .map(issue -> issue.ruleId() + ": " + issue.message())
                .toList();
        if (!errors.isEmpty()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID,
                    "编排图结构校验未通过: " + String.join("; ", errors));
        }
        List<String> warnings = issues.stream().filter(issue -> !issue.isError())
                .map(issue -> issue.ruleId() + ": " + issue.message())
                .toList();
        if (!warnings.isEmpty()) {
            log.warn("编排图存在结构警告: graphId={}, warnings={}", graphId, warnings);
        }
    }

    /**
     * 规范化图定义 JSON：校验语法合法性并把编排模式写入 JSON 的 mode 字段
     * （执行引擎按 JSON 内 mode 分派，见 Orchestrator；docs/design/46 报告 B4）。
     * 通过 JsonNode 树改写，保留模型之外的扩展字段（如设计器画布坐标）。
     */
    private String normalizeDefinitionJson(String graphDefinitionJson, String mode) {
        try {
            JsonNode tree = objectMapper.readTree(graphDefinitionJson);
            if (!tree.isObject()) {
                throw BusinessException.of(ResultCode.PARAM_INVALID, "编排图定义必须是 JSON 对象");
            }
            ((ObjectNode) tree).put("mode", mode);
            return objectMapper.writeValueAsString(tree);
        } catch (JsonProcessingException e) {
            throw BusinessException.of(ResultCode.PARAM_INVALID,
                    "编排图定义不是合法 JSON: " + e.getOriginalMessage());
        }
    }

    /** 解析规范化后的定义文本为图模型（枚举非法值在此暴露为可读业务错误）。 */
    private OrchestrationGraph deserializeNormalized(String graphDefinitionJson) {
        try {
            return objectMapper.readValue(graphDefinitionJson, OrchestrationGraph.class);
        } catch (JsonProcessingException e) {
            throw BusinessException.of(ResultCode.PARAM_INVALID,
                    "编排图定义解析失败: " + e.getOriginalMessage());
        }
    }

    /**
     * 构建执行上下文变量：图定义 variables 先入，运行时用户输入 input 后入覆盖同名键
     * （图变量 modelProvider/modelName 等由此进入执行上下文，供节点级兜底与 ${var.xxx} 模板引用）。
     */
    private Map<String, Object> buildContextVariables(OrchestrationGraph graph, String input) {
        Map<String, Object> variables = new HashMap<>();
        if (graph.getVariables() != null) {
            variables.putAll(graph.getVariables());
        }
        variables.put("input", input != null ? input : "");
        return variables;
    }

    /**
     * 节点执行记录幂等落库（docs/design/47 问题四）：node_start 建 RUNNING 记录，
     * node_complete/error 推进终态。落库失败仅告警不阻断执行。
     */
    private void upsertNodeExecution(OrchestrationGraph graph, String executionId, AgentEvent event) {
        try {
            String type = event.getType();
            boolean start = AgentEvent.NODE_START.equals(type);
            boolean complete = AgentEvent.NODE_COMPLETE.equals(type);
            boolean failed = AgentEvent.ERROR.equals(type);
            if ((!start && !complete && !failed) || event.getNodeId() == null) {
                return;
            }
            OrchestrationNodeExecutionEntity node = nodeExecutionMapper.selectList(
                            new LambdaQueryWrapper<OrchestrationNodeExecutionEntity>()
                                    .eq(OrchestrationNodeExecutionEntity::getExecutionId, executionId)
                                    .eq(OrchestrationNodeExecutionEntity::getNodeId, event.getNodeId()))
                    .stream().findFirst().orElse(null);
            long now = Instant.now().toEpochMilli();
            if (node == null) {
                OrchestrationNodeExecutionEntity created = new OrchestrationNodeExecutionEntity();
                created.setId(Ulid.next());
                created.setExecutionId(executionId);
                created.setNodeId(event.getNodeId());
                graph.getNodes().stream()
                        .filter(n -> event.getNodeId().equals(n.getNodeId()))
                        .findFirst()
                        .ifPresent(n -> {
                            created.setNodeType(n.getType() == null ? "AGENT" : n.getType().name());
                            created.setRoleCode(n.getRoleCode());
                        });
                created.setStatus(start ? "RUNNING" : failed ? "FAILED" : "SUCCEEDED");
                created.setStartedAt(now);
                if (failed) {
                    created.setErrorMessage(event.getErrorMessage());
                    created.setCompletedAt(now);
                }
                nodeExecutionMapper.insert(created);
                return;
            }
            if (complete && !"FAILED".equals(node.getStatus())) {
                node.setStatus("SUCCEEDED");
                node.setCompletedAt(now);
                if (node.getStartedAt() != null) {
                    node.setDurationMs(now - node.getStartedAt());
                }
            } else if (failed) {
                node.setStatus("FAILED");
                node.setErrorMessage(event.getErrorMessage());
                node.setCompletedAt(now);
            } else {
                return;
            }
            nodeExecutionMapper.updateById(node);
        } catch (Exception e) {
            log.warn("节点执行记录落库失败（不影响执行）: executionId={}, nodeId={}, err={}",
                    executionId, event.getNodeId(), e.getMessage());
        }
    }
}
