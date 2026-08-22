package com.gewu.agent.engine.orchestration.mode;

import com.gewu.agent.engine.core.AgentExecutor;
import com.gewu.agent.engine.core.AgentTask;
import com.gewu.agent.engine.core.event.AgentEvent;
import com.gewu.agent.engine.hitl.HitlGateway;
import com.gewu.agent.engine.hitl.HumanDecision;
import com.gewu.agent.engine.orchestration.model.GraphEdge;
import com.gewu.agent.engine.orchestration.model.GraphNode;
import com.gewu.agent.engine.orchestration.model.NodeType;
import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import com.gewu.agent.engine.orchestration.model.OrchestrationGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PipelineModeHandler} 串行流水线与 HITL 审批节点测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Pipeline 模式处理器")
class PipelineModeHandlerTest {

    @Mock
    private AgentExecutor executor;

    @Mock
    private HitlGateway hitlGateway;

    private PipelineModeHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PipelineModeHandler(executor, hitlGateway);
        // 默认 LLM 节点返回固定内容
        lenient().when(executor.executeStream(any()))
                .thenAnswer(inv -> Flux.just(
                        AgentEvent.builder().type("content").content("产出-" + stageCounter.incrementAndGet()).build(),
                        AgentEvent.builder().type("done").build()));
        lenient().when(hitlGateway.requestApproval(any()))
                .thenReturn(reactor.core.publisher.Mono.just(HumanDecision.builder()
                        .decision("APPROVED").value("同意").operatorId("admin").build()));
    }

    private static final java.util.concurrent.atomic.AtomicInteger stageCounter =
            new java.util.concurrent.atomic.AtomicInteger();

    private GraphNode agentNode(String id, String refId) {
        return GraphNode.builder().nodeId(id).type(NodeType.AGENT)
                .refId(refId).roleCode("DEVELOPER").build();
    }

    private OrchestrationContext context() {
        OrchestrationContext ctx = OrchestrationContext.builder()
                .executionId("exec-1").sessionId("sess-1").userId("user-1").build();
        ctx.putVariable("input", "原始需求");
        return ctx;
    }

    private List<AgentEvent> run(OrchestrationGraph graph) {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        handler.run(graph, context()).collectList().block().forEach(events::add);
        return events;
    }

    @Test
    @DisplayName("空节点图直接 SUCCESS 完成")
    void emptyGraphCompletes() {
        List<AgentEvent> events = run(OrchestrationGraph.builder()
                .nodes(List.of()).edges(List.of()).build());

        assertThat(events).extracting(AgentEvent::getType)
                .containsExactly("graph_start", "graph_complete");
        assertThat(events.get(1).getMetadata()).containsEntry("status", "SUCCESS");
    }

    @Test
    @DisplayName("两节点串行：按边拓扑排序执行且产出逐节点传递")
    void serialPipelinePassesOutput() {
        // 节点列表故意乱序（B 在前），边定义 A -> B，执行应按 A、B 顺序
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .nodes(List.of(agentNode("node-b", "agent-b"), agentNode("node-a", "agent-a")))
                .edges(List.of(GraphEdge.builder().fromNode("node-a").toNode("node-b").build()))
                .build();

        List<AgentEvent> events = run(graph);

        // 拓扑排序生效：node-a 先执行
        List<String> nodeStarts = events.stream()
                .filter(e -> "node_start".equals(e.getType()))
                .map(AgentEvent::getNodeId)
                .toList();
        assertThat(nodeStarts).containsExactly("node-a", "node-b");

        // 两个节点都调用了执行器
        verify(executor, times(2)).executeStream(any());

        // 第二个节点的输入是第一个节点的产出
        ArgumentCaptor<AgentTask> captor = ArgumentCaptor.forClass(AgentTask.class);
        verify(executor, times(2)).executeStream(captor.capture());
        assertThat(captor.getAllValues().get(0).getMessage()).isEqualTo("原始需求");
        assertThat(captor.getAllValues().get(1).getMessage()).contains("产出-");

        // 图完成携带最终产出
        AgentEvent complete = events.stream()
                .filter(e -> "graph_complete".equals(e.getType())).findFirst().orElseThrow();
        assertThat(complete.getMetadata()).containsEntry("status", "SUCCESS");
        assertThat(complete.getMetadata().get("output").toString()).contains("产出-");
    }

    @Test
    @DisplayName("HUMAN 节点批准：审批意见并入产出后继续执行")
    void humanNodeApproved() {
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .nodes(List.of(
                        agentNode("node-a", "agent-a"),
                        GraphNode.builder().nodeId("review").type(NodeType.HUMAN)
                                .roleCode("REVIEWER").build(),
                        agentNode("node-b", "agent-b")))
                .edges(List.of(
                        GraphEdge.builder().fromNode("node-a").toNode("review").build(),
                        GraphEdge.builder().fromNode("review").toNode("node-b").build()))
                .build();

        List<AgentEvent> events = run(graph);
        List<String> types = events.stream().map(AgentEvent::getType).toList();

        assertThat(types).contains("approval_required", "approval_result");
        // 审批通过后继续执行 node-b
        assertThat(types).containsSequence("approval_required", "approval_result", "node_complete", "node_start");
        AgentEvent complete = events.stream()
                .filter(e -> "graph_complete".equals(e.getType())).findFirst().orElseThrow();
        assertThat(complete.getMetadata()).containsEntry("status", "SUCCESS");

        // 审批意见并入产出，node-b 收到含审批意见的输入
        ArgumentCaptor<AgentTask> captor = ArgumentCaptor.forClass(AgentTask.class);
        verify(executor, times(2)).executeStream(captor.capture());
        assertThat(captor.getAllValues().get(1).getMessage()).contains("[人工审批意见]").contains("同意");
    }

    @Test
    @DisplayName("HUMAN 节点驳回：图 FAILED 终止且不再执行后续节点")
    void humanNodeRejected() {
        when(hitlGateway.requestApproval(any()))
                .thenReturn(reactor.core.publisher.Mono.just(
                        HumanDecision.builder().decision("REJECTED").value("不合格").build()));
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .nodes(List.of(
                        GraphNode.builder().nodeId("review").type(NodeType.HUMAN)
                                .roleCode("REVIEWER").build(),
                        agentNode("node-b", "agent-b")))
                .edges(List.of(GraphEdge.builder().fromNode("review").toNode("node-b").build()))
                .build();

        List<AgentEvent> events = run(graph);

        AgentEvent complete = events.stream()
                .filter(e -> "graph_complete".equals(e.getType())).findFirst().orElseThrow();
        assertThat(complete.getMetadata()).containsEntry("status", "FAILED");
        assertThat(complete.getMetadata().get("reason").toString()).contains("驳回");
        // 后续 Agent 节点未执行
        verify(executor, times(0)).executeStream(any());
    }

    @Test
    @DisplayName("节点 config 可覆盖审批超时时间")
    void humanNodeTimeoutOverride() {
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .nodes(List.of(GraphNode.builder().nodeId("review").type(NodeType.HUMAN)
                        .roleCode("REVIEWER")
                        .config(java.util.Map.of("timeoutSeconds", 60)).build()))
                .edges(List.of())
                .build();

        List<AgentEvent> events = run(graph);

        AgentEvent approval = events.stream()
                .filter(e -> "approval_required".equals(e.getType())).findFirst().orElseThrow();
        assertThat(approval.getMetadata()).containsEntry("timeoutSeconds", 60);
    }

    @Test
    @DisplayName("节点执行异常传播为流错误")
    void nodeErrorPropagates() {
        when(executor.executeStream(any()))
                .thenReturn(Flux.error(new RuntimeException("节点执行崩溃")));
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .nodes(List.of(agentNode("node-a", "agent-a")))
                .edges(List.of())
                .build();

        List<AgentEvent> events = handler.run(graph, context())
                .onErrorResume(e -> Flux.just(AgentEvent.builder()
                        .type("error").errorMessage(e.getMessage()).build()))
                .collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).anyMatch(e -> "error".equals(e.getType())
                && e.getErrorMessage().contains("节点执行崩溃"));
    }
}
