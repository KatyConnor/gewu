package com.gewu.agent.engine.scenario;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * 多场景适配框架 - 按业务场景动态裁剪编排引擎配置。
 * <p>每个场景注册一个 {@link ScenarioAdapter}，定义该场景下的：
 * <ul>
 *   <li>默认编排模式（Pipeline/Supervisor/Swarm/Debate）</li>
 *   <li>引擎裁剪策略（L1/L2/L3 任务等级）</li>
 *   <li>角色组合（哪些 SDLC 角色参与）</li>
 *   <li>预算配额（Token/时间/成本/轮次）</li>
 *   <li>HITL 阈值（哪些操作需人工审批）</li>
 * </ul>
 * 新场景接入只需实现 ScenarioAdapter 并注册，目标 < 2 周接入。
 *
 * @since 1.0.0
 */
@Slf4j
public class ScenarioAdapterRegistry {

    private final Map<String, ScenarioAdapter> adapters = new HashMap<>();

    /**
     * 注册场景适配器。
     */
    public void register(ScenarioAdapter adapter) {
        adapters.put(adapter.scenarioId(), adapter);
        log.info("ScenarioAdapterRegistry.register: {} -> {}", adapter.scenarioId(), adapter.scenarioName());
    }

    /**
     * 获取场景适配器。
     */
    public ScenarioAdapter getAdapter(String scenarioId) {
        ScenarioAdapter adapter = adapters.get(scenarioId);
        if (adapter == null) {
            log.warn("ScenarioAdapterRegistry: 未注册的场景 {}, 使用默认", scenarioId);
            return DEFAULT;
        }
        return adapter;
    }

    /**
     * 列出所有已注册场景。
     */
    public java.util.List<String> listScenarios() {
        return java.util.List.copyOf(adapters.keySet());
    }

    // ===== 默认场景适配器 =====

    /** 默认场景 */
    public static final ScenarioAdapter DEFAULT = create("default", "默认场景", "PIPELINE", "L2",
            java.util.List.of("DEVELOPER"), 50_000L, 300_000L, 10, op -> "deploy".equals(op));

    /** 代码生成场景 */
    public static final ScenarioAdapter CODE_GENERATION = create("code_generation", "代码生成", "PIPELINE", "L2",
            java.util.List.of("REQUIREMENT_PM", "ARCHITECT", "DEVELOPER", "CODE_REVIEW", "TEST_ENGINEER"),
            100_000L, 600_000L, 15, op -> "deploy".equals(op) || "merge".equals(op));

    /** 智能问答场景 */
    public static final ScenarioAdapter KNOWLEDGE_QA = create("knowledge_qa", "智能问答", "PIPELINE", "L1",
            java.util.List.of("DEVELOPER"), 10_000L, 30_000L, 3, op -> false);

    /** 架构设计场景 */
    public static final ScenarioAdapter ARCHITECTURE = create("architecture", "架构设计", "DEBATE", "L3",
            java.util.List.of("ARCHITECT", "SECURITY_AUDIT", "DATABASE_DESIGN", "DEVOPS", "SRE"),
            200_000L, 1_800_000L, 20, op -> true);

    /** 工厂方法 - 创建场景适配器 */
    private static ScenarioAdapter create(String id, String name, String mode, String level,
                                           java.util.List<String> roles, long tokenBudget,
                                           long timeBudgetMs, int maxRounds,
                                           java.util.function.Predicate<String> hitlPredicate) {
        return new ScenarioAdapter() {
            @Override public String scenarioId() { return id; }
            @Override public String scenarioName() { return name; }
            @Override public String defaultMode() { return mode; }
            @Override public String taskLevel() { return level; }
            @Override public java.util.List<String> roleCodes() { return roles; }
            @Override public long tokenBudget() { return tokenBudget; }
            @Override public long timeBudgetMs() { return timeBudgetMs; }
            @Override public int maxRounds() { return maxRounds; }
            @Override public boolean requireHITLFor(String operation) { return hitlPredicate.test(operation); }
        };
    }
}