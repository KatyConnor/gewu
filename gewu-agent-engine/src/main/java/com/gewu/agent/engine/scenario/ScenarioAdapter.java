package com.gewu.agent.engine.scenario;

import java.util.List;

/**
 * 场景适配器接口 - 定义业务场景对编排引擎的裁剪配置。
 * <p>新场景接入只需实现此接口并注册到 {@link ScenarioAdapterRegistry}。
 *
 * @since 1.0.0
 */
public interface ScenarioAdapter {

    /** 场景标识 */
    String scenarioId();

    /** 场景名称 */
    String scenarioName();

    /** 默认编排模式: PIPELINE / SUPERVISOR / SWARM / DEBATE */
    String defaultMode();

    /** 任务等级: L1(快速) / L2(标准) / L3(深度) */
    String taskLevel();

    /** 参与角色编码列表 */
    List<String> roleCodes();

    /** Token 预算 */
    long tokenBudget();

    /** 时间预算（毫秒） */
    long timeBudgetMs();

    /** 最大轮次 */
    int maxRounds();

    /**
     * 判断指定操作是否需要人工审批。
     *
     * @param operation 操作类型: deploy / merge / create / delete / execute 等
     * @return true 表示该操作需要人工审批
     */
    boolean requireHITLFor(String operation);
}