package com.gewu.agent.engine.orchestration.role;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * SDLC Agent 角色规格 - 角色注册表中的一条角色定义。
 * <p>每个角色 = (系统提示词 + 能力卡 + 工具集 + 记忆域 + 编排位置)。
 * 通过 {@link RoleRegistry} 注册，供 {@link com.gewu.agent.engine.orchestration.Orchestrator} 在 AGENT 节点按 roleCode 解析。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRoleSpec {

    /** 角色编码，如 "DEVELOPER" / "REQUIREMENT_PM" */
    private String roleCode;
    /** 角色名称，如 "开发者" */
    private String roleName;
    /** SDLC 阶段：REQUIREMENT / DESIGN / DEVELOP / REVIEW / TEST / DEPLOY / OPS */
    private String sdlcPhase;
    /** 系统提示词模板（含变量占位） */
    private String systemPromptTemplate;
    /** 绑定工具名列表 */
    private List<String> toolNames;
    /** 绑定技能编码列表 */
    private List<String> skillCodes;
    /** 记忆域隔离标识 */
    private String memoryDomain;
    /** 默认执行模式 */
    private String defaultExecutionMode;
    /** 能力卡（供 Supervisor 路由决策） */
    private CapabilityCard capability;
}