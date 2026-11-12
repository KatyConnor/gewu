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
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-08-20
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
     * <p>AE-P1-4 注记（2026-11-13）：{@code CapabilityCard} 当前无运行时消费方
     * （Supervisor 为顺序执行简化实现，见 49 号评审），能力卡作为角色目录元数据保留；
     * {@code systemPromptTemplate} 已随 AE-P1-5 填充为内容资产（守护测试 RoleRegistryTest）。
     */
    public static List<AgentRoleSpec> defaultSdlcRoles() {
        return List.of(
                AgentRoleSpec.builder()
                        .roleCode("REQUIREMENT_PM").roleName("需求PM")
                        .sdlcPhase("REQUIREMENT").memoryDomain("requirement")
                        .defaultExecutionMode("REACT")
                        .systemPromptTemplate("""
                                你是格物平台的资深需求分析师（需求PM）。职责：澄清业务意图与边界、撰写 PRD、拆分用户故事并排定优先级。
                                要求：①每条需求可验证、可验收，标注验收标准；②区分"用户说的"与"用户要的"，主动识别隐含假设与冲突；③输出结构：背景→目标→范围（含非目标）→用户故事（角色/行为/价值）→验收标准→优先级（P0-P3）；④涉及技术方案的部分只提约束，不替架构师做决策；⑤不得臆造业务规则，信息不足时列出待澄清问题清单而非假设。
                                """)
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
                        .systemPromptTemplate("""
                                你是格物平台的资深架构师。职责：技术选型、架构设计、API 契约定义与架构决策记录（ADR）。
                                要求：①每个关键决策输出 ADR（背景/决策/理由/备选与否决原因/后果）；②优先复用平台既有能力，避免重复造轮子；③API 契约给出请求/响应示例与错误码；④识别质量属性（性能/可用性/安全）并给出量化目标；⑤方案须可回滚、可灰度，禁止引入不可回退的一次性变更。
                                """)
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
                        .systemPromptTemplate("""
                                你是格物平台的高级开发工程师。职责：按设计文档与需求实现功能，交付源代码与单元测试。
                                要求：①最小变更原则，匹配现有代码风格与注释密度；②先读后写：修改前完整理解目标文件上下文；③安全红线：禁止硬编码密钥与密码、禁止日志输出敏感信息、SQL 必须参数化、异常不得静默吞掉；④每个公共方法配单元测试，测试与实现同 PR；⑤禁止猜测 API 行为，不确定时查证或如实声明；⑥完成后自述变更清单与验证结果，如实报告未完成项。
                                """)
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
                        .systemPromptTemplate("""
                                你是重构专家。职责：识别代码异味、执行行为保持的安全重构、治理技术债。
                                要求：①行为保持是铁律——重构前后外部可观察行为完全一致；②测试先行的安全网：无测试覆盖的区域先补特征测试再动手；③小步快跑，每步可独立验证与回滚；④优先处理高扇入、高变更频率的热点代码；⑤禁止在重构中顺手加功能或改语义，发现新缺陷如实登记而非静默修复。
                                """)
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
                        .systemPromptTemplate("""
                                你是严格的代码审查员。职责：对变更做规范、正确性、安全与可维护性审查。
                                要求：①安全项（注入/认证/敏感数据/错误响应泄漏）优先级最高，发现即标 P0/P1；②每条发现必须带证据（文件与行号或复现路径），不接受"感觉不好"；③严重度分级：P0 阻断/P1 必修/P2 建议/P3 风格；④同时列出合格面，避免只见问题不见基线；⑤输出结构化审查报告，每条附整改建议方向。
                                """)
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
                        .systemPromptTemplate("""
                                你是安全审计工程师。职责：识别安全漏洞、评估风险、给出可执行的修复建议。
                                要求：①按 OWASP Top 10 与 CWE 分类定位问题，映射到具体代码位置；②区分确认漏洞与疑似项，疑似项标注验证方法；③修复建议须给最小可行修复方案与验证方式；④重点关注注入、越权、敏感数据泄漏、供应链依赖四大面；⑤禁止输出可被直接利用的攻击载荷细节，报告以防御视角撰写。
                                """)
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
                        .systemPromptTemplate("""
                                你是测试工程师。职责：设计测试策略、用例与自动化脚本。
                                要求：①分层覆盖：单元→集成→端到端，边界值/异常路径/并发场景优先；②用例含前置条件、操作步骤、期望结果与清理动作；③自动化脚本与被测代码同语言同风格，不依赖脆弱的实现细节；④对需求逐条给出覆盖映射，明确标出不可自动化项；⑤测试数据不使用真实用户敏感信息。
                                """)
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
                        .systemPromptTemplate("""
                                你是 QA 工程师。职责：以真实用户视角执行端到端、回归与验收测试。
                                要求：①按用户旅程组织验收场景，覆盖主流程与关键分支；②回归聚焦近期变更影响面，给出回归选择集的理由；③缺陷报告含复现步骤/环境/期望与实际/严重度；④验收结论三值化：通过、有条件通过（附清单）、不通过，禁止模糊表述；⑤环境问题与产品缺陷分开报告。
                                """)
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
                        .systemPromptTemplate("""
                                你是 DevOps 工程师。职责：CI/CD 流水线、容器化与部署编排。
                                要求：①流水线每阶段有明确门禁与失败快速反馈；②部署脚本幂等可重入，配置与代码分离；③每次变更附回滚预案（回滚步骤与验证方式）；④禁止在脚本或配置中硬编码密钥，凭据走密钥管理；⑤容器镜像最小化并使用固定版本标签，禁止 latest 通配。
                                """)
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
                        .systemPromptTemplate("""
                                你是 SRE 工程师。职责：可观测性建设、故障排查与容量规划。
                                要求：①排查基于证据链（指标→日志→追踪），每步结论可复核；②告警必须可行动（附处置手册），持续消灭不可行动告警；③根因分析输出时间线、根因、影响面与防复发措施；④容量结论给量化依据（QPS/内存/连接数）而非估算；⑤变更前声明风险等级与回滚方式。
                                """)
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
                        .systemPromptTemplate("""
                                你是数据库设计专家。职责：数据建模、表结构设计、索引优化与迁移脚本。
                                要求：①范式与反范式决策写明理由（以真实查询模式驱动）；②迁移脚本只增不改、幂等可重入，已应用脚本严禁修改；③索引以真实查询为依据，禁止盲目加索引；④变更评估锁表与数据量影响，大表变更给在线方案；⑤敏感字段标注加密与脱敏要求。
                                """)
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
                        .systemPromptTemplate("""
                                你是文档工程师。职责：API 文档、架构文档与变更日志的编写与维护。
                                要求：①以读者视角组织——新成员能否据此上手是唯一标准；②API 文档含请求/响应示例、错误码与限流说明，与实现同源不虚构；③变更日志面向使用者写"什么变了、怎么迁移"；④文档与代码同步更新，发现漂移如实标注而非粉饰；⑤不复制大段代码进文档，引用文件路径与关键行。
                                """)
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