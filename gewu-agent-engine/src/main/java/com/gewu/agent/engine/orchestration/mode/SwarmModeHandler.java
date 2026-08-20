package com.gewu.agent.engine.orchestration.mode;

import com.gewu.agent.engine.core.AgentExecutor;
import com.gewu.agent.engine.core.AgentTask;
import com.gewu.agent.engine.core.event.AgentEvent;
import com.gewu.agent.engine.orchestration.HandoffParser;
import com.gewu.agent.engine.orchestration.model.AgentMessage;
import com.gewu.agent.engine.orchestration.model.GraphNode;
import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import com.gewu.agent.engine.orchestration.model.OrchestrationGraph;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Swarm 模式 - 轻量级 Agent 间控制权传递（handoff），无中心节点。
 * <p>Agent 输出中可嵌入路由指令（经 {@link HandoffParser} 解析）：
 * <ul>
 *   <li>{@code FINISH[:reason]} - 声明任务完成，立即结束</li>
 *   <li>{@code HANDOFF:target|reason} - 移交控制权给指定节点（nodeId 或 agentId）</li>
 *   <li>无指令 - 按声明顺序传递给下一个 Agent</li>
 * </ul>
 * 传递采用 {@link AgentMessage} 标准化信封（携带 messageId/conversationId），
 * 防环：记录 handoff 链，传递深度超阈值强制终止。
 * 适用于探索性、边界动态变化的任务。
 *
 * @since 1.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class SwarmModeHandler implements ModeHandler {

    private final AgentExecutor executor;
    private final int maxHandoffs;
    private final HandoffParser handoffParser;

    public SwarmModeHandler(AgentExecutor executor) {
        this(executor, 8, new HandoffParser());
    }

    public SwarmModeHandler(AgentExecutor executor, int maxHandoffs) {
        this(executor, maxHandoffs, new HandoffParser());
    }

    @Override
    public String mode() {
        return "SWARM";
    }

    @Override
    public Flux<AgentEvent> run(OrchestrationGraph graph, OrchestrationContext ctx) {
        return Flux.create(sink -> {
            sink.next(AgentEvent.builder()
                    .type("graph_start")
                    .metadata(Map.of("executionId", ctx.getExecutionId(), "mode", "SWARM"))
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

            // 从第一个节点开始传递
            swarmStep(executor, agentNodes, 0, new HashSet<>(), 0, ctx,
                    (String) ctx.getVariable("input"), sink);
        });
    }

    /**
     * Swarm 单步传递。
     *
     * @param handoffChain 记录已访问的节点（防环）
     * @param depth 当前传递深度
     * @param input 上一个 Agent 的输出（作为当前输入）
     */
    private void swarmStep(AgentExecutor exec, List<GraphNode> nodes,
                           int index, Set<String> handoffChain, int depth, OrchestrationContext ctx,
                           String input, reactor.core.publisher.FluxSink<AgentEvent> sink) {
        if (depth >= maxHandoffs || index >= nodes.size() || !handoffChain.add(nodes.get(index).getNodeId())) {
            sink.next(AgentEvent.builder()
                    .type("handoff")
                    .metadata(Map.of("reason", "FINISH_OR_LIMIT", "depth", depth))
                    .build());
            sink.next(AgentEvent.builder().type("graph_complete")
                    .metadata(Map.of("status", "SUCCESS")).build());
            sink.complete();
            return;
        }

        var node = nodes.get(index);
        sink.next(AgentEvent.builder().type("node_start").nodeId(node.getNodeId()).role(node.getRoleCode()).build());

        // message 优先参数传入的 input（上一 Agent 输出），否则回退默认变量
        String msg = (input != null && !input.isBlank()) ? input : (String) ctx.getVariable("input");
        AgentTask task = AgentTask.builder()
                .agentId(node.getRefId())
                .sessionId(ctx.getSessionId())
                .userId(ctx.getUserId())
                .message(msg)
                .build();

        final int currentDepth = depth;
        final String currentInput = msg;
        var accumulated = new StringBuilder();

        exec.executeStream(task).subscribe(
                event -> {
                    sink.next(AgentEvent.builder()
                            .type(event.getType()).content(event.getContent()).reasoning(event.getReasoning())
                            .toolCall(event.getToolCall()).toolResult(event.getToolResult())
                            .errorMessage(event.getErrorMessage()).nodeId(node.getNodeId()).role(node.getRoleCode())
                            .build());
                    if ("content".equals(event.getType()) && event.getContent() != null) {
                        accumulated.append(event.getContent());
                    }
                },
                sink::error,
                () -> {
                    sink.next(AgentEvent.builder().type("node_complete").nodeId(node.getNodeId())
                            .role(node.getRoleCode())
                            .metadata(Map.of("depth", currentDepth)).build());
                    routeNext(exec, nodes, index, node, handoffChain, currentDepth, ctx,
                            accumulated.toString(), currentInput, sink);
                });
    }

    /** 解析当前 Agent 输出中的路由指令并决定下一跳（FINISH / HANDOFF / 默认顺序） */
    private void routeNext(AgentExecutor exec, List<GraphNode> nodes, int index, GraphNode current,
                           Set<String> handoffChain, int depth, OrchestrationContext ctx,
                           String output, String fallbackInput, reactor.core.publisher.FluxSink<AgentEvent> sink) {
        String conversationId = ctx.getExecutionId() != null ? ctx.getExecutionId() : "swarm";
        HandoffParser.HandoffDecision decision = handoffParser.parse(output);
        String carriedOutput = output != null && !output.isBlank() ? output : fallbackInput;

        if (decision.isFinish()) {
            // FINISH：任务完成，立即结束
            AgentMessage message = AgentMessage.finish(nodeAgent(current), conversationId);
            sink.next(AgentEvent.builder()
                    .type("handoff")
                    .metadata(Map.of("reason", decision.getReason() != null ? decision.getReason() : "FINISH",
                            "depth", depth, "messageId", message.getMessageId(),
                            "messageType", message.getMessageType().name()))
                    .build());
            sink.next(AgentEvent.builder().type("graph_complete")
                    .metadata(Map.of("status", "SUCCESS")).build());
            sink.complete();
            return;
        }

        int nextIndex = index + 1;
        GraphNode target = null;
        if (decision.isHasDirective() && decision.getTargetNodeId() != null) {
            // HANDOFF:target - 按 nodeId / agentId 定位目标节点
            target = findNode(nodes, decision.getTargetNodeId());
            if (target != null) {
                nextIndex = nodes.indexOf(target);
                AgentMessage message = AgentMessage.handoff(nodeAgent(current), nodeAgent(target), conversationId);
                sink.next(AgentEvent.builder()
                        .type("handoff")
                        .metadata(Map.of("reason", decision.getReason() != null ? decision.getReason() : "HANDOFF",
                                "target", target.getNodeId(), "depth", depth,
                                "messageId", message.getMessageId(),
                                "messageType", message.getMessageType().name()))
                        .build());
            } else {
                log.warn("Swarm HANDOFF 目标不存在，回退顺序传递: target={}, from={}",
                        decision.getTargetNodeId(), current.getNodeId());
            }
        }

        swarmStep(exec, nodes, nextIndex, handoffChain, depth + 1, ctx, carriedOutput, sink);
    }

    /** 按 nodeId 或 refId（agentId）定位节点 */
    private GraphNode findNode(List<GraphNode> nodes, String target) {
        for (GraphNode node : nodes) {
            if (target.equals(node.getNodeId())) {
                return node;
            }
        }
        for (GraphNode node : nodes) {
            if (target.equals(node.getRefId())) {
                return node;
            }
        }
        return null;
    }

    private String nodeAgent(GraphNode node) {
        return node.getRefId() != null ? node.getRefId() : node.getNodeId();
    }
}
