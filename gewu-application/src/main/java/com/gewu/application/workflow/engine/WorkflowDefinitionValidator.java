package com.gewu.application.workflow.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.infrastructure.mapper.WorkflowNodeMapper;
import com.gewu.infrastructure.mapper.WorkflowTransitionMapper;
import com.gewu.domain.workflow.WorkflowNode;
import com.gewu.domain.workflow.WorkflowTransition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 工作流定义结构校验器（WV 规则，51 号 §十）。
 * <p>保存与发布双闸执行：ERROR 级阻断（发布/保存拒绝并返回问题清单），
 * WARNING 级放行并记日志。WV-01~07 为 P1 交付集（WV-08~10 随 P2/P3 补齐）。
 *
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowDefinitionValidator {

    public static final String LEVEL_ERROR = "ERROR";
    public static final String LEVEL_WARNING = "WARNING";

    /** 触发器节点类型集（WV-01） */
    private static final Set<String> TRIGGER_TYPES = Set.of(
            "manual-trigger", "schedule-trigger", "webhook-trigger", "event-trigger", "upstream-trigger", "start");

    /** 结构性节点（环检测对 loop 回边放行） */
    private static final Set<String> STRUCTURAL_TYPES = Set.of("parallel", "join", "loop");

    private final WorkflowNodeMapper nodeMapper;
    private final WorkflowTransitionMapper transitionMapper;
    private final WorkflowNodeHandlerRegistry handlerRegistry;
    private final WorkflowExpressionEvaluator expressionEvaluator;

    /** 单条校验问题 */
    public record ValidationIssue(String ruleId, String level, String nodeId, String message) {
        public boolean isError() {
            return LEVEL_ERROR.equals(level);
        }
    }

    /** 校验工作流定义，返回全部问题（含警告） */
    public List<ValidationIssue> validate(String workflowId) {
        List<WorkflowNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<WorkflowNode>()
                .eq(WorkflowNode::getWorkflowId, workflowId));
        List<WorkflowTransition> edges = transitionMapper.selectList(
                new LambdaQueryWrapper<WorkflowTransition>()
                        .eq(WorkflowTransition::getWorkflowId, workflowId));
        List<ValidationIssue> issues = new ArrayList<>();
        validateSingleTrigger(nodes, issues);
        validateTerminalNode(nodes, issues);
        validateReferences(nodes, edges, issues);
        validateCycles(nodes, edges, issues);
        validateRouting(nodes, edges, issues);
        validateRequiredConfig(nodes, issues);
        validateExpressions(nodes, issues);
        return issues;
    }

    /** 是否存在 ERROR 级问题 */
    public boolean hasErrors(List<ValidationIssue> issues) {
        return issues.stream().anyMatch(ValidationIssue::isError);
    }

    // ---------- WV-01：有且仅有一个触发器节点 ----------
    private void validateSingleTrigger(List<WorkflowNode> nodes, List<ValidationIssue> issues) {
        long triggerCount = nodes.stream()
                .filter(n -> TRIGGER_TYPES.contains(nodeType(n)))
                .count();
        if (triggerCount == 0) {
            issues.add(new ValidationIssue("WV-01", LEVEL_ERROR, null, "缺少触发器节点（manual-trigger 等）"));
        } else if (triggerCount > 1) {
            issues.add(new ValidationIssue("WV-01", LEVEL_ERROR, null,
                    "存在 " + triggerCount + " 个触发器节点，一个工作流只能有一个入口触发器"));
        }
    }

    // ---------- WV-02：存在终结节点（return/end） ----------
    private void validateTerminalNode(List<WorkflowNode> nodes, List<ValidationIssue> issues) {
        boolean hasTerminal = nodes.stream()
                .anyMatch(n -> "return".equals(nodeType(n)) || "end".equals(nodeType(n)));
        if (!hasTerminal) {
            issues.add(new ValidationIssue("WV-02", LEVEL_ERROR, null,
                    "缺少终结节点（return），流程将无法正常完成"));
        }
    }

    // ---------- WV-03：nodeId 唯一 + 边引用存在 ----------
    private void validateReferences(List<WorkflowNode> nodes, List<WorkflowTransition> edges,
                                    List<ValidationIssue> issues) {
        Set<String> nodeIds = new HashSet<>();
        for (WorkflowNode node : nodes) {
            if (node.getId() == null || !nodeIds.add(node.getId())) {
                issues.add(new ValidationIssue("WV-03", LEVEL_ERROR, node.getId(), "节点 ID 缺失或重复"));
            }
        }
        for (WorkflowTransition edge : edges) {
            if (edge.getFromNodeId() == null || edge.getToNodeId() == null
                    || !nodeIds.contains(edge.getFromNodeId()) || !nodeIds.contains(edge.getToNodeId())) {
                issues.add(new ValidationIssue("WV-03", LEVEL_ERROR, null,
                        "存在悬空边（from/to 引用不存在的节点）"));
            }
        }
    }

    // ---------- WV-04：环检测（loop 指回自身的回边放行） ----------
    private void validateCycles(List<WorkflowNode> nodes, List<WorkflowTransition> edges,
                                List<ValidationIssue> issues) {
        java.util.Map<String, List<WorkflowTransition>> outgoing = new java.util.HashMap<>();
        java.util.Map<String, WorkflowNode> byId = new java.util.HashMap<>();
        for (WorkflowNode node : nodes) {
            byId.put(node.getId(), node);
            outgoing.put(node.getId(), new ArrayList<>());
        }
        for (WorkflowTransition edge : edges) {
            if (outgoing.containsKey(edge.getFromNodeId())) {
                outgoing.get(edge.getFromNodeId()).add(edge);
            }
        }
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (WorkflowNode node : nodes) {
            if (!visited.contains(node.getId()) && hasCycle(node.getId(), outgoing, byId, visiting, visited)) {
                issues.add(new ValidationIssue("WV-04", LEVEL_ERROR, node.getId(),
                        "图存在环（loop 节点回边除外），流程将无法终止"));
                return;
            }
        }
    }

    private boolean hasCycle(String nodeId, java.util.Map<String, List<WorkflowTransition>> outgoing,
                             java.util.Map<String, WorkflowNode> byId,
                             Set<String> visiting, Set<String> visited) {
        visiting.add(nodeId);
        for (WorkflowTransition edge : outgoing.getOrDefault(nodeId, List.of())) {
            String next = edge.getToNodeId();
            if (next == null) {
                continue;
            }
            WorkflowNode nextNode = byId.get(next);
            // loop 回边放行：目标为 loop 节点的边不构成非法环
            if (nextNode != null && "loop".equals(nodeType(nextNode))) {
                continue;
            }
            if (visiting.contains(next)) {
                return true;
            }
            if (!visited.contains(next) && hasCycle(next, outgoing, byId, visiting, visited)) {
                return true;
            }
        }
        visiting.remove(nodeId);
        visited.add(nodeId);
        return false;
    }

    // ---------- WV-05：路由完整性（condition 双出口可路由 / parallel 配对 join / join 有入边） ----------
    private void validateRouting(List<WorkflowNode> nodes, List<WorkflowTransition> edges,
                                 List<ValidationIssue> issues) {
        Set<String> parallelIds = new HashSet<>();
        for (WorkflowNode node : nodes) {
            if ("parallel".equals(nodeType(node))) {
                parallelIds.add(node.getId());
            }
        }
        long joinCount = nodes.stream().filter(n -> "join".equals(nodeType(n))).count();
        if (!parallelIds.isEmpty() && joinCount == 0) {
            issues.add(new ValidationIssue("WV-05", LEVEL_ERROR, null,
                    "存在 parallel 并行网关但缺少 join 汇聚节点"));
        }
        for (WorkflowNode node : nodes) {
            String type = nodeType(node);
            if ("condition".equals(type) && countOutgoing(node.getId(), edges) < 2) {
                issues.add(new ValidationIssue("WV-05", LEVEL_ERROR, node.getId(),
                        "condition 节点应具备 true/false 两条出边"));
            }
            if ("parallel".equals(type) && countOutgoing(node.getId(), edges) < 2) {
                issues.add(new ValidationIssue("WV-05", LEVEL_ERROR, node.getId(),
                        "parallel 节点出边少于 2 条，无实际并行意义"));
            }
        }
    }

    private int countOutgoing(String nodeId, List<WorkflowTransition> edges) {
        return (int) edges.stream().filter(e -> nodeId.equals(e.getFromNodeId())).count();
    }

    // ---------- WV-06：节点 config 必填项（注册表元数据驱动）+ 未知类型 ----------
    private void validateRequiredConfig(List<WorkflowNode> nodes, List<ValidationIssue> issues) {
        for (WorkflowNode node : nodes) {
            String type = nodeType(node);
            if (!STRUCTURAL_TYPES.contains(type) && !"return".equals(type) && !"end".equals(type)
                    && !handlerRegistry.isRegistered(type)) {
                issues.add(new ValidationIssue("WV-06", LEVEL_ERROR, node.getId(),
                        "未知节点类型 '" + type + "'（已注册: " + handlerRegistry.registeredTypes() + "）"));
                continue;
            }
            if (STRUCTURAL_TYPES.contains(type)) {
                continue; // 结构性节点配置由调度器兜底校验
            }
            WorkflowNodeHandler handler = handlerRegistry.resolve(type);
            if (handler == null) {
                continue;
            }
            java.util.Map<String, Object> config = parseConfig(node.getConfig());
            for (String field : handler.requiredConfigFields()) {
                Object value = config.get(field);
                if (value == null || String.valueOf(value).isBlank()) {
                    issues.add(new ValidationIssue("WV-06", LEVEL_ERROR, node.getId(),
                            type + " 节点缺少必填配置 " + field));
                }
            }
        }
    }

    // ---------- WV-07：表达式语法（condition/switch 表达式 + 出边条件表达式） ----------
    private void validateExpressions(List<WorkflowNode> nodes, List<ValidationIssue> issues) {
        for (WorkflowNode node : nodes) {
            String type = nodeType(node);
            if (!"condition".equals(type) && !"switch".equals(type)) {
                continue;
            }
            Object expression = parseConfig(node.getConfig()).get("expression");
            if (expression != null && !String.valueOf(expression).isBlank()) {
                try {
                    expressionEvaluator.validateSyntax(String.valueOf(expression));
                } catch (IllegalArgumentException e) {
                    issues.add(new ValidationIssue("WV-07", LEVEL_ERROR, node.getId(),
                            type + " 表达式语法错误: " + e.getMessage()));
                }
            }
        }
    }

    private String nodeType(WorkflowNode node) {
        return node.getNodeType() == null ? "manual-trigger" : node.getNodeType();
    }

    private java.util.Map<String, Object> parseConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return java.util.Map.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(configJson, new com.fasterxml.jackson.core.type.TypeReference<
                            java.util.LinkedHashMap<String, Object>>() { });
        } catch (Exception e) {
            return java.util.Map.of();
        }
    }
}
