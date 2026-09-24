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
     * 从计划图映射为执行图（并行波次映射）。
     * <p>按依赖关系做 Kahn 分层：同一波次（层）内相互无依赖的步骤映射为
     * {@code PARALLEL 节点 → N 个步骤 AGENT 节点 → MERGE 节点} 的并行段；
     * 单步骤波次直接串行链接。层与层之间按尾→头衔接，
     * 使"可并行的步骤真正并行执行"而非退化为单链。
     *
     * @param plan           计划图
     * @param strategyConfig 策略配置（stepId -> 配置 JSON）
     * @return 执行图
     */
    public static ExecutionGraph fromPlanGraph(PlanGraph plan, Map<String, String> strategyConfig) {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();

        if (plan.getSteps() != null && !plan.getSteps().isEmpty()) {
            // 步骤索引与 ID 集合（用于过滤未知依赖引用）
            Map<String, PlanGraph.PlanStep> byId = new java.util.LinkedHashMap<>();
            for (PlanGraph.PlanStep step : plan.getSteps()) {
                byId.put(step.getStepId(), step);
            }
            // Kahn 分层：level(s) = 1 + max(level(dep))，未知依赖忽略，自环兜底
            Map<String, Integer> level = new java.util.HashMap<>();
            for (PlanGraph.PlanStep step : plan.getSteps()) {
                computeLevel(step, byId, level, new java.util.HashSet<>());
            }
            // 按层分组（保持声明顺序）
            java.util.TreeMap<Integer, List<PlanGraph.PlanStep>> waves = new java.util.TreeMap<>();
            for (PlanGraph.PlanStep step : plan.getSteps()) {
                waves.computeIfAbsent(level.get(step.getStepId()), k -> new ArrayList<>()).add(step);
            }

            String previousTail = null; // 上一层输出节点（单步骤=步骤节点，并行波=MERGE 节点）
            for (Map.Entry<Integer, List<PlanGraph.PlanStep>> wave : waves.entrySet()) {
                List<PlanGraph.PlanStep> steps = wave.getValue();
                for (PlanGraph.PlanStep step : steps) {
                    String config = strategyConfig != null ? strategyConfig.get(step.getStepId()) : null;
                    nodes.add(GraphNode.builder()
                            .nodeId(step.getStepId())
                            .type(NodeType.AGENT)
                            .refId(null) // 运行时填充
                            .roleCode(step.getRoleCode())
                            .executionMode(ExecutionMode.REACT)
                            .config(config != null ? parseConfig(config) : null)
                            .inputs(null)
                            .build());
                }
                String waveHead;
                String waveTail;
                if (steps.size() == 1) {
                    waveHead = steps.get(0).getStepId();
                    waveTail = waveHead;
                } else {
                    // 并行波：PARALLEL 扇出 -> 各步骤 -> MERGE 汇聚
                    String parallelId = "__parallel_L" + wave.getKey();
                    String mergeId = "__merge_L" + wave.getKey();
                    nodes.add(GraphNode.builder()
                            .nodeId(parallelId)
                            .type(NodeType.PARALLEL)
                            .build());
                    nodes.add(GraphNode.builder()
                            .nodeId(mergeId)
                            .type(NodeType.MERGE)
                            .build());
                    for (PlanGraph.PlanStep step : steps) {
                        edges.add(GraphEdge.builder().fromNode(parallelId).toNode(step.getStepId()).build());
                        edges.add(GraphEdge.builder().fromNode(step.getStepId()).toNode(mergeId).build());
                    }
                    waveHead = parallelId;
                    waveTail = mergeId;
                }
                if (previousTail != null) {
                    edges.add(GraphEdge.builder().fromNode(previousTail).toNode(waveHead).build());
                }
                previousTail = waveTail;
            }
        }

        log.info("ExecutionGraph.fromPlanGraph(波次映射): planId={}, nodes={}, edges={}",
                plan.getPlanId(), nodes.size(), edges.size());

        return ExecutionGraph.builder()
                .executionGraphId(UUID.randomUUID().toString())
                .sourcePlanId(plan.getPlanId())
                .nodes(nodes)
                .edges(edges)
                .techConfig(strategyConfig)
                .build();
    }

    /** 递归计算步骤依赖层级（未知依赖忽略；环路经 visiting 标记兜底，不产生错误层级） */
    private static int computeLevel(PlanGraph.PlanStep step, Map<String, PlanGraph.PlanStep> byId,
                                    Map<String, Integer> level, java.util.Set<String> visiting) {
        Integer cached = level.get(step.getStepId());
        if (cached != null) {
            return cached;
        }
        if (!visiting.add(step.getStepId())) {
            return 1; // 环路兜底：按层级 1 处理，避免无限递归
        }
        int max = 0;
        if (step.getDependencies() != null) {
            for (String dep : step.getDependencies()) {
                PlanGraph.PlanStep depStep = byId.get(dep);
                if (depStep == null || dep.equals(step.getStepId())) {
                    continue; // 未知依赖与自环忽略
                }
                max = Math.max(max, computeLevel(depStep, byId, level, visiting));
            }
        }
        visiting.remove(step.getStepId());
        int result = max + 1;
        level.put(step.getStepId(), result);
        return result;
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