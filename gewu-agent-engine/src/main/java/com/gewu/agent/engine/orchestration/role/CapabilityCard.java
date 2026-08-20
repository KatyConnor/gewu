package com.gewu.agent.engine.orchestration.role;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Agent 能力卡 - 供 Supervisor 路由决策。
 *
 * @param summary       能力概要，如 "负责编码实现、单元测试、本地调试"
 * @param capabilities  能力标签，如 ["Java/Spring Boot","重构","调试"]
 * @param inputSchema   接受的输入类型，如 ["设计文档","需求"]
 * @param outputSchema  产出类型，如 ["源代码","单元测试"]
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CapabilityCard {
    private String summary;
    private List<String> capabilities;
    private List<String> inputSchema;
    private List<String> outputSchema;
}