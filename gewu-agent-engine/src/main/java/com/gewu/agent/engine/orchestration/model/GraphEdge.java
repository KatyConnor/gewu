package com.gewu.agent.engine.orchestration.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 编排图边。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphEdge {

    /** 起始节点 ID */
    private String fromNode;
    /** 目标节点 ID */
    private String toNode;
    /** 路由条件（表达式 / 规则，ROUTER 节点使用） */
    private String condition;
}
