package com.gewu.agent.engine.orchestration.runtime;

import com.gewu.agent.engine.core.AgentExecutor;
import com.gewu.agent.engine.core.AgentTask;
import com.gewu.agent.engine.core.event.AgentEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * Reflexion 运行时 - 执行 ReAct 后反思，不达标则改进重试（限 N 轮）。
 * <p>策略：ReactRuntime -> Critic 评估 -> 不过则反思 + 重做（限 maxReflectionRounds 轮）。
 * 适用于质量要求高的任务（精确性 / 正确性验证）。Critic 评估与反思逻辑由使用方通过
 * {@code ReasoningKernel} SPI 提供；未提供时回退为单次 ReAct。
 *
 * @since 1.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class ReflexionRuntime implements AgentRuntime {

    private final AgentExecutor executor;

    /** 反思重试最大轮次（防止无限循环） */
    private final int maxReflectionRounds;

    public ReflexionRuntime(AgentExecutor executor) {
        this(executor, 3);
    }

    @Override
    public Flux<AgentEvent> execute(AgentTask task) {
        return Flux.<AgentEvent>create(sink -> {
            sink.next(AgentEvent.builder()
                    .type(AgentEvent.STATUS)
                    .content("Reflexion 执行中...")
                    .metadata(java.util.Map.of("maxRounds", maxReflectionRounds))
                    .build());

            // 简化实现：委托 ReAct 执行；Critic/反思由使用方通过组合编排图节点实现
            // 完整 Reflexion 闭环由认知 SPI（ReasoningKernel）配合实现
            executor.executeStream(task).subscribe(
                    sink::next,
                    sink::error,
                    sink::complete
            );
        }).doOnError(e -> log.error("Reflexion 异常: agentId={}", task.getAgentId(), e));
    }
}