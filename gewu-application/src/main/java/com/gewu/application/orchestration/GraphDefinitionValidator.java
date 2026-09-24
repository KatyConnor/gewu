package com.gewu.application.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.orchestration.model.GraphEdge;
import com.gewu.agent.engine.orchestration.model.GraphNode;
import com.gewu.agent.engine.orchestration.model.NodeType;
import com.gewu.agent.engine.orchestration.model.OrchestrationGraph;
import com.gewu.agent.engine.orchestration.model.OrchestrationMode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 编排图定义结构校验器（VL 规则，docs/design/46 调研报告 §7.5）。
 * <p>在编排图保存与执行前双闸执行：ERROR 级阻断，WARNING 级放行并记录。
 * 枚举合法性（VL-07）由 Jackson 反序列化保证，不在此重复校验。
 *
 * @since 1.0.0
 */
@Component
public class GraphDefinitionValidator {

    /** 校验级别：阻断 */
    public static final String LEVEL_ERROR = "ERROR";
    /** 校验级别：警告（放行） */
    public static final String LEVEL_WARNING = "WARNING";

    private static final Pattern VAR_REF = Pattern.compile("\\$\\{var\\.([A-Za-z0-9_.-]+)}");
    private static final long MIN_HUMAN_TIMEOUT_SECONDS = 1L;
    private static final long MAX_HUMAN_TIMEOUT_SECONDS = 86400L;

    private final ObjectMapper objectMapper;

