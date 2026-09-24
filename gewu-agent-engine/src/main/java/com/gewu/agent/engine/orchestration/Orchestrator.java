package com.gewu.agent.engine.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.contract.ArtifactContract;
import com.gewu.agent.engine.contract.ArtifactValidator;
import com.gewu.agent.engine.core.AgentExecutor;
import com.gewu.agent.engine.core.event.AgentEvent;
import com.gewu.agent.engine.orchestration.mode.DebateModeHandler;
import com.gewu.agent.engine.orchestration.mode.ModeHandler;
import com.gewu.agent.engine.orchestration.mode.PipelineModeHandler;
import com.gewu.agent.engine.orchestration.mode.SupervisorModeHandler;
import com.gewu.agent.engine.orchestration.mode.SwarmModeHandler;
import com.gewu.agent.engine.orchestration.model.GraphNode;
import com.gewu.agent.engine.orchestration.model.NodeType;
import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import com.gewu.agent.engine.orchestration.model.OrchestrationGraph;
import com.gewu.agent.engine.orchestration.model.OrchestrationResult;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 编排器 - 编排引擎的核心调度器。
 * <p>按编排图驱动执行：拓扑排序确定执行顺序，按节点类型分派。
 * AGENT 节点委托 {@link ModeHandler}（按图 {@code mode} 选择）执行；
 * TOOL / HUMAN / ROUTER / PARALLEL / MERGE / SUBGRAPH 节点类型由使用方通过 SPI 扩展。
 * <p>执行后处理（同步路径）：节点输出契约校验（config.outputSchema）-> 多 Agent 产出冲突解决。
 *
 * @since 1.0.0
 */
@Slf4j
public class Orchestrator {

    /** 冲突解决阻塞等待上限（含人工裁决场景的超时保护） */
    private static final Duration CONFLICT_RESOLUTION_TIMEOUT = Duration.ofSeconds(60);

    private final Map<String, ModeHandler> modeHandlers;
    /** 冲突解决器（可选；为 null 时跳过执行后冲突检测） */
    private final ConflictResolver conflictResolver;
    /** 制品校验器（可选；为 null 时跳过输出契约校验） */
    private final ArtifactValidator artifactValidator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Orchestrator(AgentExecutor executor) {
        this(executor, new com.gewu.agent.engine.hitl.NoOpHitlGateway(), null, null);
    }

    public Orchestrator(AgentExecutor executor, com.gewu.agent.engine.hitl.HitlGateway hitlGateway) {
        this(executor, hitlGateway, null, null);
    }

    public Orchestrator(AgentExecutor executor, com.gewu.agent.engine.hitl.HitlGateway hitlGateway,
                        ConflictResolver conflictResolver) {
        this(executor, hitlGateway, conflictResolver, null);
    }

    public Orchestrator(AgentExecutor executor, com.gewu.agent.engine.hitl.HitlGateway hitlGateway,
                        ConflictResolver conflictResolver, ArtifactValidator artifactValidator) {
        this(executor, hitlGateway, conflictResolver, artifactValidator, null, null, null);
    }

    public Orchestrator(AgentExecutor executor, com.gewu.agent.engine.hitl.HitlGateway hitlGateway,
                        ConflictResolver conflictResolver, ArtifactValidator artifactValidator,
                        GraphNodeExecutor graphNodeExecutor, ExecutionControl executionControl) {
        this(executor, hitlGateway, conflictResolver, artifactValidator, graphNodeExecutor, executionControl, null);
    }

    /**
     * 完整构造：传入 TOOL 节点执行器与执行控制后，Pipeline 模式获得
     * TOOL/ROUTER/PARALLEL/MERGE 节点支持与协作式暂停/取消/断点续跑能力；
     * 传入 GoalPlanner 后 PLAN 节点获得动态规划-派发能力（"汇总→规划→派发实施"闭环）。
     */
    public Orchestrator(AgentExecutor executor, com.gewu.agent.engine.hitl.HitlGateway hitlGateway,
                        ConflictResolver conflictResolver, ArtifactValidator artifactValidator,
                        GraphNodeExecutor graphNodeExecutor, ExecutionControl executionControl,
                        GoalPlanner goalPlanner) {
        this.modeHandlers = new HashMap<>();
        this.conflictResolver = conflictResolver;
        this.artifactValidator = artifactValidator;
        register(new PipelineModeHandler(executor, hitlGateway, graphNodeExecutor, executionControl, goalPlanner));
        register(new SupervisorModeHandler(executor));
        register(new SwarmModeHandler(executor));
        register(new DebateModeHandler(executor));
    }

