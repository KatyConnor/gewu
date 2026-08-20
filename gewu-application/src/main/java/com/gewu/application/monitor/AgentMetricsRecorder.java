package com.gewu.application.monitor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Gauge;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 业务指标仪表化 - L3 Agent 层可观测性组件。
 * <p>使用 Micrometer {@link MeterRegistry} 记录自定义业务指标，通过 Prometheus 暴露。
 * <p>核心指标：
 * <ul>
 *   <li>{@code agent.task.success} (Counter) - 任务成功次数</li>
 *   <li>{@code agent.task.failure} (Counter) - 任务失败次数</li>
 *   <li>{@code agent.task.duration} (Timer) - 任务执行时长</li>
 *   <li>{@code agent.verification.score} (Gauge) - 验证评分</li>
 *   <li>{@code agent.budget.utilization} (Gauge) - 预算利用率</li>
 *   <li>{@code agent.hitl.triggered} (Counter) - HITL 触发次数</li>
 * </ul>
 *
 * @since 1.0.0
 */
@Slf4j
@Component
public class AgentMetricsRecorder {

    private final MeterRegistry meterRegistry;

    private final Counter taskSuccessCounter;
    private final Counter taskFailureCounter;
    private final Counter hitlTriggeredCounter;
    private final Timer taskDurationTimer;
    private final AtomicReference<Double> verificationScore = new AtomicReference<>(0.0);
    private final AtomicReference<Double> budgetUtilization = new AtomicReference<>(0.0);

    public AgentMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        this.taskSuccessCounter = Counter.builder("agent.task.success")
                .description("Agent 任务成功次数")
                .register(meterRegistry);

        this.taskFailureCounter = Counter.builder("agent.task.failure")
                .description("Agent 任务失败次数")
                .register(meterRegistry);

        this.hitlTriggeredCounter = Counter.builder("agent.hitl.triggered")
                .description("HITL 人工介入触发次数")
                .register(meterRegistry);

        this.taskDurationTimer = Timer.builder("agent.task.duration")
                .description("Agent 任务执行时长")
                .register(meterRegistry);

        Gauge.builder("agent.verification.score", verificationScore, AtomicReference::get)
                .description("最近一次验证评分")
                .register(meterRegistry);

        Gauge.builder("agent.budget.utilization", budgetUtilization, AtomicReference::get)
                .description("预算利用率")
                .register(meterRegistry);

        log.info("AgentMetricsRecorder 初始化完成, 6 个指标已注册");
    }

    public void recordTaskSuccess(String agentId, long durationMs) {
        taskSuccessCounter.increment();
        taskDurationTimer.record(java.time.Duration.ofMillis(durationMs));
        if (agentId != null) {
            Counter.builder("agent.task.success").tag("agentId", agentId)
                    .register(meterRegistry).increment();
        }
    }

    public void recordTaskFailure(String agentId, String reason) {
        taskFailureCounter.increment();
        if (agentId != null) {
            Counter.builder("agent.task.failure").tag("agentId", agentId).tag("reason", reason != null ? reason : "unknown")
                    .register(meterRegistry).increment();
        }
    }

    public void recordVerificationResult(boolean passed, double score) {
        verificationScore.set(score);
        if (!passed) {
            Counter.builder("agent.verification.failed").register(meterRegistry).increment();
        }
    }

    public void recordBudgetUsage(long consumed, long budget) {
        budgetUtilization.set(budget > 0 ? (double) consumed / budget : 0);
    }

    public void recordHITLTrigger(String reason) {
        hitlTriggeredCounter.increment();
        if (reason != null) {
            Counter.builder("agent.hitl.triggered").tag("reason", reason)
                    .register(meterRegistry).increment();
        }
    }
}