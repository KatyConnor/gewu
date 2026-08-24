package com.gewu.agent.engine.orchestration.mode;

import com.gewu.agent.engine.core.AgentExecutor;
import com.gewu.agent.engine.core.AgentTask;
import com.gewu.agent.engine.core.event.AgentEvent;
import com.gewu.agent.engine.hitl.HitlGateway;
import com.gewu.agent.engine.hitl.HumanDecision;
import com.gewu.agent.engine.orchestration.ExecutionControl;
import com.gewu.agent.engine.orchestration.GraphNodeExecutor;
import com.gewu.agent.engine.orchestration.model.GraphEdge;
import com.gewu.agent.engine.orchestration.model.GraphNode;
import com.gewu.agent.engine.orchestration.model.NodeType;
import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import com.gewu.agent.engine.orchestration.model.OrchestrationGraph;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PipelineModeHandler} 结构节点与执行控制测试（T3.1）。
 * <p>覆盖 TOOL/ROUTER/PARALLEL/MERGE 四类节点与协作式暂停断点续跑/取消。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Pipeline 结构节点与执行控制")
class PipelineAdvancedNodesTest {

    @Mock
    private AgentExecutor executor;

    @Mock
    private HitlGateway hitlGateway;

    @Mock
    private ToolExecutor toolExecutor;

    private ExecutionControl control;
    private final AtomicInteger agentCalls = new AtomicInteger();
    /** 当前用例的执行 ID（context() 时赋值，暂停/取消信号定向使用） */
    private volatile String executionId;

    @BeforeEach
    void setUp() {
        control = new ExecutionControl();
        agentCalls.set(0);
        // 每个 AGENT 节点返回可区分的内容（产出N）
        lenient().when(executor.executeStream(any())).thenAnswer(inv -> {
            int n = agentCalls.incrementAndGet();
            return Flux.just(AgentEvent.builder().type("content").content("产出" + n).build());
        });
        lenient().when(hitlGateway.requestApproval(any()))
                .thenReturn(Mono.just(HumanDecision.builder().decision("APPROVED").build()));
        lenient().when(toolExecutor.execute(anyString(), anyString(), any(), any()))
                .thenReturn(ToolResult.builder().success(true).output("工具产出").build());
    }

    private PipelineModeHandler handler() {
        return new PipelineModeHandler(executor, hitlGateway,
                new GraphNodeExecutor(toolExecutor), control);
    }

    private GraphNode agent(String id) {
        return GraphNode.builder().nodeId(id).type(NodeType.AGENT).refId("agent-" + id).build();
    }

    private OrchestrationContext context() {
        executionId = "exec-" + System.nanoTime();
        OrchestrationContext ctx = OrchestrationContext.builder()
                .executionId(executionId).userId("u1").sessionId("s1").build();
        ctx.putVariable("input", "初始输入");
        return ctx;
    }

    private List<AgentEvent> run(PipelineModeHandler handler, OrchestrationGraph graph,
                                 OrchestrationContext ctx) {
        return handler.run(graph, ctx).collectList().block();
    }

    @Test
    @DisplayName("TOOL 节点：模板渲染执行工具且产出传递给后继 AGENT 节点")
    void toolNodeInPipeline() {
        OrchestrationContext ctx = context();
        ctx.putVariable("city", "北京");
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .nodes(List.of(
                        GraphNode.builder().nodeId("tool-1").type(NodeType.TOOL)
                                .config(Map.of("toolName", "weather",
                                        "arguments", "{\"city\":\"${city}\"}")).build(),
                        agent("after-tool")))
                .edges(List.of(GraphEdge.builder().fromNode("tool-1").toNode("after-tool").build()))
                .build();

        List<AgentEvent> events = run(handler(), graph, ctx);

        List<String> types = events.stream().map(AgentEvent::getType).toList();
        assertThat(types).contains("node_start", "node_complete", "graph_complete");
        // 后继 AGENT 节点收到工具产出作为输入
        ArgumentCaptor<AgentTask> captor = ArgumentCaptor.forClass(AgentTask.class);
        verify(executor).executeStream(captor.capture());
        assertThat(captor.getValue().getMessage()).isEqualTo("工具产出");
        // 工具产出写入上下文变量
        assertThat(ctx.getVariable("tool-1")).isEqualTo("工具产出");
        AgentEvent complete = events.stream()
                .filter(e -> "graph_complete".equals(e.getType())).findFirst().orElseThrow();
        assertThat(complete.getMetadata()).containsEntry("status", "SUCCESS");
    }