    public GraphDefinitionValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 单条校验问题。
     *
     * @param ruleId    规则编号（VL-01 ~ VL-12）
     * @param level     {@link #LEVEL_ERROR} / {@link #LEVEL_WARNING}
     * @param nodeId    定位节点（可为 null）
     * @param edgeIndex 定位边下标（可为 null）
     * @param message   面向用户的可读信息
     */
    public record ValidationIssue(String ruleId, String level, String nodeId, Integer edgeIndex, String message) {
        public boolean isError() {
            return LEVEL_ERROR.equals(level);
        }
    }

    /**
     * 校验编排图结构，返回全部问题（含警告）。
     */
    public List<ValidationIssue> validate(OrchestrationGraph graph) {
        List<GraphNode> nodes = graph.getNodes() == null ? List.of() : graph.getNodes();
        List<GraphEdge> edges = graph.getEdges() == null ? List.of() : graph.getEdges();
        List<ValidationIssue> issues = new ArrayList<>();
        validateCommonRules(nodes, edges, graph.getVariables(), issues);
        validateModeRules(graph.getMode(), nodes, edges, issues);
        return issues;
    }

    /** 图是否存在 ERROR 级问题。 */
    public static boolean hasErrors(List<ValidationIssue> issues) {
        return issues.stream().anyMatch(ValidationIssue::isError);
    }

    // ==================== 通用规则（与模式无关） ====================

    private void validateCommonRules(List<GraphNode> nodes, List<GraphEdge> edges,
                                     Map<String, Object> variables, List<ValidationIssue> issues) {
        Set<String> nodeIds = collectUniqueNodeIds(nodes, issues);
        validateDanglingEdges(edges, nodeIds, issues);
        Set<String> resolvableVars = collectResolvableVariables(nodes, variables);
        for (GraphNode node : nodes) {
            validateNodeConfig(node, resolvableVars, issues);
        }
    }

    /** VL-01：nodeId 非空且唯一，返回合法节点 ID 集合。 */
    private Set<String> collectUniqueNodeIds(List<GraphNode> nodes, List<ValidationIssue> issues) {
        Set<String> nodeIds = new LinkedHashSet<>();
        for (GraphNode node : nodes) {
            if (node.getNodeId() == null || node.getNodeId().isBlank()) {
                issues.add(new ValidationIssue("VL-01", LEVEL_ERROR, null, null,
                        "存在缺少 nodeId 的节点，请为每个节点设置图内唯一 ID"));
            } else if (!nodeIds.add(node.getNodeId())) {
                issues.add(new ValidationIssue("VL-01", LEVEL_ERROR, node.getNodeId(), null,
                        "节点 ID 重复: " + node.getNodeId()));
            }
        }
        return nodeIds;
    }

    /** VL-02：边的起止节点必须存在。 */
    private void validateDanglingEdges(List<GraphEdge> edges, Set<String> nodeIds, List<ValidationIssue> issues) {
        for (int i = 0; i < edges.size(); i++) {
            GraphEdge edge = edges.get(i);
            if (edge.getFromNode() == null || edge.getToNode() == null
                    || !nodeIds.contains(edge.getFromNode()) || !nodeIds.contains(edge.getToNode())) {
                issues.add(new ValidationIssue("VL-02", LEVEL_ERROR, null, i,
                        "边 " + i + " 引用了不存在的节点（悬空边）"));
            }
        }
    }

    /** VL-06 / VL-08 / VL-09 / VL-10：节点配置合法性。 */
    private void validateNodeConfig(GraphNode node, Set<String> resolvableVars, List<ValidationIssue> issues) {
        Map<String, Object> config = node.getConfig();
        if (NodeType.TOOL == node.getType()
                && (config == null || config.get("toolName") == null || config.get("toolName").toString().isBlank())) {
            issues.add(new ValidationIssue("VL-06", LEVEL_ERROR, node.getNodeId(), null,
                    "工具节点缺少 config.toolName: " + node.getNodeId()));
        }
        validateHumanTimeout(node, config, issues);
        validateOutputSchema(node, issues);
        validateVariableReferences(node, config, resolvableVars, issues);
    }

    /** VL-08：HUMAN 节点审批超时范围。 */
    private void validateHumanTimeout(GraphNode node, Map<String, Object> config, List<ValidationIssue> issues) {
        if (NodeType.HUMAN != node.getType() || config == null || config.get("timeoutSeconds") == null) {
            return;
        }
        try {
            long timeout = Long.parseLong(config.get("timeoutSeconds").toString());
            if (timeout < MIN_HUMAN_TIMEOUT_SECONDS || timeout > MAX_HUMAN_TIMEOUT_SECONDS) {
                issues.add(new ValidationIssue("VL-08", LEVEL_WARNING, node.getNodeId(), null,
                        "审批超时 timeoutSeconds 超出 [1, 86400]: " + timeout));
            }
        } catch (NumberFormatException e) {
            issues.add(new ValidationIssue("VL-08", LEVEL_WARNING, node.getNodeId(), null,
                    "审批超时 timeoutSeconds 不是数字: " + config.get("timeoutSeconds")));
        }
    }

    /** VL-10：AGENT 节点 outputSchema 必须可解析（Map / 数组 / 逗号分隔字段串）。 */
    private void validateOutputSchema(GraphNode node, List<ValidationIssue> issues) {
        if (NodeType.AGENT != node.getType() || node.getConfig() == null || node.getConfig().get("outputSchema") == null) {
            return;
        }
        Object schema = node.getConfig().get("outputSchema");
        if (schema instanceof Map || schema instanceof List) {
            return;
        }
        if (schema instanceof String text) {
            if (text.contains(",")) {
                return;
            }
            try {
                objectMapper.readValue(text, new TypeReference<Map<String, Object>>() { });
                return;
            } catch (JsonProcessingException e) {
                issues.add(new ValidationIssue("VL-10", LEVEL_ERROR, node.getNodeId(), null,
                        "outputSchema 不是合法的 JSON Schema 或逗号分隔字段串: " + node.getNodeId()));
                return;
            }
        }
        issues.add(new ValidationIssue("VL-10", LEVEL_ERROR, node.getNodeId(), null,
                "outputSchema 类型不支持: " + schema.getClass().getSimpleName()));
    }

    /** VL-09：inputs 与工具参数中的 ${var.xxx} 引用必须可解析（图变量 / input / 前驱节点产出）。 */
    private void validateVariableReferences(GraphNode node, Map<String, Object> config,
                                            Set<String> resolvableVars, List<ValidationIssue> issues) {
        List<String> texts = new ArrayList<>();
        if (node.getInputs() != null) {
            node.getInputs().values().forEach(v -> texts.add(String.valueOf(v)));
        }
        if (config != null && config.get("arguments") != null) {
            texts.add(String.valueOf(config.get("arguments")));
        }
        for (String text : texts) {
            Matcher matcher = VAR_REF.matcher(text);
            while (matcher.find()) {
                String varName = matcher.group(1);
                if (!resolvableVars.contains(varName)) {
                    issues.add(new ValidationIssue("VL-09", LEVEL_WARNING, node.getNodeId(), null,
                            "变量引用 ${var." + varName + "} 无法解析（未在图变量或前驱节点产出中声明）"));
                }
            }
        }
    }

    /** 可解析变量 = 图变量键 + input（运行时注入） + 各节点 nodeId（节点产出变量）。 */
    private Set<String> collectResolvableVariables(List<GraphNode> nodes, Map<String, Object> variables) {
        Set<String> resolvable = new HashSet<>();
        resolvable.add("input");
        if (variables != null) {
            resolvable.addAll(variables.keySet());
        }
        for (GraphNode node : nodes) {
            if (node.getNodeId() != null) {
                resolvable.add(node.getNodeId());
            }
        }
        return resolvable;
    }

    // ==================== 模式相关规则 ====================

    private void validateModeRules(OrchestrationMode mode, List<GraphNode> nodes,
                                   List<GraphEdge> edges, List<ValidationIssue> issues) {
        long agentCount = nodes.stream().filter(n -> NodeType.AGENT == n.getType()).count();
        if (mode == OrchestrationMode.SWARM) {
            validateSwarm(agentCount, edges, issues);
        } else if (mode == OrchestrationMode.SUPERVISOR) {
            validateSupervisor(agentCount, edges, issues);
        } else if (mode == OrchestrationMode.DEBATE) {
            validateDebate(nodes, issues);
        } else {
            validatePipeline(mode, nodes, edges, issues);
        }
    }

    /** VL-04 + VL-12：SWARM 需至少 1 个 AGENT；边不参与执行仅提示。 */
    private void validateSwarm(long agentCount, List<GraphEdge> edges, List<ValidationIssue> issues) {
        if (agentCount == 0) {
            issues.add(new ValidationIssue("VL-04", LEVEL_ERROR, null, null,
                    "群体协作（SWARM）模式至少需要 1 个 AGENT 节点"));
        }
        if (!edges.isEmpty()) {
            issues.add(new ValidationIssue("VL-12", LEVEL_WARNING, null, null,
                    "SWARM 模式忽略边结构：路由由 Agent 输出的 HANDOFF/FINISH 指令决定"));
        }
    }

    /** VL-05 + VL-12：SUPERVISOR 需至少 1 个 AGENT（首节点即监督者）；边不参与执行仅提示。 */
    private void validateSupervisor(long agentCount, List<GraphEdge> edges, List<ValidationIssue> issues) {
        if (agentCount == 0) {
            issues.add(new ValidationIssue("VL-05", LEVEL_ERROR, null, null,
                    "监督者（SUPERVISOR）模式至少需要 1 个 AGENT 节点，第一个 AGENT 节点为监督者"));
        }
        if (!edges.isEmpty()) {
            issues.add(new ValidationIssue("VL-12", LEVEL_WARNING, null, null,
                    "SUPERVISOR 模式忽略边结构：按节点声明顺序串行分派"));
        }
    }

    /** VL-11：DEBATE 需 MERGE/ROUTER 节点作裁判。 */
    private void validateDebate(List<GraphNode> nodes, List<ValidationIssue> issues) {
        boolean hasJudge = nodes.stream()
                .anyMatch(n -> NodeType.MERGE == n.getType() || NodeType.ROUTER == n.getType());
        if (!hasJudge) {
            issues.add(new ValidationIssue("VL-11", LEVEL_WARNING, null, null,
                    "辩论共识（DEBATE）模式建议包含 MERGE/ROUTER 节点作为裁判，缺失时无裁决汇总"));
        }
    }

    /** VL-03：PIPELINE 结构完整性（起始节点 / 环 / ROUTER 条件 / 并行汇聚配对）。 */
    private void validatePipeline(OrchestrationMode mode, List<GraphNode> nodes,
                                  List<GraphEdge> edges, List<ValidationIssue> issues) {
        if (nodes.isEmpty()) {
            issues.add(new ValidationIssue("VL-03", LEVEL_WARNING, null, null, "图为空，没有任何节点"));
            return;
        }
        if (mode != OrchestrationMode.PIPELINE && !edges.isEmpty()) {
            issues.add(new ValidationIssue("VL-12", LEVEL_WARNING, null, null,
                    "当前模式 " + mode + " 下边结构不参与执行，如需按边编排请使用 PIPELINE"));
        }
        validateStartNode(nodes, edges, issues);
        validateCycles(nodes, edges, issues);
        validateRouterConditions(nodes, edges, issues);
        validateParallelMergePairing(nodes, edges, issues);
    }

    /** VL-03a：存在无入边节点作为起始（PIPELINE 从第一个无入边节点开始）。 */
    private void validateStartNode(List<GraphNode> nodes, List<GraphEdge> edges, List<ValidationIssue> issues) {
        Set<String> targets = new HashSet<>();
        for (GraphEdge edge : edges) {
            targets.add(edge.getToNode());
        }
        boolean hasStart = nodes.stream().anyMatch(n -> n.getNodeId() != null && !targets.contains(n.getNodeId()));
        if (!hasStart) {
            issues.add(new ValidationIssue("VL-03", LEVEL_WARNING, null, null,
                    "未找到无入边的起始节点（所有节点都有入边），执行将从第一个节点回退开始"));
        }
    }

    /** VL-03b：有向环检测（三色标记 DFS），PIPELINE 下环会导致执行不终止。 */
    private void validateCycles(List<GraphNode> nodes, List<GraphEdge> edges, List<ValidationIssue> issues) {
        Map<String, List<String>> adjacency = new HashMap<>();
        for (GraphEdge edge : edges) {
            adjacency.computeIfAbsent(edge.getFromNode(), k -> new ArrayList<>()).add(edge.getToNode());
        }
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (GraphNode node : nodes) {
            String nodeId = node.getNodeId();
            if (nodeId != null && !visited.contains(nodeId)
                    && hasCycleFrom(nodeId, adjacency, visiting, visited)) {
                issues.add(new ValidationIssue("VL-03", LEVEL_ERROR, nodeId, null,
                        "图存在环，节点 " + nodeId + " 位于环上，PIPELINE 执行将无法终止"));
                return;
            }
        }
    }

    private boolean hasCycleFrom(String nodeId, Map<String, List<String>> adjacency,
                                 Set<String> visiting, Set<String> visited) {
        visiting.add(nodeId);
        for (String next : adjacency.getOrDefault(nodeId, List.of())) {
            if (visiting.contains(next)) {
                return true;
            }
            if (!visited.contains(next) && hasCycleFrom(next, adjacency, visiting, visited)) {
                return true;
            }
        }
        visiting.remove(nodeId);
        visited.add(nodeId);
        return false;
    }

    /** VL-03c：ROUTER 节点的每条出边必须有 condition，否则运行时无可命中出边。 */
    private void validateRouterConditions(List<GraphNode> nodes, List<GraphEdge> edges, List<ValidationIssue> issues) {
        Set<String> routerIds = new HashSet<>();
        for (GraphNode node : nodes) {
            if (NodeType.ROUTER == node.getType() && node.getNodeId() != null) {
                routerIds.add(node.getNodeId());
            }
        }
        for (int i = 0; i < edges.size(); i++) {
            GraphEdge edge = edges.get(i);
            if (routerIds.contains(edge.getFromNode())
                    && (edge.getCondition() == null || edge.getCondition().isBlank())) {
                issues.add(new ValidationIssue("VL-03", LEVEL_ERROR, edge.getFromNode(), i,
                        "路由节点 " + edge.getFromNode() + " 的出边缺少 condition 条件表达式"));
            }
        }
    }

    /** VL-03d：PARALLEL 扇出不足 / MERGE 汇聚不足（成对性提示）。 */
    private void validateParallelMergePairing(List<GraphNode> nodes, List<GraphEdge> edges, List<ValidationIssue> issues) {
        Map<String, Integer> outDegree = new LinkedHashMap<>();
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        for (GraphEdge edge : edges) {
            outDegree.merge(edge.getFromNode(), 1, Integer::sum);
            inDegree.merge(edge.getToNode(), 1, Integer::sum);
        }
        for (GraphNode node : nodes) {
            if (NodeType.PARALLEL == node.getType() && outDegree.getOrDefault(node.getNodeId(), 0) < 2) {
                issues.add(new ValidationIssue("VL-03", LEVEL_WARNING, node.getNodeId(), null,
                        "并行节点 " + node.getNodeId() + " 出边少于 2 条，无实际并行分支"));
            }
            if (NodeType.MERGE == node.getType() && inDegree.getOrDefault(node.getNodeId(), 0) < 2) {
                issues.add(new ValidationIssue("VL-03", LEVEL_WARNING, node.getNodeId(), null,
                        "汇聚节点 " + node.getNodeId() + " 入边少于 2 条，无实际汇聚分支"));
            }
        }
    }
}
