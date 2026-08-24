package com.gewu.agent.engine.orchestration.mode;

import com.gewu.agent.engine.core.AgentExecutor;
import com.gewu.agent.engine.core.AgentTask;
import com.gewu.agent.engine.core.event.AgentEvent;
import com.gewu.agent.engine.hitl.ApprovalRequest;
import com.gewu.agent.engine.hitl.HitlGateway;
import com.gewu.agent.engine.hitl.HumanDecision;
import com.gewu.agent.engine.orchestration.ExecutionControl;
import com.gewu.agent.engine.orchestration.GraphNodeExecutor;
import com.gewu.agent.engine.orchestration.RouteConditionEvaluator;
import com.gewu.agent.engine.orchestration.model.GraphEdge;
import com.gewu.agent.engine.orchestration.model.GraphNode;
import com.gewu.agent.engine.orchestration.model.NodeType;
import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import com.gewu.agent.engine.orchestration.model.OrchestrationGraph;
import com.gewu.agent.engine.orchestration.model.OrchestrationResult;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pipeline 模式 - 边驱动的图遍历执行器（T3.1 重构）。
 * <p>除 AGENT（LLM 节点）与 HUMAN（审批节点）外，本版补齐四类结构节点：
 * <ul>
 *   <li>TOOL - 工具节点：config.toolName + arguments 模板（${var} 渲染），经安全管线执行</li>
 *   <li>ROUTER - 条件路由：按出边 condition（var:x == 'y' / contains / else）选择唯一后继</li>
 *   <li>PARALLEL - 并行扇出：所有出边目标作为分支并发执行</li>
 *   <li>MERGE - 汇聚：等待全部入边分支完成后合并产出继续</li>
 * </ul>
 * 支持协作式暂停/取消（{@link ExecutionControl} 信号在节点间检查）与断点续跑
 * （上下文变量 {@code __resumeFromNode} 指定恢复起始节点）。
 *
 * @since 1.0.0
 */
@Slf4j
public class PipelineModeHandler implements ModeHandler {

    private final AgentExecutor executor;
    private final HitlGateway hitlGateway;
    /** TOOL 节点执行器（null 时 TOOL 节点报错） */
    private final GraphNodeExecutor nodeExecutor;
    /** 执行控制（null 时暂停/取消信号不生效） */
    private final ExecutionControl executionControl;
    private final RouteConditionEvaluator routeEvaluator = new RouteConditionEvaluator();

    /** HUMAN 节点默认审批超时（秒） */
    private static final int DEFAULT_APPROVAL_TIMEOUT_SECONDS = 1800;
    /** 审批摘要最大长度 */
    private static final int SUMMARY_MAX_LENGTH = 500;

    public PipelineModeHandler(AgentExecutor executor, HitlGateway hitlGateway) {
        this(executor, hitlGateway, null, null);
    }

    public PipelineModeHandler(AgentExecutor executor, HitlGateway hitlGateway,
                               GraphNodeExecutor nodeExecutor, ExecutionControl executionControl) {
        this.executor = executor;
        this.hitlGateway = hitlGateway;
        this.nodeExecutor = nodeExecutor;
        this.executionControl = executionControl;
    }

    @Override
    public String mode() {
        return "PIPELINE";
    }

    /** 单次执行的可变遍历状态（图/上下文之外的运行时簿记） */
    private static class Walk {
        final OrchestrationGraph graph;
        final OrchestrationContext ctx;
        final reactor.core.publisher.FluxSink<AgentEvent> sink;
        final Map<String, List<GraphEdge>> outgoing;
        final Map<String, Integer> mergeExpect;          // mergeNodeId -> 期待入边数
        final Map<String, AtomicInteger> mergeArrived;   // mergeNodeId -> 已到达数
        final Map<String, Map<String, String>> mergeInputs; // mergeNodeId -> (fromNodeId, output)
        final AtomicInteger activePaths = new AtomicInteger(0);
        final AtomicReference<String> finalOutput = new AtomicReference<>("");
        boolean terminal = false;
        boolean paused = false;

