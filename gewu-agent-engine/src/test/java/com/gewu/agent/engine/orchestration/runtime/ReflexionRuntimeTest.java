package com.gewu.agent.engine.orchestration.runtime;

import com.gewu.agent.engine.cognition.ReasoningKernel;
import com.gewu.agent.engine.cognition.ReasoningResult;
import com.gewu.agent.engine.cognition.ReflectionEngine;
import com.gewu.agent.engine.core.AgentExecutor;
import com.gewu.agent.engine.core.AgentTask;
import com.gewu.agent.engine.core.event.AgentEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ReflexionRuntime} D-5 真实反思循环测试（AE-P1-8）：
 * 未接认知保持透传 / Critic 通过单轮采纳 / 不通过反思注入重跑 / 轮次耗尽如实暴露。
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-11-13
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Reflexion 真实反思循环（AE-P1-8/D-5）")
class ReflexionRuntimeTest {

    @Mock
    private AgentExecutor executor;
    @Mock
    private ReasoningKernel reasoningKernel;
    @Mock
    private ReflectionEngine reflectionEngine;

    private AgentTask task() {
        return AgentTask.builder().agentId("a1").sessionId("s1").userId("u1")
                .message("写一个排序函数").build();
    }

    private ReasoningResult critique(double score, boolean accepted, String verdict) {
        return ReasoningResult.builder().type("CRITIC")
                .score(score).accepted(accepted).verdict(verdict).build();
    }

    @Test
    @DisplayName("未接认知内核：保持透传旧行为（单次执行，事件原样转发）")
    void passthroughWithoutKernel() {
        when(executor.executeStream(any()))
                .thenReturn(Flux.just(AgentEvent.builder().type("content").content("直接产出").build()));

        List<AgentEvent> events = new ReflexionRuntime(executor).execute(task())
                .collectList().block(Duration.ofSeconds(10));

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getContent()).isEqualTo("直接产出");
        verify(executor, times(1)).executeStream(any());
    }

    @Test
    @DisplayName("Critic 首轮通过：单轮采纳，无 REFLECTION 事件")
    void criticAcceptsFirstAttempt() {
        when(executor.executeStream(any()))
                .thenReturn(Flux.just(AgentEvent.builder().type("content").content("合格产出").build()));
        when(reasoningKernel.critique(any(), anyList())).thenReturn(critique(0.9, true, "达标"));

        List<AgentEvent> events = new ReflexionRuntime(executor, reasoningKernel, reflectionEngine, 3)
                .execute(task()).collectList().block(Duration.ofSeconds(10));

        assertThat(events)
                .filteredOn(e -> AgentEvent.REFLECTION.equals(e.getType()))
                .isEmpty();
        assertThat(events)
                .filteredOn(e -> "content".equals(e.getType()))
                .extracting(AgentEvent::getContent)
                .contains("合格产出");
        verify(executor, times(1)).executeStream(any());
    }

    @Test
    @DisplayName("Critic 不通过：REFLECTION 事件 + 反思注入重跑，第二轮通过")
    void criticFailsThenReflectionRetryPasses() {
        AtomicInteger calls = new AtomicInteger();
        when(executor.executeStream(any())).thenAnswer(inv -> {
            AgentTask t = inv.getArgument(0);
            return Flux.just(AgentEvent.builder().type("content")
                    .content(calls.incrementAndGet() == 1 ? "首轮产出" : "改进后产出").build());
        });
        when(reasoningKernel.critique(any(), anyList()))
                .thenReturn(critique(0.4, false, "缺少边界处理"))
                .thenReturn(critique(0.9, true, "达标"));
        when(reflectionEngine.reflect(any(), any(), any())).thenReturn("补充空列表与重复元素边界用例");

        List<AgentEvent> events = new ReflexionRuntime(executor, reasoningKernel, reflectionEngine, 3)
                .execute(task()).collectList().block(Duration.ofSeconds(10));

        assertThat(events).anySatisfy(e -> {
            assertThat(AgentEvent.REFLECTION.equals(e.getType())).isTrue();
            assertThat(e.getMetadata()).containsEntry("advice", "补充空列表与重复元素边界用例");
        });
        assertThat(events)
                .filteredOn(e -> "content".equals(e.getType()))
                .extracting(AgentEvent::getContent)
                .contains("改进后产出");
        // 注入：第二轮任务消息包含改进指令与原始任务
        ArgumentCaptor<AgentTask> captor = ArgumentCaptor.forClass(AgentTask.class);
        verify(executor, times(2)).executeStream(captor.capture());
        assertThat(captor.getAllValues().get(1).getMessage())
                .contains("反思改进指令").contains("补充空列表").contains("【原始任务】").contains("写一个排序函数");
        // 模型字段透传（AE-P1-1 同批适配）
        verify(reflectionEngine, times(1)).reflect(any(), any(), any());
    }

    @Test
    @DisplayName("轮次耗尽：如实暴露 ERROR（不静默伪装成功）")
    void roundsExhaustedExposesError() {
        when(executor.executeStream(any()))
                .thenReturn(Flux.just(AgentEvent.builder().type("content").content("产出").build()));
        when(reasoningKernel.critique(any(), anyList())).thenReturn(critique(0.3, false, "不达标"));
        when(reflectionEngine.reflect(any(), any(), any())).thenReturn("仍不达标");

        List<AgentEvent> events = new ReflexionRuntime(executor, reasoningKernel, reflectionEngine, 2)
                .execute(task()).collectList().block(Duration.ofSeconds(10));

        AgentEvent err = events.stream()
                .filter(e -> AgentEvent.ERROR.equals(e.getType())).findFirst().orElseThrow();
        assertThat(err.getErrorMessage()).contains("反思轮次耗尽").contains("rounds=2");
        verify(executor, times(3)).executeStream(any()); // 首轮 + 2 轮重试
    }
}
