package com.gewu.application.governance;

import com.gewu.agent.engine.orchestration.OrchestrationEngine;
import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import com.gewu.agent.engine.orchestration.model.OrchestrationGraph;
import com.gewu.agent.engine.orchestration.model.OrchestrationResult;
import com.gewu.agent.engine.spi.MetricService;
import com.gewu.agent.engine.spi.PolicyService;
import com.gewu.application.audit.AuditChainService;
import com.gewu.domain.orchestration.OrchestrationExecutionEntity;
import com.gewu.infrastructure.audit.AuditLogService;
import com.gewu.infrastructure.mapper.OrchestrationExecutionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 四环协同流水线（Phase 1: 单进程内四阶段）。
 * <p>运算环（Execute）同步执行编排图；评估环（Evaluate）记录效果指标、
 * 治理环（Govern）执行策略检查与违规记录、审计环（Audit）写审计日志与
 * WORM 审计链（链式 SHA-256，不可篡改）。后三环异步执行不阻塞响应。
 * <p>编排执行主链路（{@code OrchestrationService}）在执行完成/失败/取消时
 * 通过 {@link #postProcess} 挂接后三环。
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
    private final ObjectProvider<PolicyService> policyServiceProvider;
    private final ObjectProvider<MetricService> metricServiceProvider;
    private final ObjectProvider<com.gewu.application.evaluation.EvaluationService> evaluationServiceProvider;

    /** WORM 审计链（可选注入） */
    @Autowired(required = false)
    private AuditChainService auditChainService;

    /**
     * 完整四环执行：运算环同步执行编排图，完成后异步触发评估/治理/审计环。
     */
    public OrchestrationResult executeGraph(OrchestrationGraph graph, OrchestrationContext ctx) {
        long startTime = Instant.now().toEpochMilli();

        // 阶段1: 运算环（同步）
        OrchestrationResult result;
        try {
            result = orchestrationEngine.execute(graph, ctx);
        } catch (Exception e) {
            log.error("四环协同-运算环失败: executionId={}", ctx.getExecutionId(), e);
            result = OrchestrationResult.failure(ctx.getExecutionId(), e.getMessage());
            postProcess(result, ctx.getUserId(), ctx.getSessionId(), startTime);
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
        }

        // 阶段2-4: 异步执行（不阻塞响应）
        postProcess(result, ctx.getUserId(), ctx.getSessionId(), startTime);
        return result;
    }

    /**
     * 后三环挂接入口：供已完成运算环的调用方（同步/流式编排执行）触发
     * 评估环 + 治理环 + 审计环（全部异步）。
     */
    public void postProcess(OrchestrationResult result, String userId, String sessionId, long startTime) {
        CompletableFuture.runAsync(() -> evaluatePhase(result, startTime));
        CompletableFuture.runAsync(() -> governPhase(result, userId));
        CompletableFuture.runAsync(() -> auditPhase(result, userId, sessionId, startTime));
    }

    /**
     * 运算环结果是否成功（SUCCESS 状态判定口径统一）。
     */
    public static boolean isSuccessful(OrchestrationResult result) {
        return result != null && "SUCCESS".equals(result.getStatus());
    }

    /**
     * 评估环 - 记录执行效果指标：耗时/成败/token 消耗（MetricService SPI）。
     */
    private void evaluatePhase(OrchestrationResult result, long startTime) {
        try {
            MetricService metricService = metricServiceProvider.getIfAvailable();
            if (metricService == null) {
                return;
            }
            long duration = Instant.now().toEpochMilli() - startTime;
            boolean success = isSuccessful(result);
            Map<String, String> tags = new HashMap<>();
            tags.put("status", result.getStatus());
            metricService.recordMetric("orchestration.duration", duration, tags);
            metricService.recordMetric("orchestration.success", success ? 1 : 0, tags);
            if (result.getTokenUsed() > 0) {
                metricService.recordMetric("orchestration.tokens", result.getTokenUsed(), tags);
            }
            // LlmJudge 采样评估：按采样率对最终输出做锚点评分（分数进入 SPC 控制图）
            com.gewu.application.evaluation.EvaluationService evaluationService =
                    evaluationServiceProvider.getIfAvailable();
            if (evaluationService != null) {
                evaluationService.evaluateExecutionSampled(result.getExecutionId(), result.getFinalOutput());
            }
            log.debug("四环协同-评估环完成: executionId={}, duration={}ms", result.getExecutionId(), duration);
        } catch (Exception e) {
            log.debug("四环协同-评估环失败: {}", e.getMessage());
        }
    }

    /**
     * 治理环 - 策略检查与违规记录（PolicyService SPI）。
     * <p>执行失败视为治理事件：快照三个核心场景的当前策略，
     * 将违规轨迹写入 WORM 审计链供事后追溯。
     */
    private void governPhase(OrchestrationResult result, String userId) {
        try {
            PolicyService policyService = policyServiceProvider.getIfAvailable();
            if (policyService == null) {
                return;
            }
            if (isSuccessful(result)) {
                log.debug("四环协同-治理环完成（成功执行，无违规）: executionId={}", result.getExecutionId());
                return;
            }
            Map<String, Object> hitlPolicy = policyService.getActivePolicy("hitl_threshold");
            Map<String, Object> toolPolicy = policyService.getActivePolicy("tool_permission");
            log.warn("四环协同-治理环: 检测到执行失败, executionId={}, error={}, hitlThreshold={}, toolDeny={}",
                    result.getExecutionId(), result.getErrorMessage(),
                    hitlPolicy.get("confidenceThreshold"),
                    toolPolicy.get("deny"));

            // 失败执行的违规轨迹写入 WORM 审计链（治理留痕）
            if (auditChainService != null) {
                String violationTrace = String.format(
                        "{\"status\":\"%s\",\"error\":\"%s\",\"hitlThreshold\":%s}",
                        result.getStatus(),
                        result.getErrorMessage() != null ? result.getErrorMessage() : "",
                        hitlPolicy.get("confidenceThreshold"));
                auditChainService.append("GOVERNANCE_VIOLATION", result.getExecutionId(),
                        userId != null ? userId : "system", "govern_check", violationTrace);
            }
            log.debug("四环协同-治理环完成: executionId={}", result.getExecutionId());
        } catch (Exception e) {
            log.debug("四环协同-治理环失败: {}", e.getMessage());
        }
    }

    /**
     * 审计环 - 写入审计日志 + WORM 链式哈希审计链（异步）。
     */
    private void auditPhase(OrchestrationResult result, String userId, String sessionId, long startTime) {
        try {
            long duration = Instant.now().toEpochMilli() - startTime;
            boolean success = isSuccessful(result);
            auditLogService.recordOperation(
                    userId, sessionId,
                    "ORCHESTRATION_EXECUTE", "ORCHESTRATION",
                    result.getExecutionId(), sessionId,
                    success, duration);

            // 追加到 WORM 审计链（链式 SHA-256 哈希，不可篡改）
            if (auditChainService != null) {
                String decisionTrace = String.format(
                        "{\"status\":\"%s\",\"durationMs\":%d,\"tokenUsed\":%d,\"outputLen\":%d}",
                        result.getStatus(), duration, result.getTokenUsed(),
                        result.getFinalOutput() != null ? result.getFinalOutput().length() : 0);
                auditChainService.append("ORCHESTRATION", result.getExecutionId(),
                        userId != null ? userId : "system",
                        success ? "execute_success" : "execute_failed",
                        decisionTrace);
            }
            log.debug("四环协同-审计环完成: executionId={}", result.getExecutionId());
        } catch (Exception e) {
            log.debug("四环协同-审计环失败: {}", e.getMessage());
        }
    }
}
