package com.gewu.agent.engine.orchestration.role;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 角色注册表 - 管理 SDLC Agent 角色定义。
 * <p>角色来源可为代码注册或 {@link RoleConfigSource} SPI（DB 配置）双轨。
 * 框架内置 {@link #defaultSdlcRoles()} 12 个 SDLC 标准角色，开箱即用。
 *
 * @since 1.0.0
 */
@Slf4j
public class RoleRegistry {

    private final Map<String, AgentRoleSpec> roles = new HashMap<>();

    public RoleRegistry(List<AgentRoleSpec> specs) {
        if (specs != null) {
            for (AgentRoleSpec spec : specs) {
                register(spec);
            }
        }
        // 加载内置默认角色（未覆盖才填充）
        for (AgentRoleSpec role : defaultSdlcRoles()) {
            roles.putIfAbsent(role.getRoleCode(), role);
        }
        log.info("RoleRegistry 已注册 {} 个角色: {}", roles.size(), roles.keySet());
    }

    /** 注册角色 */
    public void register(AgentRoleSpec spec) {
        roles.put(spec.getRoleCode(), spec);
    }

    /** 按角色编码解析，不存在返回 null */
    public AgentRoleSpec resolve(String roleCode) {
        return roles.get(roleCode);
    }

    /** 列出全部角色 */
    public List<AgentRoleSpec> listAll() {
        return List.copyOf(roles.values());
    }

    /** 按阶段过滤角色 */
    public List<AgentRoleSpec> listByPhase(String sdlcPhase) {
        return roles.values().stream()
                .filter(r -> sdlcPhase.equals(r.getSdlcPhase()))
                .collect(Collectors.toList());
    }

    /**
     * 内置 12 个 SDLC 标准角色定义。
     * <p>使用方可通过注册同名角色覆盖默认行为。
     */
    public static List<AgentRoleSpec> defaultSdlcRoles() {
        return List.of(
                AgentRoleSpec.builder()
                        .roleCode("REQUIREMENT_PM").roleName("需求PM")
                        .sdlcPhase("REQUIREMENT").memoryDomain("requirement")
                        .defaultExecutionMode("REACT")
                        .capability(CapabilityCard.builder()
                                .summary("需求澄清、PRD 撰写、用户故事拆分、优先级排序")
                                .capabilities(List.of("需求分析", "PRD", "用户故事", "优先级"))
                                .inputSchema(List.of("用户输入", "背景"))
                                .outputSchema(List.of("PRD", "需求文档"))
                                .build())
                        .build(),
                AgentRoleSpec.builder()
                        .roleCode("ARCHITECT").roleName("架构师")
                        .sdlcPhase("DESIGN").memoryDomain("architecture")
                        .defaultExecutionMode("REACT")
                        .capability(CapabilityCard.builder()
                                .summary("技术选型、架构设计、API 契约、ADR")
                                .capabilities(List.of("架构设计", "技术选型", "API设计", "ADR"))
                                .inputSchema(List.of("PRD", "需求"))
                                .outputSchema(List.of("架构设计文档", "API契约定义"))
                                .build())
                        .build(),
                AgentRoleSpec.builder()
                        .roleCode("DEVELOPER").roleName("开发者")
                        .sdlcPhase("DEVELOP").memoryDomain("coding")
                        .defaultExecutionMode("REACT")
                        .capability(CapabilityCard.builder()
                                .summary("编码实现、单元测试、本地调试")
                                .capabilities(List.of("Java/Spring Boot", "编码", "重构", "调试"))
                                .inputSchema(List.of("设计文档", "需求"))
                                .outputSchema(List.of("源代码", "单元测试"))
                                .build())
                        .build(),
                AgentRoleSpec.builder()
                        .roleCode("REFACTOR").roleName("重构工程师")
                        .sdlcPhase("DEVELOP").memoryDomain("refactor")
                        .capability(CapabilityCard.builder()
                                .summary("代码异味识别、安全重构、技术债治理")
                                .capabilities(List.of("重构", "AST分析", "技术债"))
                                .inputSchema(List.of("源代码"))
                                .outputSchema(List.of("重构后代码", "重构报告"))
                                .build())
                        .build(),
                AgentRoleSpec.builder()
                        .roleCode("CODE_REVIEW").roleName("代码审查")
                        .sdlcPhase("REVIEW").memoryDomain("review")
                        .capability(CapabilityCard.builder()
                                .summary("Code Review、规范检查、安全漏洞识别")
                                .capabilities(List.of("Code Review", "规范检查", "安全审计"))
                                .inputSchema(List.of("源代码"))
                                .outputSchema(List.of("审查报告", "缺陷列表"))
                                .build())
                        .build(),
                AgentRoleSpec.builder()
                        .roleCode("SECURITY_AUDIT").roleName("安全审计")
                        .sdlcPhase("REVIEW").memoryDomain("security")
                        .capability(CapabilityCard.builder()
                                .summary("渗透测试、合规检查、漏洞修复建议")
                                .capabilities(List.of("安全审计", "SAST/DAST", "合规"))
                                .inputSchema(List.of("源代码", "部署配置"))
                                .outputSchema(List.of("安全审计报告", "漏洞修复建议"))
                                .build())
                        .build(),
                AgentRoleSpec.builder()
                        .roleCode("TEST_ENGINEER").roleName("测试工程师")
                        .sdlcPhase("TEST").memoryDomain("test")
                        .capability(CapabilityCard.builder()
                                .summary("测试策略、用例设计、自动化脚本")
                                .capabilities(List.of("测试策略", "用例设计", "自动化测试"))
                                .inputSchema(List.of("源代码", "需求"))
                                .outputSchema(List.of("测试计划", "测试用例", "自动化脚本"))
                                .build())
                        .build(),
                AgentRoleSpec.builder()
                        .roleCode("QA").roleName("QA工程师")
                        .sdlcPhase("TEST").memoryDomain("qa")
                        .capability(CapabilityCard.builder()
                                .summary("端到端测试、回归测试、验收测试")
                                .capabilities(List.of("E2E测试", "回归", "验收"))
                                .inputSchema(List.of("测试用例", "应用"))
                                .outputSchema(List.of("E2E报告", "验收报告"))
                                .build())
                        .build(),
                AgentRoleSpec.builder()
                        .roleCode("DEVOPS").roleName("DevOps工程师")
                        .sdlcPhase("DEPLOY").memoryDomain("deploy"
                        ).defaultExecutionMode("REACT")
                        .capability(CapabilityCard.builder()
                                .summary("CI/CD 流水线、容器化、部署编排")
                                .capabilities(List.of("CI/CD", "Docker", "K8s", "Jenkins"))
                                .inputSchema(List.of("源代码", "部署配置"))
                                .outputSchema(List.of("部署脚本", "流水线配置"))
                                .build())
                        .build(),
                AgentRoleSpec.builder()
                        .roleCode("SRE").roleName("SRE工程师")
                        .sdlcPhase("OPS").memoryDomain("ops")
                        .capability(CapabilityCard.builder()
                                .summary("监控告警、故障排查、容量规划")
                                .capabilities(List.of("监控", "故障排查", "容量规划"))
                                .inputSchema(List.of("监控指标", "告警"))
                                .outputSchema(List.of("排查报告", "优化建议"))
                                .build())
                        .build(),
                AgentRoleSpec.builder()
                        .roleCode("DATABASE_DESIGN").roleName("数据库设计")
                        .sdlcPhase("DESIGN").memoryDomain("database")
                        .capability(CapabilityCard.builder()
                                .summary("数据模型、表结构、索引、迁移脚本")
                                .capabilities(List.of("数据建模", "SQL", "索引优化"))
                                .inputSchema(List.of("需求"))
                                .outputSchema(List.of("数据库设计", "迁移脚本"))
                                .build())
                        .build(),
                AgentRoleSpec.builder()
                        .roleCode("DOC_ENGINEER").roleName("文档工程师")
                        .sdlcPhase("OPS").memoryDomain("doc")
                        .capability(CapabilityCard.builder()
                                .summary("API 文档、架构文档、变更日志")
                                .capabilities(List.of("文档生成", "API文档"))
                                .inputSchema(List.of("源代码", "架构设计"))
                                .outputSchema(List.of("技术文档", "API文档"))
                                .build())
                        .build()
        );
    }
}