    @Test
    @DisplayName("ROUTER 节点：条件命中路由到指定分支")
    void routerSelectsMatchingBranch() {
        OrchestrationContext ctx = context();
        ctx.putVariable("quality", "high");
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .nodes(List.of(
                        GraphNode.builder().nodeId("router").type(NodeType.ROUTER).build(),
                        agent("path-high"), agent("path-low")))
                .edges(List.of(
                        GraphEdge.builder().fromNode("router").toNode("path-high")
                                .condition("var:quality == 'high'").build(),
                        GraphEdge.builder().fromNode("router").toNode("path-low")
                                .condition("var:quality == 'low'").build()))
                .build();

        List<AgentEvent> events = run(handler(), graph, ctx);

        // 仅命中分支执行（另一分支无 node_start）
        assertThat(events).noneMatch(e -> "path-low".equals(e.getNodeId()));
        assertThat(events).anyMatch(e -> "path-high".equals(e.getNodeId())
                && "node_start".equals(e.getType()));
    }

    @Test
    @DisplayName("PARALLEL 扇出 + MERGE 汇聚：两分支并行执行、产出合并传给 final")
    void parallelFanOutMergeFanIn() {
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .nodes(List.of(
                        agent("start"),
                        GraphNode.builder().nodeId("fork").type(NodeType.PARALLEL).build(),
                        agent("branch-a"), agent("branch-b"),
                        GraphNode.builder().nodeId("merge").type(NodeType.MERGE).build(),
                        agent("final")))
                .edges(List.of(
                        GraphEdge.builder().fromNode("start").toNode("fork").build(),
                        GraphEdge.builder().fromNode("fork").toNode("branch-a").build(),
                        GraphEdge.builder().fromNode("fork").toNode("branch-b").build(),
                        GraphEdge.builder().fromNode("branch-a").toNode("merge").build(),
                        GraphEdge.builder().fromNode("branch-b").toNode("merge").build(),
                        GraphEdge.builder().fromNode("merge").toNode("final").build()))
                .build();

        List<AgentEvent> events = run(handler(), graph, context());

        // 两个分支都有 node_start；final 节点执行且其输入包含两分支产出
        assertThat(events).anyMatch(e -> "branch-a".equals(e.getNodeId()) && "node_start".equals(e.getType()));
        assertThat(events).anyMatch(e -> "branch-b".equals(e.getNodeId()) && "node_start".equals(e.getType()));

        ArgumentCaptor<AgentTask> captor = ArgumentCaptor.forClass(AgentTask.class);
        verify(executor, times(4)).executeStream(captor.capture());
        // final 节点（第 4 次调用）输入包含两个分支的产出（默认换行拼接合并）
        String finalInput = captor.getAllValues().get(3).getMessage();
        assertThat(finalInput).contains("产出").contains("\n");

        AgentEvent complete = events.stream()
                .filter(e -> "graph_complete".equals(e.getType())).findFirst().orElseThrow();
        assertThat(complete.getMetadata()).containsEntry("status", "SUCCESS");
    }

