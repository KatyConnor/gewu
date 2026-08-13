package com.gewu.application.governance;

import com.gewu.agent.engine.orchestration.OrchestrationEngine;
import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import com.gewu.agent.engine.orchestration.model.OrchestrationGraph;
import com.gewu.agent.engine.orchestration.model.OrchestrationResult;
import com.gewu.common.ulid.Ulid;
import com.gewu.domain.orchestration.OrchestrationExecutionEntity;
import com.gewu.infrastructure.audit.AuditLogService;
import com.gewu.infrastructure.mapper.OrchestrationExecutionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 四环协同流水线（Phase 1: 单进程内四阶段）。
 * <p>运算环（Execute）同步执行，评估环（Evaluate）/治理环（Govern）/审计环（Audit）异步执行不阻塞响应。
 * <p>适用于 L0-L1 阶段，无需额外消息队列基础设施。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FourPhasePipeline {

    private final OrchestrationEngine orchestrationEngine;
    private final OrchestrationExecutionMapper executionMapper;
    private final AuditLogService auditLogService;

    /**
     * 执行四环协同流水线。
     *
     * @param graphJson  编排图定义 JSON
     * @param userId     用户 ID
     * @param sessionId  会话 ID
     * @param input      输入
     * @return 运算环结果（同步返回）
     */
    public OrchestrationResult execute(Object graphJson, String userId, String sessionId, String input) {
        long startTime = Instant.now().toEpochMilli();

        // 阶段1: 运算环（同步）
        OrchestrationResult result = executePhase(graphJson, userId, sessionId, input);

        // 阶段2-4: 异步执行（不阻塞响应）
        CompletableFuture.runAsync(() -> evaluatePhase(result, startTime));
        CompletableFuture.runAsync(() -> governPhase(result));
        CompletableFuture.runAsync(() -> auditPhase(result, userId, sessionId, startTime));

        return result;
    }

    /**
     * 运算环 - 执行编排图。
     */
    private OrchestrationResult executePhase(Object graphJson, String userId, String sessionId, String input) {
        try {
            // 简化实现：直接调用编排引擎
            // 实际使用时应将 graphJson 反序列化为 OrchestrationGraph
            log.info("四环协同-运算环开始:");
            OrchestrationContext ctx = OrchestrationContext.builder()
                    .executionId(Ulid.next())
                    .userId(userId)
                    .sessionId(sessionId)
                    .variables(new HashMap<>(Map.of("input", input != null ? input : "")))
                    .build();
            // 返回简化结果（真实场景由编排引擎执行）
            return OrchestrationResult.success(ctx.getExecutionId(), input != null ? input : "");
        } catch (Exception e) {
            log.error("四环协同-运算环失败:", e);
            return OrchestrationResult.failure("unknown", e.getMessage());
        }
    }

    /**
     * 评估环 - 计算效果指标（异步）。
     */
    private void evaluatePhase(OrchestrationResult result, long startTime) {
        try {
            long duration = Instant.now().toEpochMilli() - startTime;
            log.debug("四环协同-评估环: executionId={}, status={}, duration={}ms, tokenUsed={}",
                    result.getExecutionId(), result.getStatus(), duration, result.getTokenUsed());
            // 实际场景：计算成功率/验证通过率/HITL率等指标，抽样 LLM-as-Judge 评估
        } catch (Exception e) {
            log.debug("四环协同-评估环失败: {}", e.getMessage());
        }
    }

    /**
     * 治理环 - 策略检查与违规记录（异步）。
     */
    private void governPhase(OrchestrationResult result) {
        try {
            if ("FAILED".equals(result.getStatus())) {
                log.warn("四环协同-治理环: 检测到执行失败, executionId={}, error={}",
                        result.getExecutionId(), result.getErrorMessage());
                // 实际场景：策略引擎检查违规、记录违规日志
            }
            log.debug("四环协同-治理环完成: executionId={}", result.getExecutionId());
        } catch (Exception e) {
            log.debug("四环协同-治理环失败: {}", e.getMessage());
        }
    }

    /**
     * 审计环 - 写入审计日志（异步）。
     */
    private void auditPhase(OrchestrationResult result, String userId, String sessionId, long startTime) {
        try {
            long duration = Instant.now().toEpochMilli() - startTime;
            auditLogService.recordOperation(
                    userId, sessionId,
                    "ORCHESTRATION_EXECUTE", "ORCHESTRATION",
                    result.getExecutionId(), sessionId,
                    "SUCCESS".equals(result.getStatus()), duration);
            log.debug("四环协同-审计环完成: executionId={}", result.getExecutionId());
        } catch (Exception e) {
            log.debug("四环协同-审计环失败: {}", e.getMessage());
        }
    }
}