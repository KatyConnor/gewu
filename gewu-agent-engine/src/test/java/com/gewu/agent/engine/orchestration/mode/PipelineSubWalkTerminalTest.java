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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * AE-P0-1（49 号 F-03/F-08 · RPT-002 F-02）回归：PLAN/SUBGRAPH 子 Walk 异常收尾必须委托父链。
 * <p>修复前：failGraph 直接 complete 共享 sink 杀死父图事件流并 unregister 共享 executionId；
 * pauseHere 把子图检查点写入共享 executionId——resume 在父图上按子图节点寻址必然落空；
 * PLAN 节点无特性开关门控、生产可达。
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-11-12
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Pipeline 子 Walk 异常收尾父链委托（AE-P0-1）")
class PipelineSubWalkTerminalTest {

    @Mock
    private AgentExecutor executor;

    @Mock
    private HitlGateway hitlGateway;

    @Mock
    private ToolExecutor toolExecutor;

    @Mock
    private GoalPlanner goalPlanner;

    private ExecutionControl control;

    private static final String EXEC_ID = "exec-subwalk-1";

    @BeforeEach
    void setUp() {
        control = new ExecutionControl();
        lenient().when(toolExecutor.execute(any(), any(), any(), any()))
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
                .executionId(EXEC_ID).userId("u1").sessionId("s1").build();
        ctx.putVariable("input", "初始输入");
        return ctx;
    }

    /** 根图：plan-1（PLAN）→ final（AGENT），graphId 显式声明用于检查点父链断言 */
    private OrchestrationGraph rootGraph() {
        return OrchestrationGraph.builder()
                .graphId("root-g")
                .nodes(List.of(
                        GraphNode.builder().nodeId("plan-1").type(NodeType.PLAN).build(),
                        agent("final")))
                .edges(List.of(GraphEdge.builder().fromNode("plan-1").toNode("final").build()))
                .build();
    }

    private List<AgentEvent> run(PipelineModeHandler handler, OrchestrationGraph graph,
                                 OrchestrationContext ctx) {
        return handler.run(graph, ctx).collectList().block(java.time.Duration.ofSeconds(10));
    }

    // ==================== 红基线 ①：子图 fail-fast 上抛 ====================

    @Test
    @DisplayName("子图 fail-fast 上抛：父图 PLAN 节点被标记失败（ERROR 上抛+失败原因含'子图失败'+单一 FAILED 收尾）")
    void subWalkFailFastDelegatesToParentFailureSemantics() {
        OrchestrationContext ctx = context();
        // 计划：波1=[s1, sFail]（并行）+ 波2=[s2 依赖两者]——MERGE 非终节点（避开
        // MERGE 终结图的 continueTo(null) NPE 潜在缺陷，见 FIX-20261112-merge-terminal 衍生单）
        when(goalPlanner.plan(any(), any())).thenReturn(PlanGraph.builder()
                .planId("p1").goal("含失败步骤的计划").createdBy("test")
                .steps(List.of(
                        PlanGraph.PlanStep.builder().stepId("s1").description("正常步骤").dependencies(List.of()).build(),
                        PlanGraph.PlanStep.builder().stepId("sFail").description("失败步骤").dependencies(List.of()).build(),
                        PlanGraph.PlanStep.builder().stepId("s2").description("收尾步骤").dependencies(List.of("s1", "sFail")).build()))
                .build());
        // 子图 AGENT 节点 message/agentId 全同（fromPlanGraph refId=null、inputs=null），
        // 以调用序注入失败：第 1 次=s1（OK）、第 2 次=sFail（ERROR 事件→handleNodeFailure→fail-fast）
        AtomicInteger agentCalls = new AtomicInteger();
        when(executor.executeStream(any())).thenAnswer(inv -> {
            int n = agentCalls.incrementAndGet();
            if (n == 2) {
                return Flux.just(AgentEvent.builder().type(AgentEvent.ERROR)
                        .errorMessage("模拟子图节点失败").build());
            }
            return Flux.just(AgentEvent.builder().type("content").content("OK产出" + n).build());
        });

        List<AgentEvent> events = run(handler(), rootGraph(), ctx);

        // 父图 PLAN 节点被标记失败（上抛证据；修复前仅有子节点 sFail 的 ERROR，父图语义不可达）
        assertThat(events).anySatisfy(e -> {
            assertThat(e.getType()).isEqualTo(AgentEvent.ERROR);
            assertThat(e.getNodeId()).isEqualTo("plan-1");
            assertThat(e.getErrorMessage()).contains("子图失败");
        });
        // 单一 FAILED 终态，失败原因上抛（修复前 reason 为子节点级、不含"子图失败"）
        List<AgentEvent> completes = events.stream()
                .filter(e -> AgentEvent.GRAPH_COMPLETE.equals(e.getType())).toList();
        assertThat(completes).hasSize(1);
        assertThat(completes.get(0).getMetadata()).containsEntry("status", "FAILED");
        assertThat(String.valueOf(completes.get(0).getMetadata().get("reason"))).contains("子图失败");
        // final 节点未被执行（父图 fail-fast 终止）
        assertThat(events)
                .filteredOn(e -> AgentEvent.NODE_START.equals(e.getType()))
                .extracting(AgentEvent::getNodeId)
                .doesNotContain("final");
    }