    @Test
    @DisplayName("MERGE json_merge 策略：分支 JSON 产出按字段合并")
    void mergeJsonStrategy() {
        when(toolExecutor.execute(org.mockito.ArgumentMatchers.eq("left"), anyString(), any(), any()))
                .thenReturn(ToolResult.builder().success(true).output("{\"a\":1}").build());
        when(toolExecutor.execute(org.mockito.ArgumentMatchers.eq("right"), anyString(), any(), any()))
                .thenReturn(ToolResult.builder().success(true).output("{\"b\":2}").build());
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .nodes(List.of(
                        GraphNode.builder().nodeId("fork").type(NodeType.PARALLEL).build(),
                        GraphNode.builder().nodeId("t-left").type(NodeType.TOOL)
                                .config(Map.of("toolName", "left")).build(),
                        GraphNode.builder().nodeId("t-right").type(NodeType.TOOL)
                                .config(Map.of("toolName", "right")).build(),
                        GraphNode.builder().nodeId("merge").type(NodeType.MERGE)
                                .config(Map.of("strategy", "json_merge")).build(),
                        agent("final")))
                .edges(List.of(
                        GraphEdge.builder().fromNode("fork").toNode("t-left").build(),
                        GraphEdge.builder().fromNode("fork").toNode("t-right").build(),
                        GraphEdge.builder().fromNode("t-left").toNode("merge").build(),
                        GraphEdge.builder().fromNode("t-right").toNode("merge").build(),
                        GraphEdge.builder().fromNode("merge").toNode("final").build()))
                .build();

        List<AgentEvent> events = run(handler(), graph, context());
        assertThat(events).isNotNull();

        ArgumentCaptor<AgentTask> captor = ArgumentCaptor.forClass(AgentTask.class);
        verify(executor).executeStream(captor.capture());
        String finalInput = captor.getValue().getMessage();
        assertThat(finalInput).contains("\"a\":1").contains("\"b\":2");
    }

    @Test
    @DisplayName("暂停：信号在节点间生效，检查点保存且流以 PAUSED 收尾")
    void pauseSavesCheckpoint() {
        // 第一个节点执行时请求暂停（下一节点开始前生效）
        when(executor.executeStream(any())).thenAnswer(inv -> {
            agentCalls.incrementAndGet();
            control.requestPause(executionId);
            return Flux.just(AgentEvent.builder().type("content").content("第一段").build());
        });
        OrchestrationContext ctx = context();
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .nodes(List.of(agent("node-1"), agent("node-2")))
                .edges(List.of(GraphEdge.builder().fromNode("node-1").toNode("node-2").build()))
                .build();

        List<AgentEvent> events = run(handler(), graph, ctx);

        // node-2 未执行（无 node_start）；流以 PAUSED 收尾
        assertThat(events).noneMatch(e -> "node-2".equals(e.getNodeId())
                && "node_start".equals(e.getType()));
        AgentEvent complete = events.stream()
                .filter(e -> "graph_complete".equals(e.getType())).findFirst().orElseThrow();
        assertThat(complete.getMetadata()).containsEntry("status", "PAUSED");
        assertThat(complete.getMetadata()).containsEntry("resumeFromNode", "node-2");
        assertThat(events).anyMatch(e -> "execution_paused".equals(e.getType()));
        // 检查点可恢复
        assertThat(control.hasCheckpoint(ctx.getExecutionId())).isTrue();
    }

    @Test
    @DisplayName("断点续跑：引擎 resume 从检查点恢复，跳过已完成节点完成全图")
    void resumeFromCheckpointViaEngine() {
        OrchestrationContext ctx = context();
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .nodes(List.of(agent("node-1"), agent("node-2"), agent("node-3")))
                .edges(List.of(
                        GraphEdge.builder().fromNode("node-1").toNode("node-2").build(),
                        GraphEdge.builder().fromNode("node-2").toNode("node-3").build()))
                .build();
        // 人工构造暂停检查点：从 node-2 恢复
        control.register(ctx.getExecutionId());
        control.saveCheckpoint(ctx.getExecutionId(),
                new ExecutionControl.Checkpoint(graph, ctx, "node-2"));

        // 走引擎真实 resume API（负责写入 __resumeFromNode 标记）
        com.gewu.agent.engine.orchestration.OrchestrationEngine engine =
                new com.gewu.agent.engine.orchestration.OrchestrationEngine(
                        new com.gewu.agent.engine.orchestration.Orchestrator(
                                executor, hitlGateway, null, null,
                                new GraphNodeExecutor(toolExecutor), control),
                        new com.gewu.agent.engine.orchestration.DefaultGoalPlanner(),
                        null, control);
        List<AgentEvent> events = engine.resume(ctx.getExecutionId()).collectList().block();

        // node-1 被跳过，node-2/node-3 执行
        assertThat(events).noneMatch(e -> "node-1".equals(e.getNodeId()));
        assertThat(events).anyMatch(e -> "node-2".equals(e.getNodeId()) && "node_start".equals(e.getType()));
        assertThat(events).anyMatch(e -> "node-3".equals(e.getNodeId()) && "node_start".equals(e.getType()));
        AgentEvent complete = events.stream()
                .filter(e -> "graph_complete".equals(e.getType())).findFirst().orElseThrow();
        assertThat(complete.getMetadata()).containsEntry("status", "SUCCESS");
    }