        Walk(OrchestrationGraph graph, OrchestrationContext ctx,
             reactor.core.publisher.FluxSink<AgentEvent> sink) {
            this.graph = graph;
            this.ctx = ctx;
            this.sink = sink;
            this.outgoing = new LinkedHashMap<>();
            this.mergeExpect = new HashMap<>();
            this.mergeArrived = new ConcurrentHashMap<>();
            this.mergeInputs = new ConcurrentHashMap<>();
            if (graph.getEdges() != null) {
                for (GraphEdge e : graph.getEdges()) {
                    outgoing.computeIfAbsent(e.getFromNode(), k -> new ArrayList<>()).add(e);
                    mergeExpect.merge(e.getToNode(), 1, Integer::sum);
                }
            }
        }
    }

    @Override
    public Flux<AgentEvent> run(OrchestrationGraph graph, OrchestrationContext context) {
        return Flux.create(sink -> {
            sink.next(AgentEvent.builder()
                    .type(AgentEvent.GRAPH_START)
                    .metadata(Map.of("executionId", context.getExecutionId(), "mode", "PIPELINE"))
                    .build());

            List<GraphNode> nodes = graph.getNodes();
            if (nodes == null || nodes.isEmpty()) {
                completeGraph(sink, context, "SUCCESS", Map.of());
                return;
            }

            if (executionControl != null) {
                executionControl.register(context.getExecutionId());
            }

            Walk walk = new Walk(graph, context, sink);
            // 断点续跑：从恢复节点开始遍历（跳过其前序节点）
            Object resumeFrom = context.getVariable(AgentEvent.VAR_RESUME_FROM_NODE);
            GraphNode startNode = resumeFrom != null
                    ? nodes.stream().filter(n -> resumeFrom.equals(n.getNodeId())).findFirst()
                            .orElse(selectStartNode(walk, nodes))
                    : selectStartNode(walk, nodes);
            if (resumeFrom != null) {
                log.info("Pipeline 断点续跑: executionId={}, resumeFrom={}",
                        context.getExecutionId(), startNode.getNodeId());
            }
            walk.activePaths.incrementAndGet();
            visit(walk, startNode, new StringBuilder(
                    String.valueOf(context.getVariable("input") != null ? context.getVariable("input") : "")));
        });
    }

    /** 起始节点：优先无入边节点（支持乱序声明），环图回退首节点 */
    private GraphNode selectStartNode(Walk walk, List<GraphNode> nodes) {
        return nodes.stream()
                .filter(n -> !walk.mergeExpect.containsKey(n.getNodeId()))
                .findFirst()
                .orElse(nodes.get(0));
    }

    // ==================== 节点遍历 ====================

    /** 访问一个节点：按类型分派，完成后沿出边继续 */
    private void visit(Walk walk, GraphNode node, StringBuilder input) {
        // 协作式控制信号检查（节点开始前生效；当前节点执行完毕后暂停/取消）
        if (executionControl != null) {
            ExecutionControl.Signal signal = executionControl.signalOf(walk.ctx.getExecutionId());
            if (signal == ExecutionControl.Signal.PAUSE) {
                pauseHere(walk, node);
                return;
            }
            if (signal == ExecutionControl.Signal.CANCEL) {
                cancelHere(walk);
                return;
            }
        }

        walk.sink.next(AgentEvent.builder()
                .type(AgentEvent.NODE_START)
                .nodeId(node.getNodeId())
                .role(node.getRoleCode())
                .build());

        switch (node.getType() == null ? NodeType.AGENT : node.getType()) {
            case HUMAN -> handleHumanNode(walk, node, input);
            case TOOL -> executeToolNode(walk, node, input);
            case ROUTER -> executeRouter(walk, node);
            case PARALLEL -> executeParallel(walk, node, input);
            case MERGE -> arriveMerge(walk, node, node.getNodeId(), "");
            default -> executeAgentNode(walk, node, input);
        }
    }

