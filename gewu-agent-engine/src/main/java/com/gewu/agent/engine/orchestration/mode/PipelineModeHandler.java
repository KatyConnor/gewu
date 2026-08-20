package com.gewu.agent.engine.orchestration.mode;

import com.gewu.agent.engine.core.AgentExecutor;
import com.gewu.agent.engine.core.AgentTask;
import com.gewu.agent.engine.core.event.AgentEvent;
import com.gewu.agent.engine.hitl.ApprovalRequest;
import com.gewu.agent.engine.hitl.HitlGateway;
import com.gewu.agent.engine.hitl.HumanDecision;
import com.gewu.agent.engine.orchestration.model.NodeType;
import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import com.gewu.agent.engine.orchestration.model.OrchestrationGraph;
import com.gewu.agent.engine.orchestration.model.OrchestrationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pipeline 模式 - 固定顺序的串行流水线。
 * <p>每个 Agent 节点处理一个 SDLC 阶段，产出传递给下一个节点。
 * 适用于流程明确的标准化交付（场景：需求->架构->开发->审查->测试->部署->运维）。
 *
 * @since 1.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class PipelineModeHandler implements ModeHandler {

    private final AgentExecutor executor;
    private final HitlGateway hitlGateway;

    /** HUMAN 节点默认审批超时（秒） */
    private static final int DEFAULT_APPROVAL_TIMEOUT_SECONDS = 1800;
    /** 审批摘要最大长度 */
    private static final int SUMMARY_MAX_LENGTH = 500;

    @Override
    public String mode() {
        return "PIPELINE";
    }

    @Override
    public Flux<AgentEvent> run(OrchestrationGraph graph, OrchestrationContext context) {
        return Flux.create(sink -> {
            sink.next(AgentEvent.builder()
                    .type("graph_start")
                    .metadata(Map.of("executionId", context.getExecutionId(),
                            "mode", "PIPELINE"))
                    .build());

            var nodes = graph.getNodes();
            if (nodes == null || nodes.isEmpty()) {
                sink.next(AgentEvent.builder().type("graph_complete")
                        .metadata(Map.of("status", "SUCCESS")).build());
                sink.complete();
                return;
            }

            // 按边排序确定执行顺序（线性流水线）
            var ordered = topoOrder(graph);
            var accumulated = new StringBuilder();

            // 串行执行链
            pipelineStep(executor, ordered, 0, context, accumulated, sink, graph);
        });
    }

    private void pipelineStep(AgentExecutor exec, List<com.gewu.agent.engine.orchestration.model.GraphNode> ordered,
                              int index, OrchestrationContext ctx, StringBuilder accumulated,
                              reactor.core.publisher.FluxSink<AgentEvent> sink,
                              OrchestrationGraph graph) {
        if (index >= ordered.size()) {
            sink.next(AgentEvent.builder()
                    .type("graph_complete")
                    .metadata(Map.of("status", "SUCCESS", "output", accumulated.toString()))
                    .build());
            sink.complete();
            return;
        }
        var node = ordered.get(index);
        sink.next(AgentEvent.builder()
                .type("node_start")
                .nodeId(node.getNodeId())
                .role(node.getRoleCode())
                .build());

        // HUMAN 节点：请求人工审批，阻塞等待决策
        if (node.getType() == NodeType.HUMAN) {
            handleHumanNode(node, index, ordered, ctx, accumulated, sink, graph);
            return;
        }

        // 上一节点产出作为本节点输入
        String message = accumulated.length() > 0 ? accumulated.toString() : (String) ctx.getVariable("input");
        AgentTask task = AgentTask.builder()
                .agentId(node.getRefId())
                .sessionId(ctx.getSessionId())
                .userId(ctx.getUserId())
                .message(message)
                .build();

        exec.executeStream(task).subscribe(
                event -> {
                    sink.next(AgentEvent.builder()
                            .type(event.getType())
                            .content(event.getContent())
                            .reasoning(event.getReasoning())
                            .toolCall(event.getToolCall())
                            .toolResult(event.getToolResult())
                            .errorMessage(event.getErrorMessage())
                            .nodeId(node.getNodeId())
                            .role(node.getRoleCode())
                            .build());
                    if ("content".equals(event.getType()) && event.getContent() != null) {
                        accumulated.append(event.getContent());
                    }
                },
                sink::error,
                () -> {
                    sink.next(AgentEvent.builder()
                            .type("node_complete")
                            .nodeId(node.getNodeId())
                            .role(node.getRoleCode())
                            .metadata(Map.of("outputLen", accumulated.length()))
                            .build());
                    // 重置 accumulated 为本节点产出供下一节点使用
                    pipelineStep(exec, ordered, index + 1, ctx, accumulated, sink, graph);
                });
    }

    /**
     * HUMAN 节点处理：请求人工审批并阻塞等待决策。
     * <p>APPROVED -> 记录审批意见到产出，继续下一节点；
     * REJECTED/超时 -> 终止图并标记 FAILED。
     */
    private void handleHumanNode(com.gewu.agent.engine.orchestration.model.GraphNode node,
                                 int index, List<com.gewu.agent.engine.orchestration.model.GraphNode> ordered,
                                 OrchestrationContext ctx, StringBuilder accumulated,
                                 reactor.core.publisher.FluxSink<AgentEvent> sink,
                                 OrchestrationGraph graph) {
        String summary = accumulated.length() > 0
                ? accumulated.substring(0, Math.min(SUMMARY_MAX_LENGTH, accumulated.length()))
                : "人工审核节点（无前置产出）";

        // 节点 config 可覆盖审批超时（config.timeoutSeconds）
        int timeoutSeconds = DEFAULT_APPROVAL_TIMEOUT_SECONDS;
        if (node.getConfig() != null && node.getConfig().get("timeoutSeconds") instanceof Number n) {
            timeoutSeconds = n.intValue();
        }

        ApprovalRequest request = ApprovalRequest.builder()
                .approvalId(UUID.randomUUID().toString())
                .executionId(ctx.getExecutionId())
                .nodeId(node.getNodeId())
                .type("APPROVE_REJECT")
                .summary(summary)
                .timeoutSeconds(timeoutSeconds)
                .build();

        sink.next(AgentEvent.builder()
                .type("approval_required")
                .nodeId(node.getNodeId())
                .metadata(Map.of("approvalId", request.getApprovalId(),
                        "timeoutSeconds", timeoutSeconds))
                .build());

        hitlGateway.requestApproval(request).subscribe(
                decision -> {
                    boolean approved = "APPROVED".equals(decision.getDecision());
                    sink.next(AgentEvent.builder()
                            .type("approval_result")
                            .nodeId(node.getNodeId())
                            .metadata(Map.of("approved", approved,
                                    "operator", decision.getOperatorId() != null ? decision.getOperatorId() : "",
                                    "comment", decision.getValue() != null ? decision.getValue() : ""))
                            .build());
                    sink.next(AgentEvent.builder()
                            .type("node_complete")
                            .nodeId(node.getNodeId())
                            .role(node.getRoleCode())
                            .metadata(Map.of("approval", approved ? "APPROVED" : "REJECTED"))
                            .build());
                    if (approved) {
                        // 审批意见并入产出，供下游节点参考
                        if (decision.getValue() != null && !decision.getValue().isBlank()) {
                            accumulated.append("\n\n[人工审批意见] ").append(decision.getValue());
                        }
                        pipelineStep(executor, ordered, index + 1, ctx, accumulated, sink, graph);
                    } else {
                        // 驳回时回滚一版图变量（撤销最近一次版本化写入），并留痕最终状态快照
                        boolean rolledBack = ctx.rollbackOneVersion();
                        log.info("人工驳回回滚: executionId={}, nodeId={}, rolledBack={}, stateVersion={}",
                                ctx.getExecutionId(), node.getNodeId(), rolledBack, ctx.getStateVersion());
                        sink.next(AgentEvent.builder()
                                .type("graph_complete")
                                .metadata(Map.of("status", "FAILED",
                                        "reason", "人工审批被驳回: " + (decision.getValue() != null ? decision.getValue() : ""),
                                        "nodeId", node.getNodeId(),
                                        "stateVersion", ctx.getStateVersion(),
                                        "stateKeys", ctx.snapshotVariables().keySet().size()))
                                .build());
                        sink.complete();
                    }
                },
                error -> {
                    log.error("HUMAN 节点审批异常: nodeId={}", node.getNodeId(), error);
                    sink.next(AgentEvent.builder()
                            .type("graph_complete")
                            .metadata(Map.of("status", "FAILED",
                                    "reason", "审批流程异常: " + error.getMessage()))
                            .build());
                    sink.complete();
                });
    }

/** 按边拓扑排序节点（线性流水线情形） */
    private List<com.gewu.agent.engine.orchestration.model.GraphNode> topoOrder(OrchestrationGraph graph) {
        var nodes = graph.getNodes();
        var edges = graph.getEdges();
        if (edges == null || edges.isEmpty()) {
            return nodes;
        }
        // 找入度为0的起点，沿边排序
        Map<String, com.gewu.agent.engine.orchestration.model.GraphNode> byId = new LinkedHashMap<>();
        for (var n : nodes) {
            byId.put(n.getNodeId(), n);
        }
        Map<String, String> next = new HashMap<>();
        Set<String> targets = new HashSet<>();
        for (var e : edges) {
            next.put(e.getFromNode(), e.getToNode());
            targets.add(e.getToNode());
        }
        // 找起点
        String start = next.keySet().stream().filter(k -> !targets.contains(k)).findFirst()
                .orElse(nodes.isEmpty() ? null : nodes.get(0).getNodeId());
        var ordered = new ArrayList<com.gewu.agent.engine.orchestration.model.GraphNode>();
        String cur = start;
        Set<String> visited = new HashSet<>();
        while (cur != null && visited.add(cur)) {
            var n = byId.get(cur);
            if (n != null) {
                ordered.add(n);
            }
            cur = next.get(cur);
        }
        // 加入孤立节点
        for (var n : nodes) {
            if (!visited.contains(n.getNodeId())) {
                ordered.add(n);
            }
        }
        return ordered;
    }
}