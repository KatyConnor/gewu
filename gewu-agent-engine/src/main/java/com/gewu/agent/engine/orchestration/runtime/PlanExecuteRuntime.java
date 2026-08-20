package com.gewu.agent.engine.orchestration.runtime;

import com.gewu.agent.engine.core.AgentExecutor;
import com.gewu.agent.engine.core.AgentTask;
import com.gewu.agent.engine.core.event.AgentEvent;
import com.gewu.agent.engine.orchestration.GoalPlanner;
import com.gewu.agent.engine.orchestration.model.AutonomousGoal;
import com.gewu.agent.engine.orchestration.model.ExecutionGraph;
import com.gewu.agent.engine.orchestration.model.GraphEdge;
import com.gewu.agent.engine.orchestration.model.GraphNode;
import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import com.gewu.agent.engine.orchestration.model.PlanGraph;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plan-Execute 运行时 - 双图解耦的真实落地。
 * <p>执行链路：
 * <ol>
 *   <li>规划：{@link GoalPlanner#plan} 将任务映射为计划图（逻辑层，人类可理解）</li>
 *   <li>映射：{@link ExecutionGraph#fromPlanGraph} 将计划图映射为执行图（技术层）</li>
 *   <li>执行：按依赖拓扑序逐步骤委托 {@link AgentExecutor}（ReAct）执行，
 *       前置步骤产出注入后续步骤上下文</li>
 * </ol>
 * 适用于边界清晰、步骤明确但需要先拆解的复杂任务。
 *
 * @since 1.0.0
 */
@Slf4j
public class PlanExecuteRuntime implements AgentRuntime {

    /** 单步骤产出注入后续上下文的最大长度 */
    private static final int MAX_CARRIED_CONTEXT = 2000;

    private final AgentExecutor executor;
    /** 目标规划器（可选；为 null 时回退单步执行，等效 ReactRuntime） */
    private final GoalPlanner goalPlanner;

    public PlanExecuteRuntime(AgentExecutor executor) {
        this(executor, null);
    }

    public PlanExecuteRuntime(AgentExecutor executor, GoalPlanner goalPlanner) {
        this.executor = executor;
        this.goalPlanner = goalPlanner;
    }

    @Override
    public Flux<AgentEvent> execute(AgentTask task) {
        log.info("PlanExecute 执行: agentId={}, message={}", task.getAgentId(), task.getMessage());

        return Flux.defer(() -> {
            // 1. 规划：任务 -> 计划图（逻辑层）
            sinkSafeStatus("正在规划任务...");
            PlanGraph plan = buildPlan(task);

            // 2. 映射：计划图 -> 执行图（技术层）
            ExecutionGraph executionGraph = ExecutionGraph.fromPlanGraph(plan, null);
            List<GraphNode> ordered = topologicalOrder(executionGraph);
            Map<String, String> descriptions = stepDescriptions(plan);

            log.info("PlanExecute 规划完成: planId={}, steps={}, agentId={}",
                    plan.getPlanId(), ordered.size(), task.getAgentId());

            // 3. 逐步骤执行：前置产出注入后续上下文
            StringBuilder carriedContext = new StringBuilder();
            Flux<AgentEvent> body = Flux.fromIterable(ordered)
                    .concatMap(node -> executeStep(task, node, descriptions, carriedContext));

            return Flux.concat(
                    Flux.just(
                            AgentEvent.builder().type(AgentEvent.STATUS)
                                    .content("正在规划任务...").build(),
                            AgentEvent.builder().type(AgentEvent.STATUS)
                                    .content("规划完成: " + ordered.size() + " 个步骤，开始逐步执行")
                                    .metadata(Map.of("planId", plan.getPlanId() != null ? plan.getPlanId() : ""))
                                    .build()),
                    body,
                    Flux.just(AgentEvent.builder().type(AgentEvent.STATUS)
                            .content("全部 " + ordered.size() + " 个步骤执行完成").build()))
                    .onErrorResume(e -> {
                        log.error("PlanExecute 异常: agentId={}", task.getAgentId(), e);
                        return Flux.just(AgentEvent.builder()
                                .type(AgentEvent.ERROR)
                                .errorMessage("Plan-Execute 执行失败: " + e.getMessage())
                                .build());
                    });
        });
    }

    /** 执行单个步骤：产出事件透传（补 nodeId），内容累积注入后续上下文 */
    private Flux<AgentEvent> executeStep(AgentTask task, GraphNode node,
                                         Map<String, String> descriptions, StringBuilder carriedContext) {
        String description = descriptions.getOrDefault(node.getNodeId(), task.getMessage());
        String message = carriedContext.length() == 0 ? description
                : description + "\n\n## 前置步骤产出\n" + carriedContext;

        AgentTask stepTask = AgentTask.builder()
                .agentId(node.getRefId() != null ? node.getRefId() : task.getAgentId())
                .sessionId(task.getSessionId())
                .userId(task.getUserId())
                .message(message)
                .build();

        StringBuilder accumulated = new StringBuilder();
        return Flux.just(AgentEvent.builder().type(AgentEvent.STATUS)
                        .nodeId(node.getNodeId())
                        .content("执行步骤 " + node.getNodeId() + ": " + description)
                        .build())
                .concatWith(executor.executeStream(stepTask)
                        .doOnNext(event -> {
                            if (AgentEvent.CONTENT.equals(event.getType()) && event.getContent() != null) {
                                accumulated.append(event.getContent());
                            }
                        })
                        .map(event -> AgentEvent.builder()
                                .type(event.getType())
                                .content(event.getContent())
                                .reasoning(event.getReasoning())
                                .toolCall(event.getToolCall())
                                .toolResult(event.getToolResult())
                                .errorMessage(event.getErrorMessage())
                                .nodeId(node.getNodeId())
                                .role(node.getRoleCode())
                                .build()))
                .doOnComplete(() -> {
                    if (accumulated.length() > 0 && carriedContext.length() < MAX_CARRIED_CONTEXT * 4) {
                        if (carriedContext.length() > 0) {
                            carriedContext.append("\n\n---\n\n");
                        }
                        carriedContext.append("[").append(node.getNodeId()).append("] ")
                                .append(truncate(accumulated.toString(), MAX_CARRIED_CONTEXT));
                    }
                });
    }

    /** 规划：优先委托 GoalPlanner，无规划器或失败时回退单步计划（等效 ReactRuntime） */
    private PlanGraph buildPlan(AgentTask task) {
        if (goalPlanner != null) {
            try {
                AutonomousGoal goal = AutonomousGoal.builder()
                        .goalId(task.getSessionId() != null ? task.getSessionId() : "pe-" + task.getAgentId())
                        .description(task.getMessage())
                        .type("FEATURE")
                        .build();
                PlanGraph plan = goalPlanner.plan(goal, new OrchestrationContext());
                if (plan != null && plan.getSteps() != null && !plan.getSteps().isEmpty()) {
                    return plan;
                }
            } catch (Exception e) {
                log.warn("PlanExecute 规划失败，回退单步执行: {}", e.getMessage());
            }
        }
        return PlanGraph.builder()
                .planId("plan-" + UUID.randomUUID())
                .goal(task.getMessage())
                .createdBy("PlanExecuteRuntime")
                .steps(List.of(PlanGraph.PlanStep.builder()
                        .stepId("step-1")
                        .description(task.getMessage())
                        .dependencies(List.of())
                        .estimatedComplexity(1)
                        .build()))
                .build();
    }

    /** 计划步骤描述索引（stepId -> description） */
    private Map<String, String> stepDescriptions(PlanGraph plan) {
        Map<String, String> descriptions = new LinkedHashMap<>();
        if (plan.getSteps() != null) {
            plan.getSteps().forEach(s -> descriptions.put(s.getStepId(), s.getDescription()));
        }
        return descriptions;
    }

    /** 执行图节点拓扑排序（Kahn）；有环或遗漏时按声明顺序补齐，保证步骤不丢 */
    private List<GraphNode> topologicalOrder(ExecutionGraph graph) {
        List<GraphNode> nodes = graph.getNodes() != null ? graph.getNodes() : List.of();
        if (nodes.size() <= 1) {
            return new ArrayList<>(nodes);
        }
        List<GraphEdge> edges = graph.getEdges() != null ? graph.getEdges() : List.of();

        Map<String, GraphNode> byId = new LinkedHashMap<>();
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        nodes.forEach(n -> {
            byId.put(n.getNodeId(), n);
            inDegree.put(n.getNodeId(), 0);
        });
        for (GraphEdge edge : edges) {
            if (byId.containsKey(edge.getFromNode()) && byId.containsKey(edge.getToNode())) {
                inDegree.merge(edge.getToNode(), 1, Integer::sum);
            }
        }

        Deque<String> ready = new ArrayDeque<>();
        inDegree.forEach((id, degree) -> {
            if (degree == 0) {
                ready.add(id);
            }
        });

        List<GraphNode> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            ordered.add(byId.get(id));
            for (GraphEdge edge : edges) {
                if (id.equals(edge.getFromNode())) {
                    int degree = inDegree.merge(edge.getToNode(), -1, Integer::sum);
                    if (degree == 0) {
                        ready.add(edge.getToNode());
                    }
                }
            }
        }
        if (ordered.size() < nodes.size()) {
            for (GraphNode node : nodes) {
                if (!ordered.contains(node)) {
                    ordered.add(node);
                }
            }
        }
        return ordered;
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...(截断)";
    }

    private void sinkSafeStatus(String message) {
        // 占位：日志层面的规划阶段提示（事件流中的提示在 Flux.concat 头部统一发出）
        log.debug("PlanExecute: {}", message);
    }
}