    /** AGENT 节点：LLM 流式执行，产出累积后传递给后继 */
    private void executeAgentNode(Walk walk, GraphNode node, StringBuilder input) {
        AgentTask task = AgentTask.builder()
                .agentId(node.getRefId())
                .sessionId(walk.ctx.getSessionId())
                .userId(walk.ctx.getUserId())
                .message(input.toString())
                .build();
        StringBuilder accumulated = new StringBuilder();
        executor.executeStream(task).subscribe(
                event -> {
                    walk.sink.next(AgentEvent.builder()
                            .type(event.getType())
                            .content(event.getContent())
                            .reasoning(event.getReasoning())
                            .toolCall(event.getToolCall())
                            .toolResult(event.getToolResult())
                            .errorMessage(event.getErrorMessage())
                            .nodeId(node.getNodeId())
                            .role(node.getRoleCode())
                            .build());
                    if (AgentEvent.CONTENT.equals(event.getType()) && event.getContent() != null) {
                        accumulated.append(event.getContent());
                    }
                },
                walk.sink::error,
                () -> nodeCompleted(walk, node, accumulated.toString()));
    }

    /** TOOL 节点：模板渲染参数 -> 安全管线执行 -> 产出传递后继 */
    private void executeToolNode(Walk walk, GraphNode node, StringBuilder input) {
        String output;
        try {
            if (nodeExecutor == null) {
                throw new IllegalStateException("TOOL 节点需要 GraphNodeExecutor（未配置）: " + node.getNodeId());
            }
            output = nodeExecutor.executeToolNode(node, walk.ctx);
        } catch (Exception e) {
            log.error("TOOL 节点执行失败: {}", node.getNodeId(), e);
            failGraph(walk, "TOOL 节点执行失败: " + node.getNodeId() + " - " + e.getMessage());
            return;
        }
        nodeCompleted(walk, node, output);
    }

    /** ROUTER 节点：条件求值选择唯一后继 */
    private void executeRouter(Walk walk, GraphNode node) {
        GraphEdge selected = routeEvaluator.selectEdge(walk.outgoing.get(node.getNodeId()), walk.ctx);
        walk.sink.next(AgentEvent.builder()
                .type(AgentEvent.NODE_COMPLETE)
                .nodeId(node.getNodeId())
                .role(node.getRoleCode())
                .metadata(Map.of("routedTo", selected != null ? selected.getToNode() : ""))
                .build());
        if (selected == null) {
            failGraph(walk, "ROUTER 节点无可命中出边: " + node.getNodeId());
            return;
        }
        log.info("ROUTER 路由: nodeId={} -> {}", node.getNodeId(), selected.getToNode());
        continueTo(walk, selected.getToNode(), "");
    }

    /** PARALLEL 节点：全部出边目标作为并发分支（自身路径转移给分支后结束） */
    private void executeParallel(Walk walk, GraphNode node, StringBuilder input) {
        List<GraphEdge> branches = walk.outgoing.get(node.getNodeId());
        walk.sink.next(AgentEvent.builder()
                .type(AgentEvent.NODE_COMPLETE)
                .nodeId(node.getNodeId())
                .role(node.getRoleCode())
                .metadata(Map.of("branches", branches != null ? branches.size() : 0))
                .build());
        if (branches == null || branches.isEmpty()) {
            pathEnded(walk, "");
            return;
        }
        for (GraphEdge branch : branches) {
            walk.activePaths.incrementAndGet();
            visit(walk, nodeById(walk, branch.getToNode()), new StringBuilder(input));
        }
        // 扇出节点自身路径已转移给各分支，此处结束（否则 activePaths 永不归零）
        pathEnded(walk, "");
    }