    @Test
    @DisplayName("暂停-恢复全闭环：第一段流 PAUSED 后 resume 续跑完成")
    void pauseResumeFullCycle() {
        // 第 1 次 AGENT 调用请求暂停；恢复后的调用正常返回
        AtomicInteger callCount = new AtomicInteger();
        when(executor.executeStream(any())).thenAnswer(inv -> {
            if (callCount.incrementAndGet() == 1) {
                control.requestPause(executionId);
            }
            return Flux.just(AgentEvent.builder().type("content").content("内容" + callCount.get()).build());
        });
        OrchestrationContext ctx = context();
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .nodes(List.of(agent("node-1"), agent("node-2")))
                .edges(List.of(GraphEdge.builder().fromNode("node-1").toNode("node-2").build()))
                .build();

        PipelineModeHandler h = handler();
        List<AgentEvent> firstRun = h.run(graph, ctx).collectList().block();
        assertThat(firstRun).anyMatch(e -> "execution_paused".equals(e.getType()));

        com.gewu.agent.engine.orchestration.OrchestrationEngine engine =
                new com.gewu.agent.engine.orchestration.OrchestrationEngine(
                        new com.gewu.agent.engine.orchestration.Orchestrator(
                                executor, hitlGateway, null, null,
                                new GraphNodeExecutor(toolExecutor), control),
                        new com.gewu.agent.engine.orchestration.DefaultGoalPlanner(),
                        null, control);
        List<AgentEvent> resumed = engine.resume(ctx.getExecutionId()).collectList().block();

        // 续跑流：node-2 执行并 SUCCESS 完成
        assertThat(resumed).anyMatch(e -> "node-2".equals(e.getNodeId()) && "node_start".equals(e.getType()));
        AgentEvent complete = resumed.stream()
                .filter(e -> "graph_complete".equals(e.getType())).findFirst().orElseThrow();
        assertThat(complete.getMetadata()).containsEntry("status", "SUCCESS");
        // 检查点已消费
        assertThat(control.hasCheckpoint(ctx.getExecutionId())).isFalse();
    }

    @Test
    @DisplayName("取消：信号生效后流以 CANCELLED 优雅收尾")
    void cancelTerminatesGracefully() {
        when(executor.executeStream(any())).thenAnswer(inv -> {
            agentCalls.incrementAndGet();
            control.requestCancel(executionId);
            return Flux.just(AgentEvent.builder().type("content").content("部分产出").build());
        });
        OrchestrationGraph graph = OrchestrationGraph.builder()
                .nodes(List.of(agent("node-1"), agent("node-2")))
                .edges(List.of(GraphEdge.builder().fromNode("node-1").toNode("node-2").build()))
                .build();

        List<AgentEvent> events = run(handler(), graph, context());

        AgentEvent complete = events.stream()
                .filter(e -> "graph_complete".equals(e.getType())).findFirst().orElseThrow();
        assertThat(complete.getMetadata()).containsEntry("status", "CANCELLED");
        assertThat(events).anyMatch(e -> "execution_cancelled".equals(e.getType()));
    }
}
