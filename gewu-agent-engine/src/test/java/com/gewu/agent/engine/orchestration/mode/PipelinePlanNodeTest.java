package com.gewu.agent.engine.orchestration.mode;

import com.gewu.agent.engine.core.AgentExecutor;
import com.gewu.agent.engine.core.AgentTask;
import com.gewu.agent.engine.core.event.AgentEvent;
import com.gewu.agent.engine.hitl.HitlGateway;
import com.gewu.agent.engine.orchestration.ExecutionControl;
import com.gewu.agent.engine.orchestration.GoalPlanner;
import com.gewu.agent.engine.orchestration.GraphNodeExecutor;
import com.gewu.agent.engine.orchestration.model.GraphEdge;
import com.gewu.agent.engine.orchestration.model.GraphNode;
import com.gewu.agent.engine.orchestration.model.NodeType;
import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import com.gewu.agent.engine.orchestration.model.OrchestrationGraph;
import com.gewu.agent.engine.orchestration.model.PlanGraph;
import com.gewu.agent.engine.tool.ToolExecutor;
import com.gewu.agent.engine.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PipelineModeHandler} PLAN 节点（B-4）、AGENT inputs 模板（B-3）、
 * ROUTER 跳过分支 MERGE 计数修正（B-5）测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Pipeline PLAN 节点与变量模板")
class PipelinePlanNodeTest {

    @Mock
    private AgentExecutor executor;

    @Mock
    private HitlGateway hitlGateway;

    @Mock
    private ToolExecutor toolExecutor;

    @Mock
    private GoalPlanner goalPlanner;

    private ExecutionControl control;
    private final java.util.concurrent.atomic.AtomicInteger agentCalls = new java.util.concurrent.atomic.AtomicInteger();

    @BeforeEach
    void setUp() {
        control = new ExecutionControl();
        agentCalls.set(0);
        lenient().when(executor.executeStream(any())).thenAnswer(inv -> {
            int n = agentCalls.incrementAndGet();
            return Flux.just(AgentEvent.builder().type("content").content("产出" + n).build());
        });
        lenient().when(toolExecutor.execute(anyString(), anyString(), any(), any()))
                .thenReturn(ToolResult.builder().success(true).output("工具产出").build());
    }

    private PipelineModeHandler handler() {
        return new PipelineModeHandler(executor, hitlGateway,
                new GraphNodeExecutor(toolExecutor), control, goalPlanner);
    }

    private GraphNode agent(String id) {
        return GraphNode.builder().nodeId(id).type(NodeType.AGENT).refId("agent-" + id).build();
    }

    private OrchestrationContext context() {
        OrchestrationContext ctx = OrchestrationContext.builder()
                .executionId("exec-" + System.nanoTime()).userId("u1").sessionId("s1").build();
        ctx.putVariable("input", "初始输入");
        return ctx;
    }

    private List<AgentEvent> run(PipelineModeHandler handler, OrchestrationGraph graph,
                                 OrchestrationContext ctx) {
        return handler.run(graph, ctx).collectList().block(java.time.Duration.ofSeconds(10));
    }

    private List<String> completedNodeIds(List<AgentEvent> events) {
        return events.stream()
                .filter(e -> AgentEvent.NODE_COMPLETE.equals(e.getType()) && e.getNodeId() != null)
                .map(AgentEvent::getNodeId)
                .toList();
    }

    // ==================== B-4：PLAN 节点 ====================

    @Test
    @DisplayName("PLAN 节点：输入经规划器分解为并行波次子图内联执行，产出回传父图继续")
    void planNodeExpandsAndExecutesInline() {
        OrchestrationContext ctx = context();
        // 计划：s1/s2 无依赖（并行波）+ s3 依赖两者（串行层）
        when(goalPlanner.plan(any(), any())).thenReturn(PlanGraph.builder()
                .planId("p1").goal("汇总后规划实施").createdBy("test")
                .steps(List.of(
                        PlanGraph.PlanStep.builder().stepId("s1").description("调研A").dependencies(List.of()).build(),
                        PlanGraph.PlanStep.builder().stepId("s2").description("调研B").dependencies(List.of()).build(),
                        PlanGraph.PlanStep.builder().stepId("s3").description("基于两路调研实施").dependencies(List.of("s1", "s2")).build()))
                .build());

        OrchestrationGraph graph = OrchestrationGraph.builder()
                .nodes(List.of(agent("start"),
                        GraphNode.builder().nodeId("plan-1").type(NodeType.PLAN).build(),
                        agent("final")))
                .edges(List.of(
                        GraphEdge.builder().fromNode("start").toNode("plan-1").build(),
                        GraphEdge.builder().fromNode("plan-1").toNode("final").build()))
                .build();

        List<AgentEvent> events = run(handler(), graph, ctx);

        // 图成功完成，子图节点全部执行（含并行波两个分支与串行层）
        assertThat(completedNodeIds(events)).containsSubsequence("start", "s3", "final");
        assertThat(events).anySatisfy(e -> {
            assertThat(e.getType()).isEqualTo(AgentEvent.GRAPH_COMPLETE);
            assertThat(e.getMetadata()).containsEntry("status", "SUCCESS");
        });
        // 计划事件透出（前端 PlanCard 渲染）
        AgentEvent planEvent = events.stream()
                .filter(e -> AgentEvent.PLAN_CREATED.equals(e.getType()))
                .findFirst().orElseThrow();
        assertThat(planEvent.getPlan()).hasSize(3);
        // 最终产出 = PLAN 节点变量 = 子图最后一步产出；final 节点输入即该产出
        assertThat(ctx.getVariable("final")).isEqualTo("产出5");
        assertThat(ctx.getVariable("plan-1")).isEqualTo("产出4");
        // 子图并行波两分支均执行
        assertThat(completedNodeIds(events)).contains("s1", "s2");
    }

