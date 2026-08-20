package com.gewu.agent.engine.orchestration.runtime;

import com.gewu.agent.engine.core.AgentExecutor;
import com.gewu.agent.engine.core.AgentTask;
import com.gewu.agent.engine.core.event.AgentEvent;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;

/**
 * ReAct 运行时 - 封装 {@link AgentExecutor}（默认实现）。
 * <p>直接委托底层 ReAct 执行器（含工具并行），产出标准流式事件。
 *
 * @since 1.0.0
 */
@RequiredArgsConstructor
public class ReactRuntime implements AgentRuntime {

    private final AgentExecutor executor;

    @Override
    public Flux<AgentEvent> execute(AgentTask task) {
        return executor.executeStream(task);
    }
}