package com.gewu.agent.engine.orchestration.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 执行图 - 技术层操作描述（含模型参数、工具 ID、沙箱配置）。
 * <p>由 {@link PlanGraph} 映射而来，实现"逻辑-技术"解耦。
 * 技术层可独立变化，不影响逻辑层计划图。
 *
 * @since 1.0.0
 */
@Slf4j
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionGraph {

    /** 执行图 ID */
    private String executionGraphId;
    /** 来源计划图 ID */
    private String sourcePlanId;
    /** 执行图节点（含技术细节） */
    private List<GraphNode> nodes;
    /** 执行图边 */
    private List<GraphEdge> edges;
    /** 技术配置（模型/工具/沙箱等） */
    private Map<String, String> techConfig;

    /**
     * 从计划图映射为执行图。
     * <p>每个 PlanStep 映射为一个 AGENT 类型的 GraphNode，
     * 技术配置通过 strategyConfig 注入。
     *
     * @param plan           计划图
     * @param strategyConfig 策略配置（stepId -> 配置 JSON）
     * @return 执行图
     */
    public static ExecutionGraph fromPlanGraph(PlanGraph plan, Map<String, String> strategyConfig) {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();

        if (plan.getSteps() != null) {
            for (PlanGraph.PlanStep step : plan.getSteps()) {
                String config = strategyConfig != null ? strategyConfig.get(step.getStepId()) : null;
                GraphNode node = GraphNode.builder()
                        .nodeId(step.getStepId())
                        .type(NodeType.AGENT)
                        .refId(null) // 运行时填充
                        .roleCode(step.getRoleCode())
                        .executionMode(ExecutionMode.REACT)
                        .config(config != null ? parseConfig(config) : null)
                        .inputs(null)
                        .build();
                nodes.add(node);

                // 构建依赖边
                if (step.getDependencies() != null) {
                    for (String dep : step.getDependencies()) {
                        edges.add(GraphEdge.builder()
                                .fromNode(dep)
                                .toNode(step.getStepId())
                                .condition(null)
                                .build());
                    }
                }
            }
        }

        log.info("ExecutionGraph.fromPlanGraph: planId={}, nodes={}, edges={}",
                plan.getPlanId(), nodes.size(), edges.size());

        return ExecutionGraph.builder()
                .executionGraphId(UUID.randomUUID().toString())
                .sourcePlanId(plan.getPlanId())
                .nodes(nodes)
                .edges(edges)
                .techConfig(strategyConfig)
                .build();
    }

    private static java.util.Map<String, Object> parseConfig(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }
}