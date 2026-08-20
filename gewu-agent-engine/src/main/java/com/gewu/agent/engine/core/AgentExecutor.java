package com.gewu.agent.engine.core;

import com.gewu.agent.engine.core.event.AgentEvent;
import com.gewu.agent.engine.llm.model.LlmResponse;
import reactor.core.publisher.Flux;

/**
 * Agent 执行器接口 - 执行 {@link AgentTask}，产出同步结果或流式事件。
 * <p>框架提供 {@link ReactAgentExecutor}（ReAct + 工具并行）默认实现。
 * 使用方可实现此接口提供自定义执行策略（如 Plan-Execute / Reflexion）。
 *
 * @since 1.0.0
 */
public interface AgentExecutor {

    /** 同步执行：阻塞直到获得最终回复 */
    LlmResponse execute(AgentTask task);

    /** 流式执行：返回增量事件流 */
    Flux<AgentEvent> executeStream(AgentTask task);
}
