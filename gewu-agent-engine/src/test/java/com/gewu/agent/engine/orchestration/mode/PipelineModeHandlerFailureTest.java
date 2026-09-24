package com.gewu.agent.engine.orchestration.mode;

import com.gewu.agent.engine.core.AgentExecutor;
import com.gewu.agent.engine.core.AgentTask;
import com.gewu.agent.engine.core.event.AgentEvent;
import com.gewu.agent.engine.hitl.HitlGateway;
import com.gewu.agent.engine.orchestration.model.GraphEdge;
import com.gewu.agent.engine.orchestration.model.GraphNode;
import com.gewu.agent.engine.orchestration.model.NodeType;
import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import com.gewu.agent.engine.orchestration.model.OrchestrationGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * 失败传播语义测试（docs/design/47 问题一）：
 * AGENT 节点失败不得伪装成 node_complete，fail-fast 与 best-effort 两种语义。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Pipeline 失败传播")
class PipelineModeHandlerFailureTest {

    @Mock
    private AgentExecutor executor;

    @Mock
    private HitlGateway hitlGateway;

    private PipelineModeHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PipelineModeHandler(executor, hitlGateway);
    }

    private OrchestrationContext context() {
        OrchestrationContext ctx = OrchestrationContext.builder()
                .executionId("exec-fail").sessionId("sess-1").userId("user-1").build();
        ctx.putVariable("input", "任务");
        return ctx;
    }

    private OrchestrationGraph chainGraph() {
        return OrchestrationGraph.builder()
                .mode(com.gewu.agent.engine.orchestration.model.OrchestrationMode.PIPELINE)
                .nodes(List.of(
                        GraphNode.builder().nodeId("n1").type(NodeType.AGENT).roleCode("DEVELOPER").build(),
                        GraphNode.builder().nodeId("n2").type(NodeType.AGENT).roleCode("TEST_ENGINEER").build()))
                .edges(List.of(GraphEdge.builder().fromNode("n1").toNode("n2").build()))
                .build();
    }

    private List<AgentEvent> run(OrchestrationGraph graph) {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        handler.run(graph, context()).collectList().block().forEach(events::add);
        return events;
    }

    private Flux<AgentEvent> failingStream() {
        return Flux.just(AgentEvent.builder()
                .type(AgentEvent.ERROR).errorMessage("zhipu API 认证失败或请求错误 (HTTP 429)").build());
    }

    @Test
    @DisplayName("fail-fast：AGENT 失败整图 FAILED，无虚假 node_complete、不推进后继")
    void agentFailureFailsGraph() {
        whenExecutorFails();
        List<AgentEvent> events = run(chainGraph());

        assertThat(types(events)).containsSubsequence("graph_start", "node_start", "error", "graph_complete");
        assertThat(events).noneMatch(e -> "node_complete".equals(e.getType()));
        AgentEvent complete = events.stream()
                .filter(e -> "graph_complete".equals(e.getType())).findFirst().orElseThrow();
        assertThat(complete.getMetadata()).containsEntry("status", "FAILED");
        assertThat(types(events)).doesNotContain("node_start_n2_marker");
        // 后继 n2 未被执行：node_start 事件仅 1 次（n1）
        assertThat(events.stream().filter(e -> "node_start".equals(e.getType())).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("best-effort：continueOnFailure=true 时失败节点标红但链路推进，整图终态仍 FAILED")
    void agentFailureContinueMode() {
        whenExecutorFails();
        OrchestrationContext ctx = context();
        ctx.putVariable("continueOnFailure", true);
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        handler.run(chainGraph(), ctx).collectList().block().forEach(events::add);

        assertThat(types(events)).containsSubsequence("graph_start", "node_start", "error", "graph_complete");
        // n2 被级联跳过并标记失败（error 事件），无任何 node_complete
        assertThat(events.stream().filter(e -> "error".equals(e.getType())).count()).isEqualTo(2);
        assertThat(events).noneMatch(e -> "node_complete".equals(e.getType()));
        AgentEvent complete = events.stream()
                .filter(e -> "graph_complete".equals(e.getType())).findFirst().orElseThrow();
        assertThat(complete.getMetadata()).containsEntry("status", "FAILED");
    }

    @Test
    @DisplayName("正常路径回归：无 error 事件时行为不变")
    void normalPathUnchanged() {
        lenient().when(executor.executeStream(any(AgentTask.class)))
                .thenReturn(Flux.just(
                        AgentEvent.builder().type("content").content("产出").build(),
                        AgentEvent.builder().type("done").build()));
        List<AgentEvent> events = run(chainGraph());

        assertThat(types(events)).containsSubsequence(
                "graph_start", "node_start", "node_complete", "node_start", "node_complete", "graph_complete");
        AgentEvent complete = events.stream()
                .filter(e -> "graph_complete".equals(e.getType())).findFirst().orElseThrow();
        assertThat(complete.getMetadata()).containsEntry("status", "SUCCESS");
    }

    private void whenExecutorFails() {
        lenient().when(executor.executeStream(any(AgentTask.class))).thenAnswer(inv -> failingStream());
    }

    private List<String> types(List<AgentEvent> events) {
        return events.stream().map(AgentEvent::getType).toList();
    }
}
