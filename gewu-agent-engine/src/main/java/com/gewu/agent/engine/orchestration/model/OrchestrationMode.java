package com.gewu.agent.engine.orchestration.model;

/**
 * 多 Agent 编排模式。
 *
 * @since 1.0.0
 */
public enum OrchestrationMode {
    /** 监督者路由 - 中央 Supervisor 动态分派任务给专家 Agent */
    SUPERVISOR,
    /** 专家流水线 - 固定顺序串行，产出逐级传递 */
    PIPELINE,
    /** 群体交接 - Agent 间自主 handoff 控制权传递 */
    SWARM,
    /** 辩论共识 - 多方案辩论 + 裁判裁决 */
    DEBATE
}
