package com.gewu.agent.engine.orchestration.runtime;

import com.gewu.agent.engine.core.AgentExecutor;
import com.gewu.agent.engine.core.AgentTask;
import com.gewu.agent.engine.core.event.AgentEvent;
import com.gewu.agent.engine.orchestration.GoalPlanner;
import com.gewu.agent.engine.orchestration.model.PlanGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PlanExecuteRuntime} 四项适配测试（AE-P1-1）：
 * ①事件 metadata/finishReason 透传（HITL/预算事件不断链）②波次结构节点过滤（v1 串行语义）
 * ③失败 fail-fast + 模型/模式字段透传 ④步任务字段适配。
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-11-13
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlanExecute 运行时适配（AE-P1-1）")
class PlanExecuteRuntimeTest {

    @Mock
    private AgentExecutor executor;

    @Mock
    private GoalPlanner goalPlanner;

    private AgentTask task() {
        return AgentTask.builder().agentId("a1").sessionId("s1").userId("u1")
                .modelProvider("prov-x").modelName("model-x").message("做一件事").build();
    }

    private PlanGraph twoStepPlan() {
        return PlanGraph.builder().planId("p1").goal("g").createdBy("t")
                .steps(List.of(
                        PlanGraph.PlanStep.builder().stepId("s1").description("第一步").dependencies(List.of()).build(),
                        PlanGraph.PlanStep.builder().stepId("s2").description("第二步").dependencies(List.of("s1")).build()))
                .build();
    }

    @Test
    @DisplayName("适配①：步内事件 metadata 透传（budget_warning 等带元数据事件不再丢失）")
    void stepEventMetadataPreserved() {
        when(goalPlanner.plan(any(), any())).thenReturn(twoStepPlan());
        when(executor.executeStream(any())).thenAnswer(inv -> Flux.just(
                AgentEvent.builder().type("budget_warning")
                        .metadata(Map.of("utilization", 0.9)).build(),
                AgentEvent.builder().type("content").content("产出").build()));

        List<AgentEvent> events = new PlanExecuteRuntime(executor, goalPlanner).execute(task())
                .collectList().block(Duration.ofSeconds(10));

        AgentEvent warn = events.stream()
                .filter(e -> "budget_warning".equals(e.getType())).findFirst().orElseThrow();
        assertThat(warn.getMetadata()).containsEntry("utilization", 0.9);
        assertThat(warn.getNodeId()).isNotBlank();
    }

    @Test
    @DisplayName("适配②：波次结构节点（PARALLEL/MERGE）不再作为步骤执行（v1 串行语义声明）")
    void structuralNodesFiltered() {
        when(goalPlanner.plan(any(), any())).thenReturn(PlanGraph.builder().planId("p1").goal("g").createdBy("t")
                .steps(List.of(
                        PlanGraph.PlanStep.builder().stepId("s1").description("一").dependencies(List.of()).build(),
                        PlanGraph.PlanStep.builder().stepId("s2").description("二").dependencies(List.of()).build()))
                .build());
        when(executor.executeStream(any()))
                .thenReturn(Flux.just(AgentEvent.builder().type("content").content("ok").build()));

        List<AgentEvent> events = new PlanExecuteRuntime(executor, goalPlanner).execute(task())
                .collectList().block(Duration.ofSeconds(10));

        List<String> stepStatuses = events.stream()
                .filter(e -> AgentEvent.STATUS.equals(e.getType())
                        && e.getContent() != null && e.getContent().startsWith("执行步骤 "))
                .map(AgentEvent::getContent).toList();
        assertThat(stepStatuses).hasSize(2);
        assertThat(String.join("|", stepStatuses)).doesNotContain("__parallel").doesNotContain("__merge");
    }

    @Test
    @DisplayName("适配③：步骤 ERROR 事件 fail-fast（后续步骤跳过、失败事件带 nodeId）")
    void stepErrorFailsFast() {
        when(goalPlanner.plan(any(), any())).thenReturn(twoStepPlan());
        AtomicInteger calls = new AtomicInteger();
        when(executor.executeStream(any())).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                return Flux.just(AgentEvent.builder()
                        .type(AgentEvent.ERROR).errorMessage("步骤一失败").build());
            }
            return Flux.just(AgentEvent.builder().type("content").content("不应执行").build());
        });

        List<AgentEvent> events = new PlanExecuteRuntime(executor, goalPlanner).execute(task())
                .collectList().block(Duration.ofSeconds(10));

        AgentEvent err = events.stream()
                .filter(e -> AgentEvent.ERROR.equals(e.getType())).findFirst().orElseThrow();
        assertThat(err.getNodeId()).isEqualTo("s1");
        assertThat(err.getErrorMessage()).isEqualTo("步骤一失败");
        // 后续步骤未执行（fail-fast）
        assertThat(calls.get()).isEqualTo(1);
        assertThat(events)
                .filteredOn(e -> AgentEvent.STATUS.equals(e.getType())
                        && e.getContent() != null && e.getContent().contains("s2"))
                .isEmpty();
    }

    @Test
    @DisplayName("适配④：模型/模式字段透传到步任务（不再退化为默认模型）")
    void modelFieldsCarriedToStepTasks() {
        when(goalPlanner.plan(any(), any())).thenReturn(twoStepPlan());
        when(executor.executeStream(any()))
                .thenReturn(Flux.just(AgentEvent.builder().type("content").content("ok").build()));

        new PlanExecuteRuntime(executor, goalPlanner).execute(task())
                .collectList().block(Duration.ofSeconds(10));

        ArgumentCaptor<AgentTask> captor = ArgumentCaptor.forClass(AgentTask.class);
        verify(executor, times(2)).executeStream(captor.capture());
        assertThat(captor.getAllValues().get(0).getModelProvider()).isEqualTo("prov-x");
        assertThat(captor.getAllValues().get(1).getModelName()).isEqualTo("model-x");
    }
}