    @Test
    @DisplayName("PLAN 节点：GoalPlanner 未配置时图失败并给出明确原因")
    void planNodeWithoutPlannerFails() {
        OrchestrationContext ctx = context();
        PipelineModeHandler bare = new PipelineModeHandler(executor, hitlGateway,
                new GraphNodeExecutor(toolExecutor), control, null);
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .nodes(List.of(GraphNode.builder().nodeId("plan-1").type(NodeType.PLAN).build()))
                .edges(List.of())
                .build();

        List<AgentEvent> events = run(bare, graph, ctx);

        assertThat(events).anySatisfy(e -> {
            assertThat(e.getType()).isEqualTo(AgentEvent.GRAPH_COMPLETE);
            assertThat(e.getMetadata()).containsEntry("status", "FAILED");
        });
    }

    // ==================== B-3：AGENT 节点 inputs 模板 ====================

    @Test
    @DisplayName("inputs 模板：message 键覆盖前驱输出，其余键以参考段追加（引用任意上游变量）")
    void agentNodeInputsTemplate() {
        OrchestrationContext ctx = context();
        ctx.putVariable("research", "调研结论X");
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .nodes(List.of(agent("a1"),
                        GraphNode.builder().nodeId("a2").type(NodeType.AGENT).refId("agent-a2")
                                .inputs(Map.of(
                                        "message", "${a1}",
                                        "researchRef", "上游调研：${research}；原始输入：${input}")).build()))
                .edges(List.of(GraphEdge.builder().fromNode("a1").toNode("a2").build()))
                .build();

        run(handler(), graph, ctx);

        ArgumentCaptor<AgentTask> captor = ArgumentCaptor.forClass(AgentTask.class);
        verify(executor, org.mockito.Mockito.times(2)).executeStream(captor.capture());
        AgentTask a2Task = captor.getAllValues().get(1);
        assertThat(a2Task.getMessage())
                .isEqualTo("产出1\n\n## 参考：researchRef\n上游调研：调研结论X；原始输入：初始输入");
    }

    @Test
    @DisplayName("模型回退：refId 为空时经图变量 modelProvider/modelName 解析（动态图免 Agent 预置）")
    void agentNodeModelFallbackFromVariables() {
        OrchestrationContext ctx = context();
        ctx.putVariable("modelProvider", "prov-x");
        ctx.putVariable("modelName", "model-x");
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .nodes(List.of(GraphNode.builder().nodeId("free").type(NodeType.AGENT).build()))
                .edges(List.of())
                .build();

        run(handler(), graph, ctx);

        ArgumentCaptor<AgentTask> captor = ArgumentCaptor.forClass(AgentTask.class);
        verify(executor).executeStream(captor.capture());
        assertThat(captor.getValue().getAgentId()).isNull();
        assertThat(captor.getValue().getModelProvider()).isEqualTo("prov-x");
        assertThat(captor.getValue().getModelName()).isEqualTo("model-x");
    }

    // ==================== B-5：ROUTER 跳过分支的 MERGE 计数修正 ====================

    @Test
    @DisplayName("ROUTER+MERGE：被跳过分支的 MERGE 期待数被修正，汇聚照常开闸（不再静默丢分支）")
    void routerSkippedBranchAdjustsMergeExpect() {
        OrchestrationContext ctx = context();
        ctx.putVariable("x", "a");
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .nodes(List.of(
                        GraphNode.builder().nodeId("router").type(NodeType.ROUTER).build(),
                        agent("a1"), agent("a2"),
                        GraphNode.builder().nodeId("merge").type(NodeType.MERGE).build(),
                        agent("out")))
                .edges(List.of(
                        GraphEdge.builder().fromNode("router").toNode("a1").condition("var:x == 'a'").build(),
                        GraphEdge.builder().fromNode("router").toNode("a2").condition("else").build(),
                        GraphEdge.builder().fromNode("a1").toNode("merge").build(),
                        GraphEdge.builder().fromNode("a2").toNode("merge").build(),
                        GraphEdge.builder().fromNode("merge").toNode("out").build()))
                .build();

        List<AgentEvent> events = run(handler(), graph, ctx);

        // 修复前：mergeExpect=2 永不到齐，out 不执行、图以部分输出 SUCCESS 收尾
        // 修复后：跳过分支的入边被扣除，merge 到齐后 out 正常执行
        assertThat(completedNodeIds(events)).containsSubsequence("a1", "merge", "out");
        assertThat(events).anySatisfy(e -> {
            assertThat(e.getType()).isEqualTo(AgentEvent.GRAPH_COMPLETE);
            assertThat(e.getMetadata()).containsEntry("status", "SUCCESS");
        });
        assertThat(ctx.getVariable("out")).isEqualTo("产出2");
    }
}
