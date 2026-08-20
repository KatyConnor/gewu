package com.gewu.application.governance;

import com.gewu.agent.engine.spi.MetricService;
import com.gewu.application.agent.AgentStatService;
import com.gewu.application.monitor.AgentMetricsRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * MetricService SPI 适配器 - 桥接引擎指标服务到平台业务指标记录。
 * <p>将 agent-engine 的 MetricService SPI 委托给：
 * <ul>
 *   <li>{@link AgentMetricsRecorder} - Micrometer Prometheus 指标</li>
 *   <li>{@link AgentStatService} - Agent 执行统计（信任等级数据基础）</li>
 * </ul>
 * <p>启用条件：{@code agent.engine.adapter.enabled=true}
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.engine.adapter.enabled", havingValue = "true")
public class DbMetricServiceAdapter implements MetricService {

    private final AgentMetricsRecorder agentMetricsRecorder;
    private final AgentStatService agentStatService;

    @Override
    public void recordMetric(String name, double value, Map<String, String> tags) {
        if (tags == null) tags = new HashMap<>();

        switch (name) {
            case "agent.task.success" -> {
                String agentId = tags.getOrDefault("agentId", "unknown");
                long durationMs = (long) value;
                agentMetricsRecorder.recordTaskSuccess(agentId, durationMs);
                agentStatService.recordExecution(agentId, true);
            }
            case "agent.task.failure" -> {
                String agentId = tags.getOrDefault("agentId", "unknown");
                agentMetricsRecorder.recordTaskFailure(agentId, tags.get("reason"));
                agentStatService.recordExecution(agentId, false);
            }
            case "agent.verification.score" -> {
                agentMetricsRecorder.recordVerificationResult(value >= 0.6, value);
            }
            case "agent.budget.utilization" -> {
                long consumed = (long) value;
                long budget = Long.parseLong(tags.getOrDefault("budget", "0"));
                agentMetricsRecorder.recordBudgetUsage(consumed, budget);
            }
            case "agent.hitl.triggered" -> {
                agentMetricsRecorder.recordHITLTrigger(tags.get("reason"));
            }
            default -> log.debug("DbMetricServiceAdapter: 未识别的指标名 {}, value={}", name, value);
        }
    }
}