    /** MERGE 到达：记录分支产出，全部到齐后合并并继续 */
    private void arriveMerge(Walk walk, GraphNode mergeNode, String fromNodeId, String output) {
        if (fromNodeId != null && !fromNodeId.isBlank()) {
            walk.mergeInputs.computeIfAbsent(mergeNode.getNodeId(), k -> new ConcurrentHashMap<>())
                    .put(fromNodeId, output != null ? output : "");
        }
        AtomicInteger arrived = walk.mergeArrived.computeIfAbsent(mergeNode.getNodeId(), k -> new AtomicInteger(0));
        int expected = walk.mergeExpect.getOrDefault(mergeNode.getNodeId(), 1);
        if (arrived.incrementAndGet() < expected) {
            // 仍有分支未到达，本路径结束等待
            pathEnded(walk, output);
            return;
        }
        // 全部分支到齐：合并产出（默认拼接；config.strategy=json_merge 时 JSON 合并）
        Map<String, String> inputs = walk.mergeInputs.getOrDefault(mergeNode.getNodeId(), Map.of());
        String merged = mergeOutputs(mergeNode, inputs);
        walk.sink.next(AgentEvent.builder()
                .type(AgentEvent.NODE_COMPLETE)
                .nodeId(mergeNode.getNodeId())
                .role(mergeNode.getRoleCode())
                .metadata(Map.of("mergedFrom", inputs.size()))
                .build());
        continueTo(walk, firstOutgoing(walk, mergeNode.getNodeId()), merged);
    }

    /** 节点完成：发事件并沿唯一出边继续（多出边取第一条；无出边则路径结束） */
    private void nodeCompleted(Walk walk, GraphNode node, String output) {
        walk.sink.next(AgentEvent.builder()
                .type(AgentEvent.NODE_COMPLETE)
                .nodeId(node.getNodeId())
                .role(node.getRoleCode())
                .metadata(Map.of("outputLen", output != null ? output.length() : 0))
                .build());
        walk.ctx.putVariable(node.getNodeId(), output != null ? output : "");
        String next = firstOutgoing(walk, node.getNodeId());
        if (next == null) {
            pathEnded(walk, output);
            return;
        }
        GraphNode nextNode = nodeById(walk, next);
        // 分支汇入 MERGE：产出作为 MERGE 入参
        if (nextNode.getType() == NodeType.MERGE) {
            arriveMerge(walk, nextNode, node.getNodeId(), output);
            return;
        }
        continueTo(walk, next, output);
    }

    private void continueTo(Walk walk, String nodeId, String output) {
        GraphNode next = nodeById(walk, nodeId);
        if (next == null) {
            log.warn("出边指向不存在的节点: {}", nodeId);
            pathEnded(walk, output);
            return;
        }
        visit(walk, next, new StringBuilder(output != null ? output : ""));
    }

    /** 路径结束：全部路径结束后图完成 */
    private void pathEnded(Walk walk, String output) {
        if (output != null && !output.isBlank()) {
            walk.finalOutput.set(output);
        }
        if (walk.activePaths.decrementAndGet() == 0 && !walk.terminal) {
            if (walk.paused) {
                return; // 暂停时由 pauseHere 负责收尾
            }
            completeGraph(walk.sink, walk.ctx, "SUCCESS",
                    Map.of("output", walk.finalOutput.get()));
            if (executionControl != null) {
                executionControl.unregister(walk.ctx.getExecutionId());
            }
        }
    }

    // ==================== HUMAN 审批节点 ====================