    /** 注册模式处理器（可覆盖默认实现） */
    public void register(ModeHandler handler) {
        modeHandlers.put(handler.mode(), handler);
        log.info("Orchestrator 注册模式处理器: {}", handler.mode());
    }

    /**
     * 执行编排图（流式）。
     */
    public Flux<AgentEvent> run(OrchestrationGraph graph, OrchestrationContext context) {
        if (context.getExecutionId() == null) {
            context.setExecutionId(UUID.randomUUID().toString());
        }
        context.setGraphId(graph.getGraphId());

        String mode = graph.getMode() != null ? graph.getMode().name() : "PIPELINE";
        ModeHandler handler = modeHandlers.get(mode);
        if (handler == null) {
            return Flux.error(new IllegalArgumentException("不支持的编排模式: " + mode));
        }

        log.info("编排执行: graphId={}, mode={}, executionId={}",
                graph.getGraphId(), mode, context.getExecutionId());
        return handler.run(graph, context);
    }

    /**
     * 执行编排图（同步）。
     */
    public OrchestrationResult runSync(OrchestrationGraph graph, OrchestrationContext context) {
        if (context.getExecutionId() == null) {
            context.setExecutionId(UUID.randomUUID().toString());
        }
        String mode = graph.getMode() != null ? graph.getMode().name() : "PIPELINE";
        ModeHandler handler = modeHandlers.get(mode);
        if (handler == null) {
            return OrchestrationResult.failure(context.getExecutionId(), "不支持的编排模式: " + mode);
        }
        OrchestrationResult result = handler.runSync(graph, context);
        result = validateNodeOutputs(graph, result);
        return resolveOutputConflicts(graph, context, result);
    }

    /**
     * 节点输出契约校验：节点 config 配置 {@code outputSchema}（字段列表或 Schema Map）时启用。
     * <p>结构化产出（JSON 对象）按契约校验必需字段，不合格则整体标记 FAILED，
     * 由上层（自主执行器反思循环）触发重试。纯文本产出跳过字段校验。
     */
    private OrchestrationResult validateNodeOutputs(OrchestrationGraph graph, OrchestrationResult result) {
        if (artifactValidator == null || !"SUCCESS".equals(result.getStatus())
                || graph.getNodes() == null || result.getOutputs() == null) {
            return result;
        }
        try {
            for (GraphNode node : graph.getNodes()) {
                Object schemaConfig = node.getConfig() != null ? node.getConfig().get("outputSchema") : null;
                if (schemaConfig == null || node.getType() != NodeType.AGENT) {
                    continue;
                }
                Object output = result.getOutputs().get(node.getNodeId());
                if (output == null) {
                    continue;
                }
                Object artifact = output;
                if (output instanceof String text) {
                    try {
                        JsonNode parsed = objectMapper.readTree(text);
                        if (!parsed.isObject()) {
                            continue; // 非结构化产出不做字段契约校验
                        }
                        artifact = parsed;
                    } catch (Exception e) {
                        continue; // 纯文本产出跳过
                    }
                }
                ArtifactValidator.ValidationResult validation =
                        artifactValidator.validate(buildContract(schemaConfig), artifact);
                if (!validation.isPassed()) {
                    log.warn("节点输出契约校验失败: nodeId={}, errors={}", node.getNodeId(), validation.getErrors());
                    result.setStatus("FAILED");
                    result.setErrorMessage("节点 " + node.getNodeId() + " 输出不符合契约: "
                            + String.join("; ", validation.getErrors()));
                    return result;
                }
            }
        } catch (Exception e) {
            log.warn("输出契约校验异常（忽略，保留原结果）: {}", e.getMessage());
        }
        return result;
    }