    // ==================== 红基线 ②：子图内暂停 ====================

    @Test
    @DisplayName("子图内暂停：检查点保存父链状态（根图+恢复节点=PLAN节点），流以 PAUSED 收尾且注册表保留")
    void pauseInsideSubWalkSavesParentChainCheckpoint() {
        OrchestrationContext ctx = context();
        // 计划：波1=[s1, s2]（并行）+ 波2=[s3 依赖两者]——MERGE 非终节点
        when(goalPlanner.plan(any(), any())).thenReturn(PlanGraph.builder()
                .planId("p1").goal("两步并行加收尾").createdBy("test")
                .steps(List.of(
                        PlanGraph.PlanStep.builder().stepId("s1").description("第一步").dependencies(List.of()).build(),
                        PlanGraph.PlanStep.builder().stepId("s2").description("第二步").dependencies(List.of()).build(),
                        PlanGraph.PlanStep.builder().stepId("s3").description("收尾步骤").dependencies(List.of("s1", "s2")).build()))
                .build());
        AtomicBoolean pauseArmed = new AtomicBoolean(false);
        when(executor.executeStream(any())).thenAnswer(inv -> {
            if (pauseArmed.compareAndSet(false, true)) {
                // 首个子图节点（s1）执行期间请求暂停：下一个 visit（s2）处生效
                control.requestPause(EXEC_ID);
            }
            return Flux.just(AgentEvent.builder().type("content").content("产出").build());
        });

        List<AgentEvent> events = run(handler(), rootGraph(), ctx);

        // 流以 PAUSED 收尾，恢复节点上抛为父图 PLAN 节点
        AgentEvent paused = events.stream()
                .filter(e -> AgentEvent.EXECUTION_PAUSED.equals(e.getType())).findFirst().orElseThrow();
        assertThat(paused.getNodeId()).isEqualTo("plan-1");
        assertThat(events).anySatisfy(e -> {
            assertThat(e.getType()).isEqualTo(AgentEvent.GRAPH_COMPLETE);
            assertThat(e.getMetadata()).containsEntry("status", "PAUSED");
        });
        // 检查点=父链状态（修复前：graph=子图 UUID、resumeFromNode=子图内节点 s2，resume 必然落空）
        assertThat(control.hasCheckpoint(EXEC_ID)).isTrue();
        Optional<ExecutionControl.Checkpoint> cp = control.takeCheckpoint(EXEC_ID);
        assertThat(cp).isPresent();
        assertThat(cp.get().graph().getGraphId()).isEqualTo("root-g");
        assertThat(cp.get().resumeFromNodeId()).isEqualTo("plan-1");
    }
}