    private void handleHumanNode(Walk walk, GraphNode node, StringBuilder accumulated) {
        String summary = accumulated.length() > 0
                ? accumulated.substring(0, Math.min(SUMMARY_MAX_LENGTH, accumulated.length()))
                : "人工审核节点（无前置产出）";

        int timeoutSeconds = DEFAULT_APPROVAL_TIMEOUT_SECONDS;
        if (node.getConfig() != null && node.getConfig().get("timeoutSeconds") instanceof Number n) {
            timeoutSeconds = n.intValue();
        }

        ApprovalRequest request = ApprovalRequest.builder()
                .approvalId(UUID.randomUUID().toString())
                .executionId(walk.ctx.getExecutionId())
                .nodeId(node.getNodeId())
                .type("APPROVE_REJECT")
                .summary(summary)
                .timeoutSeconds(timeoutSeconds)
                .build();

        walk.sink.next(AgentEvent.builder()
                .type(AgentEvent.APPROVAL_REQUIRED)
                .nodeId(node.getNodeId())
                .metadata(Map.of("approvalId", request.getApprovalId(),
                        "timeoutSeconds", timeoutSeconds))
                .build());

        hitlGateway.requestApproval(request).subscribe(
                decision -> {
                    boolean approved = "APPROVED".equals(decision.getDecision());
                    walk.sink.next(AgentEvent.builder()
                            .type(AgentEvent.APPROVAL_RESULT)
                            .nodeId(node.getNodeId())
                            .metadata(Map.of("approved", approved,
                                    "operator", decision.getOperatorId() != null ? decision.getOperatorId() : "",
                                    "comment", decision.getValue() != null ? decision.getValue() : ""))
                            .build());
                    walk.sink.next(AgentEvent.builder()
                            .type(AgentEvent.NODE_COMPLETE)
                            .nodeId(node.getNodeId())
                            .role(node.getRoleCode())
                            .metadata(Map.of("approval", approved ? "APPROVED" : "REJECTED"))
                            .build());
                    if (approved) {
                        String output = accumulated.toString();
                        if (decision.getValue() != null && !decision.getValue().isBlank()) {
                            output = output + "\n\n[人工审批意见] " + decision.getValue();
                        }
                        String next = firstOutgoing(walk, node.getNodeId());
                        if (next == null) {
                            pathEnded(walk, output);
                            return;
                        }
                        GraphNode nextNode = nodeById(walk, next);
                        if (nextNode.getType() == NodeType.MERGE) {
                            arriveMerge(walk, nextNode, node.getNodeId(), output);
                            return;
                        }
                        continueTo(walk, next, output);
                    } else {
                        boolean rolledBack = walk.ctx.rollbackOneVersion();
                        log.info("人工驳回回滚: executionId={}, nodeId={}, rolledBack={}, stateVersion={}",
                                walk.ctx.getExecutionId(), node.getNodeId(), rolledBack, walk.ctx.getStateVersion());
                        failGraph(walk, "人工审批被驳回: "
                                + (decision.getValue() != null ? decision.getValue() : ""));
                    }
                },
                error -> failGraph(walk, "审批流程异常: " + error.getMessage()));
    }

    // ==================== 暂停/取消/终止 ====================

    /** 暂停生效：保存检查点并以 PAUSED 状态优雅收尾（保留注册表条目供 resume） */
    private void pauseHere(Walk walk, GraphNode nextNode) {
        walk.terminal = true;
        walk.paused = true;
        walk.activePaths.decrementAndGet();
        if (executionControl != null) {
            executionControl.saveCheckpoint(walk.ctx.getExecutionId(),
                    new ExecutionControl.Checkpoint(walk.graph, walk.ctx, nextNode.getNodeId()));
            // 信号已消费：检查点即为暂停态的唯一事实源，避免恢复后残留 PAUSE 信号
            executionControl.clearSignal(walk.ctx.getExecutionId());
        }
        walk.sink.next(AgentEvent.builder()
                .type(AgentEvent.EXECUTION_PAUSED)
                .nodeId(nextNode.getNodeId())
                .metadata(Map.of("resumeFromNode", nextNode.getNodeId()))
                .build());
        completeGraph(walk.sink, walk.ctx, "PAUSED",
                Map.of("resumeFromNode", nextNode.getNodeId(),
                        "variables", walk.ctx.snapshotVariables()));
    }

    /** 取消生效：优雅收尾并注销 */
    private void cancelHere(Walk walk) {
        walk.terminal = true;
        walk.activePaths.decrementAndGet();
        walk.sink.next(AgentEvent.builder()
                .type(AgentEvent.EXECUTION_CANCELLED)
                .metadata(Map.of("stateVersion", walk.ctx.getStateVersion()))
                .build());
        completeGraph(walk.sink, walk.ctx, "CANCELLED",
                Map.of("stateVersion", walk.ctx.getStateVersion(),
                        "stateKeys", walk.ctx.snapshotVariables().keySet().size()));
        if (executionControl != null) {
            executionControl.unregister(walk.ctx.getExecutionId());
        }
    }

    private void failGraph(Walk walk, String reason) {
        if (walk.terminal) {
            return;
        }
        walk.terminal = true;
        completeGraph(walk.sink, walk.ctx, "FAILED", Map.of("reason", reason));
        if (executionControl != null) {
            executionControl.unregister(walk.ctx.getExecutionId());
        }
    }

