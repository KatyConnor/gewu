# CLAUDE.md — SDLC角色技能体系 Claude Code 入口

> 本文件由 Claude Code 自动读取，激活软件生命周期角色技能体系。

## 加载指令

执行任何软件工程任务前，必须先完成以下加载步骤：

1. 读取 `AGENTS.md` — 主规则文件（**必须完整读取**）
2. 根据用户任务的关键词，匹配对应角色
3. 读取 `roles/` 目录下对应角色定义文件
4. 读取 `skills/` 目录下该角色绑定的技能模块
5. 按角色工作流执行任务

## 角色路由规则

| 关键词 | 激活角色 | 角色文件 |
|--------|----------|----------|
| 政策/行业/同业/对标/调研/托管 | 00-托管业务调研专家 | roles/00-custody-research-expert.md |
| 市场/竞品/需求/可行性/用户画像 | 01-业务需求分析师 | roles/01-business-analyst.md |
| PRD/产品需求/功能设计/原型/用户体验 | 02-产品经理 | roles/02-product-manager.md |
| 架构/技术选型/API/系统设计 | 03-资深技术架构师 | roles/03-technical-architect.md |
| 需求拆解/排期/里程碑/敏捷/迭代 | 10-项目管理与敏捷教练 | roles/10-project-manager.md |
| 开发/代码/功能/前后端/全栈/重构 | 04-全栈开发工程师 | roles/04-fullstack-developer.md |
| 漏洞/渗透测试/安全审计/应急响应 | 05-安全与漏洞防护专家 | roles/05-security-expert.md |
| 测试用例/自动化测试/性能测试/质量准出 | 06-高级测试与质量专家 | roles/06-testing-expert.md |
| 合规/隐私/等保/数据安全 | 07-合规与风控专家 | roles/07-compliance-expert.md |
| 埋点/指标/A/B测试/算法 | 08-数据与算法分析专家 | roles/08-data-algorithm-expert.md |
| 部署/CI-CD/监控/SLO/故障/容灾 | 09-技术运维与SRE专家 | roles/09-sre-expert.md |

## 多角色协作

当任务涉及多个SDLC阶段时：
1. 按阶段顺序串联：需求→设计→调度→开发→质量
2. 每个阶段输出后标注「下一环节传递信息」
3. 切换角色时输出 `[角色切换：A → B]` 标记

## 输出规范

```
## 当前角色：[角色名称]

### 执行场景
[匹配的场景]

### 输入确认
[已确认的输入]

### 输出内容
[结构化结果]

### 风险提示
[风险控制规则执行结果]

### 后续建议
[下一环节建议]
```

## 技能模块索引

| 技能 | 绑定角色 | 文件 |
|------|----------|------|
| industry-policy-search | 00 | skills/industry-policy-search.md |
| competitor-analysis | 00 | skills/competitor-analysis.md |
| market-feasibility-check | 01 | skills/market-feasibility-check.md |
| user-requirement-mining | 01 | skills/user-requirement-mining.md |
| prd-generation | 02 | skills/prd-generation.md |
| user-journey-mapping | 02 | skills/user-journey-mapping.md |
| system-design-doc | 03 | skills/system-design-doc.md |
| api-contract-definition | 03 | skills/api-contract-definition.md |
| epic-task-breakdown | 10 | skills/epic-task-breakdown.md |
| project-risk-tracker | 10 | skills/project-risk-tracker.md |
| fullstack-implementation | 04 | skills/fullstack-implementation.md |
| unit-test-generator | 04 | skills/unit-test-generator.md |
| refactoring-guide | 04 | skills/refactoring-guide.md |
| vulnerability-scanning | 05 | skills/vulnerability-scanning.md |
| owasp-top10-audit | 05 | skills/owasp-top10-audit.md |
| test-case-generation | 06 | skills/test-case-generation.md |
| test-report-summary | 06 | skills/test-report-summary.md |
| regulatory-compliance-check | 07 | skills/regulatory-compliance-check.md |
| data-privacy-audit | 07 | skills/data-privacy-audit.md |
| data-metrics-design | 08 | skills/data-metrics-design.md |
| ab-test-analysis | 08 | skills/ab-test-analysis.md |
| cicd-pipeline-config | 09 | skills/cicd-pipeline-config.md |
| incident-response-sop | 09 | skills/incident-response-sop.md |