    /** 从节点 config.outputSchema 构建制品契约（支持字段列表 / Schema Map / 逗号分隔字符串） */
    private ArtifactContract buildContract(Object schemaConfig) {
        Map<String, Object> schema = new LinkedHashMap<>();
        if (schemaConfig instanceof Map<?, ?> map) {
            map.forEach((k, v) -> schema.put(String.valueOf(k), v));
        } else if (schemaConfig instanceof List<?> fields) {
            for (Object field : fields) {
                schema.put(String.valueOf(field), null);
            }
        } else if (schemaConfig instanceof String csv) {
            for (String field : csv.split(",")) {
                if (!field.isBlank()) {
                    schema.put(field.trim(), null);
                }
            }
        }
        return ArtifactContract.builder()
                .artifactType("node-output")
                .version("1")
                .schema(schema)
                .build();
    }

    /**
     * 执行后冲突检测与解决：图级变量 {@code conflictCheck=true} 时启用。
     * <p>收集多个 AGENT 节点的非空产出作为冲突方，若产出存在分歧则委托
     * {@link ConflictResolver} 按四策略（权威优先 / 多数表决 / LLM 仲裁 / 人工裁决）
     * 选出胜者，胜者产出作为最终输出。冲突解决异常不阻断编排结果，仅告警降级。
     */
    private OrchestrationResult resolveOutputConflicts(OrchestrationGraph graph, OrchestrationContext context,
                                                       OrchestrationResult result) {
        if (conflictResolver == null || !"SUCCESS".equals(result.getStatus())) {
            return result;
        }
        Object optIn = graph.getVariables() != null ? graph.getVariables().get("conflictCheck") : null;
        if (!Boolean.parseBoolean(String.valueOf(optIn))) {
            return result;
        }
        try {
            Map<String, String> agentOutputs = new LinkedHashMap<>();
            if (graph.getNodes() != null && result.getOutputs() != null) {
                for (GraphNode node : graph.getNodes()) {
                    if (node.getType() == NodeType.AGENT) {
                        Object out = result.getOutputs().get(node.getNodeId());
                        if (out != null && !String.valueOf(out).isBlank()) {
                            agentOutputs.put(node.getNodeId(), String.valueOf(out));
                        }
                    }
                }
            }
            // 不足两方、或产出完全一致，视为无冲突
            if (agentOutputs.size() < 2 || new LinkedHashSet<>(agentOutputs.values()).size() == 1) {
                return result;
            }

            List<ConflictResolver.ConflictParty> parties = agentOutputs.entrySet().stream()
                    .map(e -> ConflictResolver.ConflictParty.builder()
                            .agentId(e.getKey()).proposal(e.getValue()).build())
                    .toList();
            ConflictResolver.AgentConflict conflict = ConflictResolver.AgentConflict.builder()
                    .conflictId("conflict-" + context.getExecutionId())
                    .type("semantic")
                    .description("编排图 " + graph.getGraphId() + " 多 Agent 节点产出存在分歧")
                    .executionId(context.getExecutionId())
                    .parties(parties)
                    .build();

            ConflictResolver.ConflictResolution resolution =
                    conflictResolver.resolve(conflict).block(CONFLICT_RESOLUTION_TIMEOUT);
            if (resolution != null && resolution.getWinner() != null) {
                log.info("编排冲突已解决: executionId={}, method={}, winner={}",
                        context.getExecutionId(), resolution.getMethod(), resolution.getWinner().getAgentId());
                result.setFinalOutput(resolution.getWinner().getProposal());
                if (result.getOutputs() != null) {
                    result.getOutputs().put("conflictResolution", resolution.getMethod());
                }
            }
        } catch (Exception e) {
            log.warn("编排冲突解决失败（降级保留原输出）: executionId={}, cause={}",
                    context.getExecutionId(), e.getMessage());
        }
        return result;
    }

    /** 列出已注册模式 */
    public List<String> listModes() {
        return List.copyOf(modeHandlers.keySet());
    }
}