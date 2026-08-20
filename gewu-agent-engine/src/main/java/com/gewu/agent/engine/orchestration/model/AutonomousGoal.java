package com.gewu.agent.engine.orchestration.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 自主目标 - 自主目标驱动执行的入口模型。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AutonomousGoal {

    /** 目标 ID */
    private String goalId;
    /** 高层目标描述，如 "为系统实现 OAuth2 登录模块" */
    private String description;
    /** 目标类型：FEATURE / BUGFIX / REFACTOR / RESEARCH / OPS */
    private String type;
    /** 约束列表，如 "必须用 Spring Security" */
    private List<String> constraints;
    /** 验收标准（可验证） */
    private List<String> acceptances;
    /** 所属领域 / SDLC 阶段 */
    private String domain;
    /** 自主循环上限（防失控，默认 5） */
    @Builder.Default
    private int maxIterations = 5;
    /** Token 预算 */
    private Long budgetTokens;
    /** 时间预算（毫秒，0 表示不限） */
    @Builder.Default
    private long timeBudgetMs = 300_000;
    /** 每节点最大重试次数 */
    @Builder.Default
    private int maxRetriesPerNode = 3;
    /** 最大工具调用次数（资源配额） */
    @Builder.Default
    private int maxToolCalls = 50;
    /** 任务等级 L1/L2/L3（决定预算配额） */
    @Builder.Default
    private String taskLevel = "L2";
    /** 状态：PENDING / DECOMPOSING / EXECUTING / VERIFYING / SUCCESS / FAILED */
    private String status;
}
