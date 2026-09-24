package com.gewu.agent.engine.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.llm.LlmClient;
import com.gewu.agent.engine.llm.LlmClientRegistry;
import com.gewu.agent.engine.llm.model.LlmRequest;
import com.gewu.agent.engine.llm.model.LlmResponse;
import com.gewu.agent.engine.orchestration.model.AutonomousGoal;
import com.gewu.agent.engine.orchestration.model.ExecutionGraph;
import com.gewu.agent.engine.orchestration.model.GraphNode;
import com.gewu.agent.engine.orchestration.model.GraphType;
import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import com.gewu.agent.engine.orchestration.model.OrchestrationGraph;
import com.gewu.agent.engine.orchestration.model.OrchestrationMode;
import com.gewu.agent.engine.orchestration.model.PlanGraph;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link GoalPlanner} LLM 实现（B-1）：经 LLM 将高层目标真实分解为多步骤计划图，
 * 无依赖步骤经 {@link ExecutionGraph#fromPlanGraph} 波次映射为并行段。
 * <p>输出约定（严格 JSON，允许 ```json 围栏）：
 * <pre>{"steps":[{"id":"s1","description":"...","dependsOn":["s..."],"agentId":"可选","roleCode":"可选"}]}</pre>
 * <p>健壮性：解析失败 / 步骤为空 / 全部字段非法时回退单步骤计划（与 {@link DefaultGoalPlanner} 兜底语义一致）；
 * 未知依赖引用与自环忽略；步骤数截断至 maxSteps。
 *
 * @since 1.0.0
 */
@Slf4j
public class LlmGoalPlanner implements GoalPlanner {

    private final LlmClientRegistry llmClientRegistry;
    private final ObjectMapper objectMapper;
    /** 规划用 LLM 供应商与模型（registry 中已注册的 provider 名） */
    private final String provider;
    private final String model;
    /** 单次分解的最大步骤数 */
    private final int maxSteps;

    public LlmGoalPlanner(LlmClientRegistry llmClientRegistry, ObjectMapper objectMapper,
                          String provider, String model, int maxSteps) {
        this.llmClientRegistry = llmClientRegistry;
        this.objectMapper = objectMapper;
        this.provider = provider;
        this.model = model;
        this.maxSteps = Math.max(1, maxSteps);
    }

    @Override
    public PlanGraph plan(AutonomousGoal goal, OrchestrationContext ctx) {
        String prompt = buildPrompt(goal);
        String content;
        try {
            LlmClient client = llmClientRegistry.getClient(provider);
            LlmResponse response = client.chat(LlmRequest.builder()
                    .model(model)
                    .messages(List.of(
                            com.gewu.agent.engine.llm.model.Message.builder()
                                    .role("system")
                                    .content("你是任务规划器。将目标分解为可由独立 Agent 执行的步骤，只输出 JSON，不要输出任何解释。")
                                    .build(),
                            com.gewu.agent.engine.llm.model.Message.builder()
                                    .role("user")
                                    .content(prompt)
                                    .build()))
                    .temperature(0.2)
                    .stream(false)
                    .build());
            content = response.getContent();
        } catch (Exception e) {
            log.warn("LLM 规划调用失败，回退单步计划: goal={}, cause={}", goal.getDescription(), e.getMessage());
            return singleStepFallback(goal);
        }
        PlanGraph parsed = parsePlan(goal, content);
        if (parsed != null) {
            return parsed;
        }
        log.warn("LLM 规划输出解析失败，回退单步计划: goal={}", goal.getDescription());
        return singleStepFallback(goal);
    }

    @Override
    public OrchestrationGraph decompose(AutonomousGoal goal, OrchestrationContext ctx) {
        OrchestrationMode mode = recommendMode(goal);
        PlanGraph plan = plan(goal, ctx);
        // 双图映射（波次并行版）：计划图 -> 执行图（无依赖步骤生成 PARALLEL/MERGE 并行段）
        ExecutionGraph executionGraph = ExecutionGraph.fromPlanGraph(plan, null);
        // 步骤未绑定 agentId 时经图变量 modelProvider/modelName 兜底解析模型（PipelineModeHandler 支持）
        List<GraphNode> nodes = executionGraph.getNodes();
        if (nodes != null && !nodes.isEmpty()) {
            GraphNode first = nodes.get(0);
            if (first.getConfig() == null || first.getConfig().isEmpty()) {
                first.setConfig(Map.of("maxIterations", goal.getMaxIterations()));
            }
        }
        return OrchestrationGraph.builder()
                .graphId(UUID.randomUUID().toString())
                .name(goal.getDescription())
                .type(GraphType.GOAL_DECOMPOSED)
                .mode(mode)
                .nodes(nodes)
                .edges(executionGraph.getEdges())
                .variables(Map.of("input", goal.getDescription()))
                .rootGoalId(goal.getGoalId())
                .build();
    }

    /** 按目标特征推荐编排模式（与 DefaultGoalPlanner 一致） */
    private OrchestrationMode recommendMode(AutonomousGoal goal) {
        String type = goal.getType();
        if (type == null) {
            return OrchestrationMode.PIPELINE;
        }
        return switch (type) {
            case "FEATURE", "BUGFIX" -> OrchestrationMode.PIPELINE;
            case "REFACTOR", "OPS" -> OrchestrationMode.SUPERVISOR;
            case "RESEARCH" -> OrchestrationMode.SWARM;
            default -> OrchestrationMode.PIPELINE;
        };
    }

    private String buildPrompt(AutonomousGoal goal) {
        StringBuilder sb = new StringBuilder();
        sb.append("目标：").append(goal.getDescription() != null ? goal.getDescription() : "").append('\n');
        if (goal.getConstraints() != null && !goal.getConstraints().isEmpty()) {
            sb.append("约束：").append(String.join("；", goal.getConstraints())).append('\n');
        }
        sb.append("""
                请分解为不超过 %d 个步骤，输出严格 JSON（可置于 ```json 围栏中）：
                {"steps":[{"id":"s1","description":"步骤描述（自包含，执行者只看到本描述）","dependsOn":[],"agentId":"可选","roleCode":"可选"}]}
                规则：
                1. id 全图唯一；dependsOn 只能引用已声明的 id；
                2. 相互无依赖的步骤将被并行执行，尽量拆分可并行的独立子任务；
                3. description 必须自包含（执行者看不到目标全文与其他步骤产出）。
                """.formatted(maxSteps));
        return sb.toString();
    }

    /**
     * 解析 LLM 输出为计划图：剥围栏 -> 字段校验 -> 未知依赖/自环剔除 -> 步骤数截断。
     * 任何环节失败返回 null（由调用方回退单步）。
     */
    PlanGraph parsePlan(AutonomousGoal goal, String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String json = stripFences(content.trim());
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
        JsonNode stepsNode = root.path("steps");
        if (!stepsNode.isArray() || stepsNode.isEmpty()) {
            return null;
        }
        List<PlanGraph.PlanStep> steps = new ArrayList<>();
        java.util.Set<String> declaredIds = new HashSet<>();
        int count = 0;
        for (JsonNode s : stepsNode) {
            String id = s.path("id").asText("s" + (++count));
            String description = s.path("description").asText("");
            if (description.isBlank()) {
                return null;
            }
            declaredIds.add(id);
            List<String> dependsOn = new ArrayList<>();
            JsonNode deps = s.path("dependsOn");
            if (deps.isArray()) {
                for (JsonNode d : deps) {
                    String dep = d.asText("");
                    if (!dep.isBlank() && !dep.equals(id)) {
                        dependsOn.add(dep);
                    }
                }
            }
            steps.add(PlanGraph.PlanStep.builder()
                    .stepId(id)
                    .description(description)
                    .dependencies(dependsOn)
                    .agentId(s.path("agentId").asText(null))
                    .roleCode(s.path("roleCode").asText(null))
                    .estimatedComplexity(1)
                    .build());
            if (steps.size() >= maxSteps) {
                break;
            }
        }
        if (steps.isEmpty()) {
            return null;
        }
        // 二次清洗：剔除引用了未声明 id 的依赖（防幻觉引用）
        for (PlanGraph.PlanStep step : steps) {
            if (step.getDependencies() != null) {
                step.getDependencies().removeIf(dep -> !declaredIds.contains(dep));
            }
        }
        return PlanGraph.builder()
                .planId("plan-" + UUID.randomUUID())
                .goal(goal.getDescription())
                .createdBy("LlmGoalPlanner")
                .steps(steps)
                .build();
    }

    /** 剥离 ```json 围栏（模型常在 JSON 外包围栏） */
    private String stripFences(String text) {
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1);
            }
            int closingFence = text.lastIndexOf("```");
            if (closingFence >= 0) {
                text = text.substring(0, closingFence);
            }
            return text.trim();
        }
        // 容错：截取首个 { 到最后一个 } 之间的内容
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    /** 单步兜底：与 DefaultGoalPlanner 简单模式一致（保证分解永远可用） */
    private PlanGraph singleStepFallback(AutonomousGoal goal) {
        return PlanGraph.builder()
                .planId("plan-" + UUID.randomUUID())
                .goal(goal.getDescription())
                .createdBy("LlmGoalPlanner")
                .steps(List.of(PlanGraph.PlanStep.builder()
                        .stepId("n1")
                        .description(goal.getDescription())
                        .dependencies(List.of())
                        .estimatedComplexity(1)
                        .build()))
                .build();
    }
}
