package com.gewu.agent.engine.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.orchestration.model.AutonomousGoal;
import com.gewu.agent.engine.orchestration.model.ExecutionGraph;
import com.gewu.agent.engine.orchestration.model.GraphNode;
import com.gewu.agent.engine.orchestration.model.NodeType;
import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import com.gewu.agent.engine.orchestration.model.PlanGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 路线 B 单元测试：LLM 规划器解析（B-1）与计划图并行波次映射（B-2）。
 */
@DisplayName("LlmGoalPlanner 解析与计划图波次映射")
class LlmGoalPlannerAndWaveMappingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private LlmGoalPlanner planner(int maxSteps) {
        // 解析逻辑不触网：registry 传 null 即可
        return new LlmGoalPlanner(null, objectMapper, "test", "test-model", maxSteps);
    }

    private AutonomousGoal goal(String description) {
        return AutonomousGoal.builder().goalId("g1").description(description).type("FEATURE").build();
    }

    // ==================== B-1：LLM 规划解析 ====================

    @Test
    @DisplayName("解析合法 JSON：步骤/依赖/agentId 齐全")
    void parseValidPlan() {
        PlanGraph plan = planner(8).parsePlan(goal("做X"),
                "{\"steps\":[{\"id\":\"s1\",\"description\":\"调研\",\"dependsOn\":[],\"agentId\":\"researcher\"},"
                        + "{\"id\":\"s2\",\"description\":\"实施\",\"dependsOn\":[\"s1\"]}]}");
        assertThat(plan).isNotNull();
        assertThat(plan.getSteps()).hasSize(2);
        assertThat(plan.getSteps().get(0).getAgentId()).isEqualTo("researcher");
        assertThat(plan.getSteps().get(1).getDependencies()).containsExactly("s1");
    }

    @Test
    @DisplayName("解析 ```json 围栏输出：剥围栏后正常解析")
    void parseFencedPlan() {
        PlanGraph plan = planner(8).parsePlan(goal("做X"), """
                ```json
                {"steps":[{"id":"s1","description":"第一步","dependsOn":[]}]}
                ```
                """);
        assertThat(plan).isNotNull();
        assertThat(plan.getSteps()).hasSize(1);
    }

    @Test
    @DisplayName("解析失败回退：非法 JSON / 空 steps / 空描述 返回 null（调用方单步兜底）")
    void parseInvalidReturnsNull() {
        LlmGoalPlanner p = planner(8);
        assertThat(p.parsePlan(goal("做X"), "这不是 JSON")).isNull();
        assertThat(p.parsePlan(goal("做X"), "{\"steps\":[]}")).isNull();
        assertThat(p.parsePlan(goal("做X"), "{\"steps\":[{\"id\":\"s1\",\"description\":\"\"}]}")).isNull();
        assertThat(p.parsePlan(goal("做X"), null)).isNull();
    }

    @Test
    @DisplayName("防御性清洗：未知依赖与自环剔除、步骤数按 maxSteps 截断")
    void parseDefensiveCleaning() {
        LlmGoalPlanner p = planner(2);
        PlanGraph plan = p.parsePlan(goal("做X"), """
                {"steps":[
                  {"id":"s1","description":"一","dependsOn":["ghost","s1"]},
                  {"id":"s2","description":"二","dependsOn":["s1"]},
                  {"id":"s3","description":"三（应被截断）","dependsOn":[]}]}
                """);
        assertThat(plan).isNotNull();
        assertThat(plan.getSteps()).hasSize(2); // maxSteps 截断
        assertThat(plan.getSteps().get(0).getDependencies()).isEmpty(); // ghost + 自环剔除
    }

    // ==================== B-2：波次映射 ====================

    @Test
    @DisplayName("波次映射：无依赖步骤生成 PARALLEL/MERGE 并行段，串行层直接链接")
    void waveMappingProducesParallelSegment() {
        PlanGraph plan = PlanGraph.builder()
                .planId("p1").goal("做X").createdBy("test")
                .steps(List.of(
                        PlanGraph.PlanStep.builder().stepId("s1").description("调研").dependencies(List.of()).build(),
                        PlanGraph.PlanStep.builder().stepId("s2").description("实施A").dependencies(List.of("s1")).build(),
                        PlanGraph.PlanStep.builder().stepId("s3").description("实施B").dependencies(List.of("s1")).build()))
                .build();

        ExecutionGraph graph = ExecutionGraph.fromPlanGraph(plan, null);

        Map<String, NodeType> typeById = new java.util.LinkedHashMap<>();
        for (GraphNode n : graph.getNodes()) {
            typeById.put(n.getNodeId(), n.getType());
        }
        // L1=[s1] 串行；L2=[s2,s3] 并行段
        assertThat(typeById).containsKeys("s1", "s2", "s3", "__parallel_L2", "__merge_L2");
        assertThat(typeById.get("__parallel_L2")).isEqualTo(NodeType.PARALLEL);
        assertThat(typeById.get("__merge_L2")).isEqualTo(NodeType.MERGE);
        assertThat(typeById.get("s1")).isEqualTo(NodeType.AGENT);

        List<String> edgeKeys = graph.getEdges().stream()
                .map(e -> e.getFromNode() + "->" + e.getToNode())
                .toList();
        assertThat(edgeKeys).containsExactlyInAnyOrder(
                "s1->__parallel_L2",
                "__parallel_L2->s2", "__parallel_L2->s3",
                "s2->__merge_L2", "s3->__merge_L2");
    }

    @Test
    @DisplayName("波次映射：全串行计划不插入结构节点（向后兼容）")
    void serialPlanStaysLinear() {
        PlanGraph plan = PlanGraph.builder()
                .planId("p1").goal("做X").createdBy("test")
                .steps(List.of(
                        PlanGraph.PlanStep.builder().stepId("s1").description("一").dependencies(List.of()).build(),
                        PlanGraph.PlanStep.builder().stepId("s2").description("二").dependencies(List.of("s1")).build()))
                .build();

        ExecutionGraph graph = ExecutionGraph.fromPlanGraph(plan, null);

        assertThat(graph.getNodes()).allSatisfy(n -> assertThat(n.getType()).isEqualTo(NodeType.AGENT));
        assertThat(graph.getEdges()).hasSize(1)
                .first().satisfies(e -> {
                    assertThat(e.getFromNode()).isEqualTo("s1");
                    assertThat(e.getToNode()).isEqualTo("s2");
                });
    }

    @Test
    @DisplayName("波次映射：单步骤计划保持 DefaultGoalPlanner 兼容形态")
    void singleStepPlanUnchanged() {
        PlanGraph plan = PlanGraph.builder()
                .planId("p1").goal("做X").createdBy("test")
                .steps(List.of(PlanGraph.PlanStep.builder().stepId("n1").description("做X").dependencies(List.of()).build()))
                .build();
        ExecutionGraph graph = ExecutionGraph.fromPlanGraph(plan, null);
        assertThat(graph.getNodes()).hasSize(1);
        assertThat(graph.getEdges()).isEmpty();
        // OrchestrationContext 仅用于签名一致（plan() 签名要求），此处不需要变量
        OrchestrationContext ctx = OrchestrationContext.builder().executionId("e").build();
        assertThat(ctx.getVariable("input")).isNull();
    }
}
