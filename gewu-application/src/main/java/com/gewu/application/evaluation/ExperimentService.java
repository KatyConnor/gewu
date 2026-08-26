package com.gewu.application.evaluation;

import com.gewu.infrastructure.dto.ExperimentGroupStats;
import com.gewu.infrastructure.mapper.AgentExecutionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A/B 实验对比服务（T3.4）。
 * <p>按 {@code agent_execution.experiment_group} 聚合成功率/时长/成本/评测分，
 * 为认知层决策（Sprint 4 T4.6 接线或裁剪）提供数据依据。
 * <p>分组来源：Agent modelConfig JSON 的 {@code experimentGroup} 字段，
 * 执行时由 AgentExecutionEngine 写入 agent_execution。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExperimentService {

    private final AgentExecutionMapper agentExecutionMapper;

    /**
     * 预置对照组（四组配置样例，写入 Agent modelConfig JSON 即生效）。
     */
    public static final Map<String, String> PRESET_GROUPS = Map.of(
            "baseline", "纯 ReAct 基线：仅 maxToolRounds 循环，无复杂度路由/模型路由/认知增强",
            "complexity", "+复杂度路由：L1-L3 预算分级生效（budget.* 配置）",
            "model_route", "+模型路由：ModelSelector 按复杂度/预算动态选模型",
            "full_stack", "全开：复杂度路由 + 模型路由 + 记忆注入 + 语义缓存 + 双闭环验证"
    );

    /**
     * 分组对比报表。
     *
     * @param from 开始时间（毫秒，null 不限）
     * @param to 结束时间（毫秒，null 不限）
     * @return 分组 -> 统计；附 preset 说明（未产生数据的预置组也出现在结果中，计数为 0）
     */
    public Map<String, Object> compare(Long from, Long to) {
        List<ExperimentGroupStats> stats = agentExecutionMapper.aggregateByExperimentGroup(from, to);

        Map<String, ExperimentGroupStats> byGroup = new LinkedHashMap<>();
        for (ExperimentGroupStats stat : stats) {
            if (stat.getTotalCount() != null && stat.getTotalCount() > 0) {
                byGroup.put(stat.getExperimentGroup(), withDerived(stat));
            }
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("window", Map.of(
                "from", from != null ? from : 0,
                "to", to != null ? to : "now"));
        report.put("groups", byGroup);
        // 预置对照组说明（含尚无数据的组，便于对照实验设计）
        report.put("presetGroups", PRESET_GROUPS);
        report.put("sampleAdvice", "统计显著性建议：每组至少 50 个样本、实验周期至少 3 轮后再决策");
        log.info("实验对比报表: groups={}, window=[{}, {}]", byGroup.keySet(), from, to);
        return report;
    }

    /** 派生成功率（SQL 只返回计数，比率在此计算避免 DB 方言差异） */
    private ExperimentGroupStats withDerived(ExperimentGroupStats stat) {
        if (stat.getTotalCount() != null && stat.getTotalCount() > 0
                && stat.getSuccessCount() != null) {
            stat.setSuccessRate(stat.getSuccessCount() * 1.0 / stat.getTotalCount());
        }
        return stat;
    }
}
