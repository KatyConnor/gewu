package com.gewu.domain.agent.enums;

/**
 * Agent 执行账本状态（agent_execution.status，AE-P2-2 enum 化）。
 * <p>DB 保持 varchar 存储（值=枚举 name()），应用层以本枚举消灭字符串比较；
 * 状态机：RUNNING → COMPLETED | FAILED（由 AgentExecutionService 驱动）。
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-11-13
 */
public enum AgentExecutionStatus {

    /** 执行中（创建即置） */
    RUNNING,

    /** 成功完成（含产出与 token 用量） */
    COMPLETED,

    /** 失败（含错误信息） */
    FAILED
}
