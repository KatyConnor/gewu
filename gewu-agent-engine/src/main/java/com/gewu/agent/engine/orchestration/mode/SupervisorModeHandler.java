package com.gewu.agent.engine.orchestration.mode;

import com.gewu.agent.engine.core.AgentExecutor;
import com.gewu.agent.engine.core.AgentTask;
import com.gewu.agent.engine.core.event.AgentEvent;
import com.gewu.agent.engine.orchestration.model.AgentMessage;
import com.gewu.agent.engine.orchestration.model.GraphNode;
import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import com.gewu.agent.engine.orchestration.model.OrchestrationGraph;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * Supervisor 模式 - 中央 Supervisor Agent 动态分派任务给专家 Agent，汇总结果。
 * <p>第一个 AGENT 节点作为 Supervisor，后续节点为专家。
 * 委托与回传采用 {@link AgentMessage} 标准化信封（TASK_DELEGATE / TASK_RESULT），
 * 携带 messageId / conversationId / 结构化 payload，替代裸字符串传递。
 * 适用于任务边界不清晰、需要动态决策分派的场景。
 *
 * @since 1.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class SupervisorModeHandler implements ModeHandler {

    private final AgentExecutor executor;

    @Override
    public String mode() {
        return "SUPERVISOR";
    }

    @Override
    public Flux<AgentEvent> run(OrchestrationGraph graph, OrchestrationContext ctx) {
        return Flux.create(sink -> {
            sink.next(AgentEvent.builder()
                    .type("graph_start")
                    .metadata(Map.of("executionId", ctx.getExecutionId(), "mode", "SUPERVISOR"))
                    .build());

            var agentNodes = graph.getNodes() == null ? List.<GraphNode>of()
                    : graph.getNodes().stream()
                    .filter(n -> n.getType() == com.gewu.agent.engine.orchestration.model.NodeType.AGENT)
                    .toList();

            if (agentNodes.isEmpty()) {
                sink.next(AgentEvent.builder().type("graph_complete")
                        .metadata(Map.of("status", "SUCCESS")).build());
                sink.complete();
                return;
            }

            // 第一个节点为 Supervisor，其余为专家；Supervisor 产出作为委托任务的初始上下文
            supervisorStep(agentNodes, 0, ctx, new StringBuilder(), sink);
        });
    }

    private void supervisorStep(List<GraphNode> nodes, int index, OrchestrationContext ctx,
                                StringBuilder accumulated, reactor.core.publisher.FluxSink<AgentEvent> sink) {
        if (index >= nodes.size()) {
            sink.next(AgentEvent.builder()
                    .type("graph_complete")
                    .metadata(Map.of("status", "SUCCESS", "output", accumulated.toString()))
                    .build());
            sink.complete();
            return;
        }
        AgentExecutor exec = this.executor;
        var node = nodes.get(index);
        boolean isSupervisor = index == 0;
        String conversationId = ctx.getExecutionId() != null ? ctx.getExecutionId() : "supervisor";

        // 专家节点：构建 TASK_DELEGATE 结构化委托信封（Supervisor -> 专家）
        if (!isSupervisor) {
            GraphNode supervisor = nodes.get(0);
            AgentMessage delegation = AgentMessage.delegate(
                    agentOf(supervisor), agentOf(node), conversationId,
                    Map.of("task", accumulated.length() > 0 ? accumulated.toString()
                            : String.valueOf(ctx.getVariable("input"))));
            sink.next(AgentEvent.builder()
                    .type("message")
                    .nodeId(node.getNodeId()).role(node.getRoleCode())
                    .metadata(Map.of(
                            "messageId", delegation.getMessageId(),
                            "messageType", delegation.getMessageType().name(),
                            "fromAgent", delegation.getFromAgent(),
                            "toAgent", delegation.getToAgent()))
                    .build());
        }

        sink.next(AgentEvent.builder().type("node_start").nodeId(node.getNodeId()).role(node.getRoleCode()).build());

        AgentTask task = AgentTask.builder()
                .agentId(node.getRefId())
                .sessionId(ctx.getSessionId())
                .userId(ctx.getUserId())
                .message(accumulated.length() > 0 ? accumulated.toString()
                        : (String) ctx.getVariable("input"))
                .build();

        var nodeOutput = new StringBuilder();
        exec.executeStream(task).subscribe(
                event -> {
                    // 失败传播（docs/design/47 问题一）：顺序分派链，断链即整图 FAILED
                    if (AgentEvent.ERROR.equals(event.getType())) {
                        sink.next(AgentEvent.builder()
                                .type(AgentEvent.ERROR).errorMessage(event.getErrorMessage())
                                .nodeId(node.getNodeId()).role(node.getRoleCode()).build());
                        sink.next(AgentEvent.builder().type("graph_complete")
                                .metadata(Map.of("status", "FAILED",
                                        "reason", node.getNodeId() + " 执行失败: "
                                                + (event.getErrorMessage() == null ? "未知错误" : event.getErrorMessage())))
                                .build());
                        sink.complete();
                        return;
                    }
                    sink.next(AgentEvent.builder()
                            .type(event.getType()).content(event.getContent()).reasoning(event.getReasoning())
                            .toolCall(event.getToolCall()).toolResult(event.getToolResult())
                            .errorMessage(event.getErrorMessage()).nodeId(node.getNodeId()).role(node.getRoleCode())
                            .build());
                    if ("content".equals(event.getType()) && event.getContent() != null) {
                        nodeOutput.append(event.getContent());
                    }
                },
                sink::error,
                () -> {
                    sink.next(AgentEvent.builder().type("node_complete").nodeId(node.getNodeId())
                            .role(node.getRoleCode()).build());

                    // 累积产出，作为下一节点的委托上下文
                    if (nodeOutput.length() > 0) {
                        if (accumulated.length() > 0) {
                            accumulated.append("\n\n---\n\n");
                        }
                        accumulated.append("[").append(node.getNodeId()).append("] ").append(nodeOutput);
                    }

                    // 专家节点：回传 TASK_RESULT 信封（专家 -> Supervisor）
                    if (!isSupervisor) {
                        GraphNode supervisor = nodes.get(0);
                        AgentMessage result = AgentMessage.result(
                                agentOf(node), agentOf(supervisor), conversationId,
                                Map.of("nodeId", node.getNodeId(), "outputLength", nodeOutput.length()));
                        sink.next(AgentEvent.builder()
                                .type("message")
                                .nodeId(node.getNodeId())
                                .metadata(Map.of(
                                        "messageId", result.getMessageId(),
                                        "messageType", result.getMessageType().name(),
                                        "fromAgent", result.getFromAgent(),
                                        "toAgent", result.getToAgent()))
                                .build());
                    }

                    supervisorStep(nodes, index + 1, ctx, accumulated, sink);
                });
    }

    private String agentOf(GraphNode node) {
        return node.getRefId() != null ? node.getRefId() : node.getNodeId();
    }
}
