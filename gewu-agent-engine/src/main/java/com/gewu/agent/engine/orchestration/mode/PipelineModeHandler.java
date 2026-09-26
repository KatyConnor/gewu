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
    /** 目标分解器（null 时 PLAN 节点报错；PLAN 节点动态规划为并行波次子图内联执行） */
    private final com.gewu.agent.engine.orchestration.GoalPlanner goalPlanner;
    /** 子图解析器（null 或开关关闭时 SUBGRAPH 节点退回按 AGENT 执行） */
    private final com.gewu.agent.engine.orchestration.SubgraphResolver subgraphResolver;
    /** SUBGRAPH 特性开关（WFO-04：agent.engine.orchestration.subgraph.enabled，默认关闭） */
    private final boolean subgraphEnabled;
    private final RouteConditionEvaluator routeEvaluator = new RouteConditionEvaluator();

    /** HUMAN 节点默认审批超时（秒） */
    private static final int DEFAULT_APPROVAL_TIMEOUT_SECONDS = 1800;
    /** 审批摘要最大长度 */
    private static final int SUMMARY_MAX_LENGTH = 500;
    /** PLAN 节点嵌套深度上限（子图内不再允许再嵌 PLAN，防递归失控） */
    private static final int MAX_PLAN_DEPTH = 1;
    /** SUBGRAPH 嵌套深度上限（WFO-04，防跨图环失控；静态环检测由 VL-13 兜底） */
    private static final int MAX_SUBGRAPH_DEPTH = 2;
    /** 节点级重试次数上限（WFO-05） */
    private static final int MAX_NODE_RETRY_COUNT = 3;
    /** 节点级重试默认退避（毫秒，WFO-05） */
    private static final int DEFAULT_RETRY_BACKOFF_MS = 1000;
    /** 节点级超时上限（秒，WFO-05） */
    private static final long MAX_NODE_TIMEOUT_SECONDS = 86400L;
    /**
     * TOOL 节点超时执行专用池（WFO-05）：有界队列 + CallerRunsPolicy（CR-022 模式），
     * 不占用 commonPool；队列满时退化为提交线程同步执行（超时保护降级，可接受）。
     */
    private static final java.util.concurrent.ExecutorService TOOL_TIMEOUT_EXECUTOR =
            new java.util.concurrent.ThreadPoolExecutor(1, 4, 60L, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(32),
                    runnable -> {
                        Thread thread = new Thread(runnable, "orch-tool-timeout");
                        thread.setDaemon(true);
                        return thread;
                    },
                    new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());

    public PipelineModeHandler(AgentExecutor executor, HitlGateway hitlGateway) {
        this(executor, hitlGateway, null, null, null);
    }

    public PipelineModeHandler(AgentExecutor executor, HitlGateway hitlGateway,
                               GraphNodeExecutor nodeExecutor, ExecutionControl executionControl) {
        this(executor, hitlGateway, nodeExecutor, executionControl, null);
    }

    public PipelineModeHandler(AgentExecutor executor, HitlGateway hitlGateway,
                               GraphNodeExecutor nodeExecutor, ExecutionControl executionControl,
                               com.gewu.agent.engine.orchestration.GoalPlanner goalPlanner) {
        this(executor, hitlGateway, nodeExecutor, executionControl, goalPlanner, null, false);
    }

    public PipelineModeHandler(AgentExecutor executor, HitlGateway hitlGateway,
                               GraphNodeExecutor nodeExecutor, ExecutionControl executionControl,
                               com.gewu.agent.engine.orchestration.GoalPlanner goalPlanner,
                               com.gewu.agent.engine.orchestration.SubgraphResolver subgraphResolver,
                               boolean subgraphEnabled) {
        this.executor = executor;
        this.hitlGateway = hitlGateway;
        this.nodeExecutor = nodeExecutor;
        this.executionControl = executionControl;
        this.goalPlanner = goalPlanner;
        this.subgraphResolver = subgraphResolver;
        this.subgraphEnabled = subgraphEnabled;
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
        /** PLAN 子图遍历：全部路径结束后回调宿主（父 Walk 继续 PLAN 节点后继）；null=正常收尾 */
        Runnable completionCallback;
        /** PLAN 节点嵌套深度（子图继承父深度+1，达到上限拒绝再嵌） */
        int planDepth;
        /** SUBGRAPH 嵌套深度（WFO-04，子图继承父深度+1，达到上限拒绝再嵌） */
        int subgraphDepth;
        /** 失败节点登记（nodeId → 原因）：终态判定与 best-effort 级联防重 */
        final Map<String, String> failedNodes = new LinkedHashMap<>();
        /** 节点重试计数（nodeId → 成功前的额外尝试次数，WFO-05，随 node_complete 事件透出） */
        final Map<String, Integer> nodeRetries = new ConcurrentHashMap<>();
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
        if (walk.terminal) {
            return; // 图已失败/完成，短路后续分派（失败传播守卫）
        }
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
            case ROUTER -> executeRouter(walk, node, input);
            case PARALLEL -> executeParallel(walk, node, input);
            case MERGE -> arriveMerge(walk, node, node.getNodeId(), "");
            case PLAN -> executePlanNode(walk, node, input);
            case SUBGRAPH -> executeSubgraphNode(walk, node, input);
            default -> executeAgentNode(walk, node, input);
        }
    }

    /**
     * SUBGRAPH 嵌套子图节点（WFO-04）。
     * <p>特性开关关闭时保持历史行为（按 AGENT 节点执行）。开启后：
     * refId 经 {@link com.gewu.agent.engine.orchestration.SubgraphResolver} 加载可执行子图
     * （active、优先版本快照），以父上下文变量快照为初始变量沙箱执行（子图内部变量写入
     * 不回渗父图），子图最终产出作为本节点输出继续父图遍历；嵌套深度上限 2，
     * 断点续跑从 SUBGRAPH 节点恢复时子图整体重跑（子图内部无断点语义）。
     */
    private void executeSubgraphNode(Walk walk, GraphNode node, StringBuilder input) {
        if (!subgraphEnabled) {
            executeAgentNode(walk, node, input);
            return;
        }
        if (subgraphResolver == null) {
            handleNodeFailure(walk, node, "SUBGRAPH 节点需要 SubgraphResolver（未配置）: " + node.getNodeId());
            return;
        }
        if (walk.subgraphDepth >= MAX_SUBGRAPH_DEPTH) {
            handleNodeFailure(walk, node, "子图嵌套深度超限（上限 " + MAX_SUBGRAPH_DEPTH + "）: " + node.getNodeId());
            return;
        }
        String subGraphId = node.getRefId();
        if (subGraphId == null || subGraphId.isBlank()) {
            handleNodeFailure(walk, node, "SUBGRAPH 节点缺少 refId: " + node.getNodeId());
            return;
        }
        OrchestrationGraph subGraph;
        try {
            subGraph = subgraphResolver.resolve(subGraphId);
        } catch (Exception e) {
            log.error("加载子图失败: refId={}", subGraphId, e);
            handleNodeFailure(walk, node, "加载子图失败 " + subGraphId + ": " + e.getMessage());
            return;
        }
        if (subGraph == null) {
            handleNodeFailure(walk, node, "子图不存在或不可执行（需已激活）: " + subGraphId);
            return;
        }
        String goalText = input != null ? input.toString() : "";
        // 沙箱上下文：子图自身 variables 为基础（如子图级模型兜底配置），
        // 父上下文变量快照覆盖同名键（父传参优先），子图内部新写入不回渗父图
        OrchestrationContext subCtx = OrchestrationContext.builder()
                .executionId(walk.ctx.getExecutionId())
                .graphId(subGraph.getGraphId())
                .userId(walk.ctx.getUserId())
                .sessionId(walk.ctx.getSessionId())
                .currentNodeId(walk.ctx.getCurrentNodeId())
                .build();
        Map<String, Object> subVars = new HashMap<>();
        if (subGraph.getVariables() != null) {
            subVars.putAll(subGraph.getVariables());
        }
        subVars.putAll(walk.ctx.snapshotVariables());
        subCtx.setVariables(subVars);
        subCtx.putVariable("input", goalText);
        Walk subWalk = new Walk(subGraph, subCtx, walk.sink);
        subWalk.subgraphDepth = walk.subgraphDepth + 1;
        subWalk.completionCallback = () -> {
            // 子图收尾：最终产出写回 SUBGRAPH 节点变量并沿父图出边继续
            nodeCompleted(walk, node, subWalk.finalOutput.get());
        };
        List<GraphNode> subNodes = subGraph.getNodes();
        if (subNodes == null || subNodes.isEmpty()) {
            nodeCompleted(walk, node, "");
            return;
        }
        log.info("SUBGRAPH 节点执行: nodeId={}, subGraphId={}, depth={}",
                node.getNodeId(), subGraphId, subWalk.subgraphDepth);
        GraphNode subStart = selectStartNode(subWalk, subNodes);
        subWalk.activePaths.incrementAndGet();
        visit(subWalk, subStart, new StringBuilder(goalText));
    }

    /** AGENT 节点：LLM 流式执行，产出累积后传递给后继 */
    private void executeAgentNode(Walk walk, GraphNode node, StringBuilder input) {
        // inputs 变量模板（B-3）：约定键 message 覆盖前驱输出，其余键以参考段追加；
        // 由此 PLAN/MERGE 后的节点可分别引用任意上游节点产出（变量 key=节点 ID）
        String message = input != null ? input.toString() : "";
        if (node.getInputs() != null && !node.getInputs().isEmpty()) {
            Map<String, String> extra = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : node.getInputs().entrySet()) {
                String rendered = com.gewu.agent.engine.orchestration.VariableTemplates.render(
                        entry.getValue() != null ? String.valueOf(entry.getValue()) : "", walk.ctx);
                if ("message".equals(entry.getKey())) {
                    message = rendered;
                } else {
                    extra.put(entry.getKey(), rendered);
                }
            }
            if (!extra.isEmpty()) {
                StringBuilder sb = new StringBuilder(message);
                for (Map.Entry<String, String> e : extra.entrySet()) {
                    sb.append("\n\n## 参考：").append(e.getKey()).append("\n").append(e.getValue());
                }
                message = sb.toString();
            }
        }
        AgentTask.AgentTaskBuilder taskBuilder = AgentTask.builder()
                .agentId(node.getRefId())
                .sessionId(walk.ctx.getSessionId())
                .userId(walk.ctx.getUserId())
                .message(message);
        // 模型回退：节点未绑定 Agent（refId 为空）时，允许图变量 modelProvider/modelName 兜底解析，
        // 使 GoalPlanner 动态生成的图无需预置 Agent 配置即可执行
        if ((node.getRefId() == null || node.getRefId().isBlank())) {
            Object provider = walk.ctx.getVariable("modelProvider");
            Object modelName = walk.ctx.getVariable("modelName");
            if (provider != null && modelName != null) {
                taskBuilder.modelProvider(String.valueOf(provider))
                        .modelName(String.valueOf(modelName));
            }
        }
        AgentTask task = taskBuilder.build();
        executeAgentStream(walk, node, task, 0);
    }

    /**
     * AGENT 节点流式执行（WFO-05 节点级超时与重试）。
     * <p>超时：config.timeoutSeconds > 0 时对流式执行加超时上限（缺省 0 不启用，
     * 由 LLM 客户端级超时兜底）；重试：仅对流异常/超时重试（config.retryCount，上限 3），
     * 执行器转发的 error 事件属业务失败、有副作用风险，不重试。
     */
    private void executeAgentStream(Walk walk, GraphNode node, AgentTask task, int attempt) {
        if (walk.terminal) {
            return; // 重试等待期间图已终止，丢弃本次执行
        }
        long timeoutSeconds = nodeTimeoutSeconds(node);
        Flux<AgentEvent> stream = executor.executeStream(task);
        if (timeoutSeconds > 0) {
            stream = stream.timeout(java.time.Duration.ofSeconds(timeoutSeconds));
        }
        StringBuilder accumulated = new StringBuilder();
        // 推理模型（如 glm-5.3-flash）的输出主要是 reasoning_content：
        // content 为空时以最后的推理内容兜底作为节点产出，避免下游节点空输入（docs/design/47 问题一延伸）
        StringBuilder lastReasoning = new StringBuilder();
        stream.subscribe(
                event -> {
                    // 失败传播（docs/design/47 问题一）：执行器把异常转为 error 事件而非 Flux error，
                    // 必须在此识别，否则失败被伪装成成功继续推进（冒烟实证）
                    if (AgentEvent.ERROR.equals(event.getType())) {
                        handleNodeFailure(walk, node, event.getErrorMessage());
                        return;
                    }
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
                    if (AgentEvent.THINKING.equals(event.getType()) && event.getReasoning() != null) {
                        lastReasoning.setLength(0);
                        lastReasoning.append(event.getReasoning());
                    }
                },
                error -> {
                    if (attempt < nodeRetryCount(node) && !walk.terminal) {
                        scheduleAgentRetry(walk, node, task, attempt, describeStreamError(error, timeoutSeconds));
                        return;
                    }
                    handleNodeFailure(walk, node, describeStreamError(error, timeoutSeconds));
                },
                () -> {
                    String output = accumulated.toString();
                    if (output.isBlank() && lastReasoning.length() > 0) {
                        output = lastReasoning.toString();
                    }
                    nodeCompleted(walk, node, output);
                });
    }

    /** AGENT 流式执行的非阻塞退避重试（WFO-05） */
    private void scheduleAgentRetry(Walk walk, GraphNode node, AgentTask task, int attempt, String cause) {
        walk.nodeRetries.merge(node.getNodeId(), 1, Integer::sum);
        int backoffMs = Math.max(0, intConfig(node, "retryBackoffMs", DEFAULT_RETRY_BACKOFF_MS));
        int nextAttempt = attempt + 1;
        log.warn("AGENT 节点执行失败，准备第 {} 次重试: nodeId={}, backoffMs={}, cause={}",
                nextAttempt, node.getNodeId(), backoffMs, cause);
        if (backoffMs > 0) {
            reactor.core.publisher.Mono.delay(java.time.Duration.ofMillis(backoffMs))
                    .subscribe(ignored -> executeAgentStream(walk, node, task, nextAttempt));
        } else {
            executeAgentStream(walk, node, task, nextAttempt);
        }
    }

    /** 流异常可读描述（区分节点级超时与其他异常） */
    private String describeStreamError(Throwable error, long timeoutSeconds) {
        if (error instanceof java.util.concurrent.TimeoutException) {
            return "节点执行超时（" + timeoutSeconds + "s）";
        }
        return error.getMessage();
    }

    /** 节点配置读取：数字键（非法值回退默认） */
    private int intConfig(GraphNode node, String key, int defaultValue) {
        if (node.getConfig() == null) {
            return defaultValue;
        }
        Object value = node.getConfig().get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                // 非数字配置按缺省处理
            }
        }
        return defaultValue;
    }

    /** 节点级重试次数（WFO-05，上限 3；缺省 0 = 保持 fail-fast 现行为） */
    private int nodeRetryCount(GraphNode node) {
        return Math.min(Math.max(intConfig(node, "retryCount", 0), 0), MAX_NODE_RETRY_COUNT);
    }

    /** 节点级超时秒数（WFO-05，[1, 86400]；缺省/非正数 = 不启用，由引擎级超时兜底） */
    private long nodeTimeoutSeconds(GraphNode node) {
        long value = intConfig(node, "timeoutSeconds", 0);
        if (value <= 0) {
            return 0;
        }
        return Math.min(value, MAX_NODE_TIMEOUT_SECONDS);
    }

    /**
     * 节点失败统一处理（docs/design/47 问题一 + WFO-05 泛化，AGENT/TOOL 共用）。
     * <p>默认 fail-fast：整图 FAILED 终止，与 TOOL/PLAN/HUMAN 失败语义对齐；
     * 图变量或节点 config 设 {@code continueOnFailure=true} 时转为 best-effort：
     * 失败节点不发 node_complete、不写产出，向下游传播失败占位（MERGE 到账空产出、
     * 非 MERGE 后继级联跳过），整图终态仍如实判 FAILED。
     */
    private void handleNodeFailure(Walk walk, GraphNode node, String errorMessage) {
        if (walk.terminal || walk.failedNodes.containsKey(node.getNodeId())) {
            return;
        }
        String reason = node.getNodeId() + " 执行失败: "
                + (errorMessage == null || errorMessage.isBlank() ? "未知错误" : errorMessage);
        walk.failedNodes.put(node.getNodeId(), reason);
        // 透传 error 事件（带 nodeId）：前端据此把失败节点标红
        walk.sink.next(AgentEvent.builder()
                .type(AgentEvent.ERROR)
                .errorMessage(errorMessage)
                .nodeId(node.getNodeId())
                .role(node.getRoleCode())
                .build());
        if (!isContinueOnFailure(walk, node)) {
            failGraph(walk, reason);
            return;
        }
        log.warn("节点失败（best-effort 继续）: {}", reason);
        propagateFailure(walk, node, reason, new java.util.HashSet<>());
    }

    /** 失败传播：出边目标为 MERGE 则到账空产出占位；非 MERGE 后继级联跳过；原路径计数转移后归还 */
    private void propagateFailure(Walk walk, GraphNode node, String reason, java.util.Set<String> visited) {
        visited.add(node.getNodeId());
        List<GraphEdge> edges = walk.outgoing.get(node.getNodeId());
        if (edges == null || edges.isEmpty()) {
            pathEnded(walk, "");
            return;
        }
        for (GraphEdge edge : edges) {
            GraphNode target = nodeById(walk, edge.getToNode());
            walk.activePaths.incrementAndGet();
            if (target == null) {
                log.warn("失败传播出边指向不存在的节点: {}", edge.getToNode());
                pathEnded(walk, "");
                continue;
            }
            if (target.getType() == NodeType.MERGE) {
                arriveMerge(walk, target, node.getNodeId(), "");
                continue;
            }
            if (!walk.failedNodes.containsKey(target.getNodeId()) && visited.add(target.getNodeId())) {
                walk.sink.next(AgentEvent.builder()
                        .type(AgentEvent.ERROR)
                        .nodeId(target.getNodeId())
                        .role(target.getRoleCode())
                        .errorMessage("因上游 " + node.getNodeId() + " 失败跳过执行")
                        .build());
                walk.failedNodes.put(target.getNodeId(),
                        target.getNodeId() + " 因上游 " + node.getNodeId() + " 失败跳过");
            }
            propagateFailure(walk, target, reason, visited);
        }
        // 原失败节点路径计数已转移给各传播分支，归还自身
        pathEnded(walk, "");
    }

    /** 失败语义开关：节点 config 优先，其次图变量（variables 已并入执行上下文），默认 fail-fast */
    private boolean isContinueOnFailure(Walk walk, GraphNode node) {
        if (node.getConfig() != null
                && Boolean.parseBoolean(String.valueOf(node.getConfig().get("continueOnFailure")))) {
            return true;
        }
        Object flag = walk.ctx.getVariable("continueOnFailure");
        return flag != null && Boolean.parseBoolean(String.valueOf(flag));
    }

    /**
     * PLAN 节点（"汇总→规划→派发实施"闭环核心）：输入（通常是 MERGE 汇总产出）
     * 经 {@link com.gewu.agent.engine.orchestration.GoalPlanner} 动态分解为计划图，
     * 经 {@link com.gewu.agent.engine.orchestration.model.ExecutionGraph#fromPlanGraph}
     * 映射为并行波次子图，在当前上下文上内联执行；子图最终产出作为本节点输出继续父图遍历。
     */
    private void executePlanNode(Walk walk, GraphNode node, StringBuilder input) {
        if (goalPlanner == null) {
            failGraph(walk, "PLAN 节点需要 GoalPlanner（未配置）: " + node.getNodeId());
            return;
        }
        if (walk.planDepth >= MAX_PLAN_DEPTH) {
            failGraph(walk, "PLAN 节点嵌套深度超限（上限 " + MAX_PLAN_DEPTH + "）: " + node.getNodeId());
            return;
        }
        String goalText = input != null ? input.toString() : "";
        Object goalType = node.getConfig() != null ? node.getConfig().get("goalType") : null;
        com.gewu.agent.engine.orchestration.model.AutonomousGoal goal =
                com.gewu.agent.engine.orchestration.model.AutonomousGoal.builder()
                        .goalId("plan-" + walk.ctx.getExecutionId() + "-" + node.getNodeId())
                        .description(goalText)
                        .type(goalType != null ? String.valueOf(goalType) : "FEATURE")
                        .build();
        com.gewu.agent.engine.orchestration.model.PlanGraph plan;
        try {
            plan = goalPlanner.plan(goal, walk.ctx);
        } catch (Exception e) {
            log.error("PLAN 节点规划失败: {}", node.getNodeId(), e);
            failGraph(walk, "PLAN 节点规划失败: " + node.getNodeId() + " - " + e.getMessage());
            return;
        }
        // 计划可见性：发 PLAN_CREATED（步骤清单随事件透出，前端 PlanCard 可渲染）
        List<AgentEvent.PlanStepInfo> planSteps = new ArrayList<>();
        if (plan.getSteps() != null) {
            for (var step : plan.getSteps()) {
                planSteps.add(AgentEvent.PlanStepInfo.builder()
                        .id(step.getStepId())
                        .text(step.getDescription())
                        .status("pending")
                        .build());
            }
        }
        walk.sink.next(AgentEvent.builder()
                .type(AgentEvent.PLAN_CREATED)
                .nodeId(node.getNodeId())
                .planTitle(plan.getGoal() != null ? plan.getGoal() : "PLAN 节点计划")
                .plan(planSteps)
                .build());

        // 计划图 -> 并行波次执行图 -> 子图内联执行（复用 PIPELINE 全部节点能力）
        var execGraph = com.gewu.agent.engine.orchestration.model.ExecutionGraph.fromPlanGraph(plan, null);
        OrchestrationGraph subGraph = OrchestrationGraph.builder()
                .graphId(execGraph.getExecutionGraphId())
                .name("plan-" + node.getNodeId())
                .type(com.gewu.agent.engine.orchestration.model.GraphType.GOAL_DECOMPOSED)
                .mode(com.gewu.agent.engine.orchestration.model.OrchestrationMode.PIPELINE)
                .nodes(execGraph.getNodes())
                .edges(execGraph.getEdges())
                .variables(walk.ctx.getVariables())
                .build();
        Walk subWalk = new Walk(subGraph, walk.ctx, walk.sink);
        subWalk.planDepth = walk.planDepth + 1;
        subWalk.completionCallback = () -> {
            // 子图收尾：最终产出写回 PLAN 节点变量并沿父图出边继续
            nodeCompleted(walk, node, subWalk.finalOutput.get());
        };
        List<GraphNode> subNodes = subGraph.getNodes();
        GraphNode subStart = selectStartNode(subWalk, subNodes);
        subWalk.activePaths.incrementAndGet();
        visit(subWalk, subStart, new StringBuilder(goalText));
    }

    /**
     * TOOL 节点：模板渲染参数 -> 安全管线执行 -> 产出传递后继。
     * <p>WFO-05：支持节点级重试（retryCount，仅对执行异常重试）与超时
     * （timeoutSeconds，缺省不启用、由工具执行管线内部超时兜底）；
     * 重试耗尽后走与 AGENT 一致的失败语义（fail-fast / continueOnFailure）。
     */
    private void executeToolNode(Walk walk, GraphNode node, StringBuilder input) {
        if (nodeExecutor == null) {
            handleNodeFailure(walk, node, "TOOL 节点需要 GraphNodeExecutor（未配置）: " + node.getNodeId());
            return;
        }
        int retryCount = nodeRetryCount(node);
        int attempt = 0;
        String lastError = null;
        while (true) {
            try {
                String output = executeToolWithTimeout(node, walk.ctx);
                nodeCompleted(walk, node, output);
                return;
            } catch (Exception e) {
                lastError = e.getMessage();
                if (walk.terminal) {
                    return; // 重试期间图已终止
                }
                if (attempt < retryCount) {
                    attempt++;
                    walk.nodeRetries.merge(node.getNodeId(), 1, Integer::sum);
                    int backoffMs = Math.max(0, intConfig(node, "retryBackoffMs", DEFAULT_RETRY_BACKOFF_MS));
                    log.warn("TOOL 节点执行失败，第 {} 次重试: nodeId={}, backoffMs={}, cause={}",
                            attempt, node.getNodeId(), backoffMs, lastError);
                    if (backoffMs > 0) {
                        try {
                            Thread.sleep(backoffMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    continue;
                }
                break;
            }
        }
        log.error("TOOL 节点执行失败: {}", node.getNodeId());
        handleNodeFailure(walk, node, "TOOL 节点执行失败: " + node.getNodeId() + " - " + lastError);
    }

    /** 工具调用包节点级超时（WFO-05）：同步等待上限，超时尽力中断底层任务 */
    private String executeToolWithTimeout(GraphNode node, OrchestrationContext ctx) throws Exception {
        long timeoutSeconds = nodeTimeoutSeconds(node);
        if (timeoutSeconds <= 0) {
            return nodeExecutor.executeToolNode(node, ctx);
        }
        java.util.concurrent.CompletableFuture<String> future =
                java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> nodeExecutor.executeToolNode(node, ctx), TOOL_TIMEOUT_EXECUTOR);
        try {
            return future.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("节点执行超时（" + timeoutSeconds + "s）", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause.getMessage(), cause);
        }
    }

    /** ROUTER 节点：条件求值选择唯一后继 */
    private void executeRouter(Walk walk, GraphNode node, StringBuilder input) {
        List<GraphEdge> outgoing = walk.outgoing.get(node.getNodeId());
        GraphEdge selected = routeEvaluator.selectEdge(outgoing, walk.ctx);
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
        // 被跳过分支下游的 MERGE 永远等不到对应入边：修正静态期待计数，
        // 否则汇聚到不齐、图以部分产出 SUCCESS 收尾（静默丢分支）
        if (outgoing != null && outgoing.size() > 1) {
            adjustMergeExpectForSkipped(walk, outgoing, selected);
        }
        log.info("ROUTER 路由: nodeId={} -> {}", node.getNodeId(), selected.getToNode());
        // 透传上游产出（docs/design/47 延伸修复）：路由节点只做决策不做处理，
        // 原实现传空串会吞掉上游产出，导致下游节点空输入
        continueTo(walk, selected.getToNode(), input != null ? input.toString() : "");
    }

    /**
     * ROUTER 跳过分支后修正下游 MERGE 的期待入边数。
     * <p>从每个被跳过分支的起点做可达性遍历（不展开 MERGE 节点）：
     * 遍历中遇到的每条指向 MERGE 的边即为"永不到达的入边"，将其从 mergeExpect 扣除。
     * 若某 MERGE 的全部入边均来自被跳过子树，其期待数归零后永不开闸——其下游节点
     * 本就不可达（只能经由该 MERGE），语义一致。
     */
    private void adjustMergeExpectForSkipped(Walk walk, List<GraphEdge> outgoing, GraphEdge selected) {
        java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
        java.util.Set<String> visited = new HashSet<>();
        for (GraphEdge skipped : outgoing) {
            if (skipped == selected) {
                continue;
            }
            stack.push(skipped.getToNode());
        }
        while (!stack.isEmpty()) {
            String nodeId = stack.pop();
            if (nodeId == null || !visited.add(nodeId)) {
                continue;
            }
            GraphNode node = nodeById(walk, nodeId);
            if (node == null) {
                continue;
            }
            if (node.getType() == NodeType.MERGE) {
                // 位于被跳过子树内的 MERGE：不展开其下游（其产出不可达）
                continue;
            }
            List<GraphEdge> outs = walk.outgoing.get(nodeId);
            if (outs == null) {
                continue;
            }
            for (GraphEdge e : outs) {
                GraphNode target = nodeById(walk, e.getToNode());
                if (target != null && target.getType() == NodeType.MERGE) {
                    walk.mergeExpect.merge(e.getToNode(), -1, Integer::sum);
                    walk.mergeExpect.computeIfPresent(e.getToNode(),
                            (k, v) -> Math.max(0, v));
                } else {
                    stack.push(e.getToNode());
                }
            }
        }
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
        if (walk.terminal) {
            return; // 图已失败/完成后到达的迟来回调，不产生 node_complete（失败传播守卫）
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("outputLen", output != null ? output.length() : 0);
        // WFO-05：带重试的成功把重试次数透出（应用层落库节点记录 retry_count）
        Integer retries = walk.nodeRetries.get(node.getNodeId());
        if (retries != null && retries > 0) {
            metadata.put("retries", retries);
        }
        walk.sink.next(AgentEvent.builder()
                .type(AgentEvent.NODE_COMPLETE)
                .nodeId(node.getNodeId())
                .role(node.getRoleCode())
                .metadata(metadata)
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

    /** 路径结束：全部路径结束后图完成（或回调宿主 PLAN 子图收尾） */
    private void pathEnded(Walk walk, String output) {
        if (output != null && !output.isBlank()) {
            walk.finalOutput.set(output);
        }
        if (walk.activePaths.decrementAndGet() == 0 && !walk.terminal) {
            if (walk.paused) {
                return; // 暂停时由 pauseHere 负责收尾
            }
            if (walk.completionCallback != null) {
                // PLAN 子图：不结束整图，把子图最终产出交回宿主 Walk 继续父图遍历
                walk.completionCallback.run();
                return;
            }
            // 失败终态判定（docs/design/47 问题一）：存在失败节点时整图如实判 FAILED
            if (!walk.failedNodes.isEmpty()) {
                completeGraph(walk.sink, walk.ctx, "FAILED",
                        Map.of("reason", String.join("; ", walk.failedNodes.values()),
                                "output", walk.finalOutput.get()));
            } else {
                completeGraph(walk.sink, walk.ctx, "SUCCESS",
                        Map.of("output", walk.finalOutput.get()));
            }
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
        // 审批人圈定（WFO-07）：config.assigneeId / assigneeRole 透传给审批中心做可见性过滤
        Object assigneeId = node.getConfig() != null ? node.getConfig().get("assigneeId") : null;
        Object assigneeRole = node.getConfig() != null ? node.getConfig().get("assigneeRole") : null;

        ApprovalRequest.ApprovalRequestBuilder requestBuilder = ApprovalRequest.builder()
                .approvalId(UUID.randomUUID().toString())
                .executionId(walk.ctx.getExecutionId())
                .nodeId(node.getNodeId())
                .type("APPROVE_REJECT")
                .summary(summary)
                .timeoutSeconds(timeoutSeconds);
        if (assigneeId != null && !String.valueOf(assigneeId).isBlank()) {
            requestBuilder.assigneeId(String.valueOf(assigneeId));
        }
        if (assigneeRole != null && !String.valueOf(assigneeRole).isBlank()) {
            requestBuilder.assigneeRole(String.valueOf(assigneeRole));
        }
        ApprovalRequest request = requestBuilder.build();

        Map<String, Object> approvalMeta = new LinkedHashMap<>();
        approvalMeta.put("approvalId", request.getApprovalId());
        approvalMeta.put("timeoutSeconds", timeoutSeconds);
        if (request.getAssigneeId() != null) {
            approvalMeta.put("assigneeId", request.getAssigneeId());
        }
        walk.sink.next(AgentEvent.builder()
                .type(AgentEvent.APPROVAL_REQUIRED)
                .nodeId(node.getNodeId())
                .metadata(approvalMeta)
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
        String reason = null;
        for (AgentEvent event : events) {
            if (AgentEvent.GRAPH_COMPLETE.equals(event.getType())) {
                Map<String, Object> metadata = event.getMetadata();
                Object s = metadata != null ? metadata.get("status") : null;
                status = s != null ? String.valueOf(s) : status;
                output = metadata != null && metadata.get("output") != null
                        ? String.valueOf(metadata.get("output")) : output;
                // 失败原因透传（冒烟发现项修复）：此前 failure 只带 output，error_message 落库为空
                Object r = metadata != null ? metadata.get("reason") : null;
                reason = r != null ? String.valueOf(r) : reason;
            }
        }
        if ("SUCCESS".equals(status)) {
            return OrchestrationResult.success(context.getExecutionId(), output);
        }
        // 终态透传（评审 F-07）：PAUSED/CANCELLED 不再硬编码为 FAILED——
        // 否则引擎检查点仍在而执行记录已 FAILED，形成"FAILED 却可续跑"的状态机矛盾
        return OrchestrationResult.builder()
                .executionId(context.getExecutionId())
                .status(status)
                .finalOutput(output)
                .errorMessage(reason != null && !reason.isBlank() ? reason : ("FAILED".equals(status) ? output : null))
                .build();
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
