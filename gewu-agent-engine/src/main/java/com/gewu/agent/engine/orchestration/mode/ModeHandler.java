package com.gewu.agent.engine.orchestration.mode;

import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import com.gewu.agent.engine.orchestration.model.OrchestrationGraph;
import com.gewu.agent.engine.orchestration.model.OrchestrationResult;
import com.gewu.agent.engine.core.event.AgentEvent;
import reactor.core.publisher.Flux;

/**
 * 编排模式处理器接口 - 对应 {@link com.gewu.agent.engine.orchestration.model.OrchestrationMode}。
 * <p>每种模式实现特定的多 Agent 调度策略：
 * <ul>
 *   <li>{@link SupervisorModeHandler} - 中央 Supervisor 动态分派</li>
 *   <li>{@link PipelineModeHandler} - 固定流水线串行</li>
 *   <li>{@link SwarmModeHandler} - Agent 间自主 handoff</li>
 *   <li>{@link DebateModeHandler} - 多方案辩论 + 裁判裁决</li>
 * </ul>
 *
 * @since 1.0.0
 */
public interface ModeHandler {

    /** 模式标识 */
    String mode();

    /**
     * 执行编排。
     *
     * @param graph    编排图
     * @param context  执行上下文
     * @return 流式事件，完成时发射 graph_complete 事件
     */
    Flux<AgentEvent> run(OrchestrationGraph graph, OrchestrationContext context);

    /**
     * 同步执行编排（默认实现阻塞等待流式完成）。
     */
    default OrchestrationResult runSync(OrchestrationGraph graph, OrchestrationContext context) {
        StringBuilder output = new StringBuilder();
        String[] status = {"FAILED"};
        run(graph, context)
                .doOnNext(event -> {
                    if (AgentEvent.DONE.equals(event.getType())) {
                        status[0] = "SUCCESS";
                    }
                    if (AgentEvent.CONTENT.equals(event.getType()) && event.getContent() != null) {
                        output.append(event.getContent());
                    }
                })
                .blockLast();
        return "SUCCESS".equals(status[0])
                ? OrchestrationResult.success(context.getExecutionId(), output.toString())
                : OrchestrationResult.failure(context.getExecutionId(), "编排执行未成功");
    }
}