    private void completeGraph(reactor.core.publisher.FluxSink<AgentEvent> sink,
                               OrchestrationContext ctx, String status, Map<String, Object> extra) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("status", status);
        metadata.putAll(extra);
        sink.next(AgentEvent.builder()
                .type(AgentEvent.GRAPH_COMPLETE)
                .metadata(metadata)
                .build());
        sink.complete();
    }

    // ==================== 辅助 ====================

    private GraphNode nodeById(Walk walk, String nodeId) {
        return walk.graph.getNodes().stream()
                .filter(n -> nodeId.equals(n.getNodeId()))
                .findFirst().orElse(null);
    }

    private String firstOutgoing(Walk walk, String nodeId) {
        List<GraphEdge> edges = walk.outgoing.get(nodeId);
        return edges != null && !edges.isEmpty() ? edges.get(0).getToNode() : null;
    }

    /** MERGE 产出合并：默认按入边顺序拼接；config.strategy=json_merge 时合并 JSON 对象字段 */
    private String mergeOutputs(GraphNode mergeNode, Map<String, String> inputs) {
        Object strategy = mergeNode.getConfig() != null ? mergeNode.getConfig().get("strategy") : null;
        if ("json_merge".equals(strategy)) {
            Map<String, Object> merged = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : inputs.entrySet()) {
                try {
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(e.getValue() != null && !e.getValue().isBlank()
                                    ? e.getValue() : "{}", Map.class)
                            .forEach((k, v) -> merged.put(String.valueOf(k), v));
                } catch (Exception ex) {
                    merged.put(e.getKey(), e.getValue());
                }
            }
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(merged);
            } catch (Exception ex) {
                return String.join("\n\n", inputs.values());
            }
        }
        return String.join("\n\n", inputs.values());
    }

    /** 同步执行（ModeHandler 默认约定：阻塞收集流式结果） */
    @Override
    public OrchestrationResult runSync(OrchestrationGraph graph, OrchestrationContext context) {
        List<AgentEvent> events = new ArrayList<>();
        try {
            run(graph, context).collectList().block().forEach(events::add);
        } catch (Exception e) {
            return OrchestrationResult.failure(context.getExecutionId(), e.getMessage());
        }
        String status = "SUCCESS";
        String output = "";
        for (AgentEvent event : events) {
            if (AgentEvent.GRAPH_COMPLETE.equals(event.getType())) {
                Object s = event.getMetadata() != null ? event.getMetadata().get("status") : null;
                status = s != null ? String.valueOf(s) : status;
                output = event.getMetadata() != null && event.getMetadata().get("output") != null
                        ? String.valueOf(event.getMetadata().get("output")) : output;
            }
        }
        if ("SUCCESS".equals(status)) {
            return OrchestrationResult.success(context.getExecutionId(), output);
        }
        return OrchestrationResult.failure(context.getExecutionId(), output);
    }

    /** 兼容旧调用：按节点声明顺序返回（图遍历已改为边驱动，此方法仅供调试） */
    @SuppressWarnings("unused")
    private List<GraphNode> topoOrder(OrchestrationGraph graph) {
        var nodes = graph.getNodes();
        var edges = graph.getEdges();
        if (edges == null || edges.isEmpty()) {
            return nodes;
        }
        Map<String, GraphNode> byId = new LinkedHashMap<>();
        for (var n : nodes) {
            byId.put(n.getNodeId(), n);
        }
        Map<String, String> next = new HashMap<>();
        Set<String> targets = new HashSet<>();
        for (var e : edges) {
            next.put(e.getFromNode(), e.getToNode());
            targets.add(e.getToNode());
        }
        String start = next.keySet().stream().filter(k -> !targets.contains(k)).findFirst()
                .orElse(nodes.isEmpty() ? null : nodes.get(0).getNodeId());
        var ordered = new ArrayList<GraphNode>();
        String cur = start;
        Set<String> visited = new HashSet<>();
        while (cur != null && visited.add(cur)) {
            var n = byId.get(cur);
            if (n != null) {
                ordered.add(n);
            }
            cur = next.get(cur);
        }
        for (var n : nodes) {
            if (!visited.contains(n.getNodeId())) {
                ordered.add(n);
            }
        }
        return ordered;
    }
}
