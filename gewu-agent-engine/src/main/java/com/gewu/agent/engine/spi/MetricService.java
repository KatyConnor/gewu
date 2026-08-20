package com.gewu.agent.engine.spi;

import java.util.Map;

/**
 * 指标服务 SPI - Agent 执行指标的记录与查询。
 * <p>共享知识层的指标组件，记录成功率/延迟/验证通过率/HITL率等。
 *
 * @since 1.0.0
 */
public interface MetricService {

    /**
     * 记录指标。
     *
     * @param name  指标名（如 agent.task.success / agent.verification.score）
     * @param value 指标值
     * @param tags  标签（agentId/executionId/roleCode 等）
     */
    void recordMetric(String name, double value, Map<String, String> tags);

    /**
     * 查询指标。
     */
    default double getMetric(String name, Map<String, String> filter) {
        return 0;
    }
}