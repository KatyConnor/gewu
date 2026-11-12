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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * AE-衍生-1 回归（AE-P0-1 实施期间发现）：MERGE 为图终节点时，
 * 旧实现 continueTo(firstOutgoing=null)→nodeById(null) NPE 且被 LambdaSubscriber
 * 完成回调以 onErrorDropped 静默吞掉——编排流既不 complete 也不 error，执行悬挂、
 * 账本停留 RUNNING。修复后终节点语义 = 路径结束 + 正常收尾。
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-11-13
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MERGE 终结图收尾（AE-衍生-1）")
class PipelineMergeTerminalTest {

    @Mock
    private AgentExecutor executor;

    @Mock
    private HitlGateway hitlGateway;

    @Mock
    private ToolExecutor toolExecutor;

    @Mock
    private GoalPlanner goalPlanner;

    private ExecutionControl control;

    @BeforeEach
    void setUp() {
        control = new ExecutionControl();
        lenient().when(toolExecutor.execute(any(), any(), any(), any()))
                .thenReturn(ToolResult.builder().success(true).output("工具产出").build());
        lenient().when(executor.executeStream(any()))
                .thenReturn(Flux.just(AgentEvent.builder().type("content").content("产出").build()));
    }

    private PipelineModeHandler handler() {
        return new PipelineModeHandler(executor, hitlGateway,
                new GraphNodeExecutor(toolExecutor), control, goalPlanner);
    }

    private OrchestrationContext context() {
        return OrchestrationContext.builder()
                .executionId("exec-merge-1").userId("u1").sessionId("s1").build();
    }

    @Test
    @DisplayName("根图以 MERGE 终结：单一 SUCCESS 收尾（修复前 NPE 静默吞掉→流悬挂）")
    void mergeTerminalGraphCompletes() {
        OrchestrationContext ctx = context();
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .nodes(List.of(
                        GraphNode.builder().nodeId("a1").type(NodeType.AGENT).refId("agent-a1").build(),
                        GraphNode.builder().nodeId("merge").type(NodeType.MERGE).build()))
                .edges(List.of(GraphEdge.builder().fromNode("a1").toNode("merge").build()))
                .build();

        List<AgentEvent> events = handler().run(graph, ctx)
                .collectList().block(java.time.Duration.ofSeconds(10));

        List<AgentEvent> completes = events.stream()
                .filter(e -> AgentEvent.GRAPH_COMPLETE.equals(e.getType())).toList();
        assertThat(completes).hasSize(1);
        assertThat(completes.get(0).getMetadata()).containsEntry("status", "SUCCESS");
    }

    @Test
    @DisplayName("PLAN 单波次计划（子图 MERGE 终结）：整图正常完成（AE-P0-1 红基线期间的原始悬挂场景）")
    void planSingleWaveCompletes() {
        OrchestrationContext ctx = context();
        when(goalPlanner.plan(any(), any())).thenReturn(PlanGraph.builder()
                .planId("p1").goal("单波次计划").createdBy("t")
                .steps(List.of(
                        PlanGraph.PlanStep.builder().stepId("s1").description("调研").dependencies(List.of()).build(),
                        PlanGraph.PlanStep.builder().stepId("s2").description("实施").dependencies(List.of()).build()))
                .build());

        List<AgentEvent> events = handler().run(
                        OrchestrationGraph.builder()
                                .nodes(List.of(GraphNode.builder().nodeId("plan-1").type(NodeType.PLAN).build()))
                                .edges(List.of())
                                .build(),
                        ctx)
                .collectList().block(java.time.Duration.ofSeconds(10));

        List<AgentEvent> completes = events.stream()
                .filter(e -> AgentEvent.GRAPH_COMPLETE.equals(e.getType())).toList();
        assertThat(completes).hasSize(1);
        assertThat(completes.get(0).getMetadata()).containsEntry("status", "SUCCESS");
        assertThat(completedIds(events)).contains("s1", "s2");
    }

    private List<String> completedIds(List<AgentEvent> events) {
        return events.stream()
                .filter(e -> AgentEvent.NODE_COMPLETE.equals(e.getType()) && e.getNodeId() != null)
                .map(AgentEvent::getNodeId)
                .toList();
    }
}
