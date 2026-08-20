package com.gewu.agent.engine.orchestration.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 编排图节点。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphNode {

    /** 节点 ID（图内唯一） */
    private String nodeId;
    /** 节点类型 */
    private NodeType type;
    /** 引用 ID：agentId / toolName / approvalConfigId / subGraphId */
    private String refId;
    /** 角色编码（AGENT 节点，用于运行时选择 AgentRoleSpec） */
    private String roleCode;
    /** 执行模式（AGENT 节点） */
    private ExecutionMode executionMode;
    /** 节点配置：超时 / 重试 / 并发度等 */
    private Map<String, Object> config;
    /** 输入映射（可引用图变量 ${var.xxx}） */
    private Map<String, Object> inputs;
}
