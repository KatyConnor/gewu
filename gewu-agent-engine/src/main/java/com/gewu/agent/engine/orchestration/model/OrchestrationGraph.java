package com.gewu.agent.engine.orchestration.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 编排图 - 统一工作流 DAG 与 Agent ReAct 的编排模型。
 * <p>"编排即图"：工作流节点（ToolNode/HumanNode/RouterNode）与 Agent 循环（AgentNode）都是图节点，
 * 统一由 {@link com.gewu.agent.engine.orchestration.Orchestrator} 调度。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrchestrationGraph {

    /** 图 ID */
    private String graphId;
    /** 名称 */
    private String name;
    /** 图类型 */
    private GraphType type;
    /** 编排模式 */
    private OrchestrationMode mode;
    /** 节点列表 */
    private List<GraphNode> nodes;
    /** 边列表 */
    private List<GraphEdge> edges;
    /** 图级变量模板（全局上下文） */
    private Map<String, Object> variables;
    /** 关联的自主目标 ID（若有） */
    private String rootGoalId;
}
