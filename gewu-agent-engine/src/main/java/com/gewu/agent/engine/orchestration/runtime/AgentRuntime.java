package com.gewu.agent.engine.orchestration.runtime;

import com.gewu.agent.engine.core.AgentExecutor;
import com.gewu.agent.engine.core.AgentTask;
import com.gewu.agent.engine.core.event.AgentEvent;
import reactor.core.publisher.Flux;

/**
 * Agent 运行时接口 - 编排引擎中 AGENT 节点的执行策略。
 * <p>不同运行时实现不同的推理循环：
 * <ul>
 *   <li>{@link ReactRuntime} - ReAct + 工具并行（封装 {@code AgentExecutor}）</li>
 *   <li>{@link PlanExecuteRuntime} - 先规划任务列表再逐个执行 ReAct</li>
 *   <li>{@link ReflexionRuntime} - ReAct + 执行后反思 + 改进重试</li>
 * </ul>
 *
 * @since 1.0.0
 */
public interface AgentRuntime {

    /**
     * 执行 Agent 任务，返回流式事件。
     *
     * @param task Agent 任务
     * @return 流式事件
     */
    Flux<AgentEvent> execute(AgentTask task);
}