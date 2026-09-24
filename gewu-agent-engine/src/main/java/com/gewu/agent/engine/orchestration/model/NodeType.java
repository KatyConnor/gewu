package com.gewu.agent.engine.orchestration.model;

/**
 * 编排图节点类型。
 *
 * @since 1.0.0
 */
public enum NodeType {
    /** Agent 节点 - 委派给某个角色 Agent 执行 */
    AGENT,
    /** 工具节点 - 直接调用工具（无 LLM 推理） */
    TOOL,
    /** 人机协同节点 - 审批 / 输入 */
    HUMAN,
    /** 路由节点 - 条件分支（LLM 路由 or 规则路由） */
    ROUTER,
    /** 并行扇出节点 */
    PARALLEL,
    /** 汇聚合并节点 */
    MERGE,
    /** 嵌套子图节点 */
    SUBGRAPH,
    /** 计划节点 - 输入经 GoalPlanner 动态分解为计划图，映射为并行波次子图内联执行（"汇总→规划→派发实施"闭环） */
    PLAN
}
