package com.gewu.domain.orchestration.enums;

/**
 * 编排执行实例状态（orchestration_execution.status，AE-P2-2 enum 化）。
 * <p>DB 保持 varchar 存储（值=枚举 name()），应用层以本枚举消灭字符串比较。
 * 状态机（OrchestrationService 驱动，49 号评审后的全量终态映射）：
 * <pre>
 * PENDING（Webhook 受理即落）→ RUNNING → SUCCEEDED | FAILED | PAUSED | CANCELLED
 * PAUSED → RUNNING（检查点恢复）| CANCELLED
 * </pre>
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-11-13
 */
public enum OrchestrationExecutionStatus {

    /** 已受理（Webhook 异步触发先落 PENDING，后台 worker 置 RUNNING） */
    PENDING,

    /** 执行中 */
    RUNNING,

    /** 成功完成 */
    SUCCEEDED,

    /** 失败 */
    FAILED,

    /** 已暂停（检查点在，可恢复） */
    PAUSED,

    /** 已取消 */
    CANCELLED
}
