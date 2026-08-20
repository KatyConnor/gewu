package com.gewu.agent.engine.core;

import com.gewu.agent.engine.core.event.AgentEvent;
import com.gewu.agent.engine.llm.model.LlmResponse;
import reactor.core.publisher.Flux;

/**
 * Agent 引擎门面 - 框架统一入口。
 * <p>使用方注入此类即可执行 Agent 任务。Phase 1 委托 {@link AgentExecutor}；
 * Phase 2 将扩展编排能力（OrchestrationEngine）。
 *
 * <pre>{@code
 * @Autowired
 * private AgentEngine agentEngine;
 *
 * // 同步执行
 * LlmResponse response = agentEngine.execute(task);
 *
 * // 流式执行
 * Flux<AgentEvent> stream = agentEngine.executeStream(task);
 * }</pre>
 *
 * @since 1.0.0
 */
public class AgentEngine {

    private final AgentExecutor executor;

    public AgentEngine(AgentExecutor executor) {
        this.executor = executor;
    }

    /** 同步执行 Agent 任务 */
    public LlmResponse execute(AgentTask task) {
        return executor.execute(task);
    }

    /** 流式执行 Agent 任务 */
    public Flux<AgentEvent> executeStream(AgentTask task) {
        return executor.executeStream(task);
    }

    /** 获取底层执行器（用于编排层组合） */
    public AgentExecutor getExecutor() {
        return executor;
    }
}
