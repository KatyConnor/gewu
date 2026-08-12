# 格物平台 — 文档中心

> 最后更新：2026-07-29

---

## 快速导航

| 文档 | 说明 |
|------|------|
| [格物平台-项目资料总览.md](格物平台-项目资料总览.md) | **项目资料总览（整合所有文档）** |
| [格物智能体平台需求规格说明书.md](格物智能体平台需求规格说明书.md) | **需求规格说明书（SRS）** |

---

| 目录 | 内容 | 文档数 |
|------|------|--------|
| [wenshi/](wenshi/) | Wenshi 知识增强架构文档集 | 8 |
| [design/](design/) | 技术设计文档（架构/数据库/安全/工作流等） | 22 |
| [api/](api/) | API 接口文档 | 6 |
| [ops/](ops/) | 运维手册（运行手册/灾难恢复） | 2 |
| [UAT/](UAT/) | UAT 验收计划与上线方案 | 3 |
| [user/](user/) | 用户手册 | 1 |
| [migration/](migration/) | 迁移指南文档集 | 7 |
| [project/](project/) | 项目级文档（计划/报告/架构全景） | 7 |
| [archive/](archive/) | 历史归档文档（仅供参考） | 16 |

---

## 项目简介

**格物平台（gewu-platform）**是一个 AI 驱动的智能开发协作平台，提供 Agent 智能体会话、工作流编排、代码沙箱执行、项目管理等核心能力。

**Wenshi（格物知行）**是平台的 LLM 增强架构，通过知识层+推理层+学习层三层增强，为 LLM 提供长期记忆、业务知识和经验积累能力。

---

## 文档详情

### wenshi/ — Wenshi 知识增强架构

| 文档 | 说明 | 状态 |
|------|------|------|
| [WENSHI-ARCHITECTURE-v3.md](wenshi/WENSHI-ARCHITECTURE-v3.md) | Wenshi 完整架构方案（三层认知操作系统） | ✅ 当前 |
| [WENSHI-SYSTEM-DESIGN.md](wenshi/WENSHI-SYSTEM-DESIGN.md) | 整体系统设计与现有架构对接方案 | ✅ 当前 |
| [WENSHI-DEVELOPMENT-PLAN.md](wenshi/WENSHI-DEVELOPMENT-PLAN.md) | 四阶段开发计划（A/B/C/D） | ✅ 当前 |
| [WENSHI-PHASE-A-REVIEW.md](wenshi/WENSHI-PHASE-A-REVIEW.md) | 阶段 A 验收报告 | ✅ 当前 |
| [WENSHI-POC-REPORT.md](wenshi/WENSHI-POC-REPORT.md) | PoC 验证报告 | ✅ 当前 |
| [WENSHI-CODE-REVIEW.md](wenshi/WENSHI-CODE-REVIEW.md) | 代码审查+安全审计报告 | ✅ 当前 |
| [WENSHI-COMPREHENSIVE-REVIEW.md](wenshi/WENSHI-COMPREHENSIVE-REVIEW.md) | 综合审计报告（含知识图谱分析） | ✅ 当前 |
| [WENSHI-REVIEW-V2-AFTER-FIX.md](wenshi/WENSHI-REVIEW-V2-AFTER-FIX.md) | 修复后二次审计报告 | ✅ 当前 |

### design/ — 技术设计

| 文档 | 说明 | 状态 |
|------|------|------|
| [01-technical-architecture.md](design/01-technical-architecture.md) | 技术架构设计 | ✅ 当前 |
| [02-deployment-architecture.md](design/02-deployment-architecture.md) | 部署架构设计 | ✅ 当前 |
| [03-database-design.md](design/03-database-design.md) | 数据库设计 | ✅ 当前 |
| [06-monitoring-alerting.md](design/06-monitoring-alerting.md) | 监控告警设计 | ✅ 当前 |
| [07-security-compliance.md](design/07-security-compliance.md) | 安全合规设计 | ✅ 当前 |
| [17-product-requirements.md](design/17-product-requirements.md) | 产品需求文档 | ✅ 当前 |
| [19-gap-analysis.md](design/19-gap-analysis.md) | 差距分析 | ✅ 当前 |
| [20-unified-prd.md](design/20-unified-prd.md) | 统一 PRD | ✅ 当前 |
| [21-unified-architecture.md](design/21-unified-architecture.md) | 统一架构 | ✅ 当前 |
| [22-unified-db-schema.md](design/22-unified-db-schema.md) | 统一数据库 Schema | ✅ 当前 |
| [23-unified-api-spec.md](design/23-unified-api-spec.md) | 统一 API 规范 | ✅ 当前 |
| [24-unified-security.md](design/24-unified-security.md) | 统一安全方案 | ✅ 当前 |
| [25-unified-deployment.md](design/25-unified-deployment.md) | 统一部署方案 | ✅ 当前 |
| [26-migration-guide.md](design/26-migration-guide.md) | 迁移指南 | ✅ 当前 |
| [27-agent-sandbox-design.md](design/27-agent-sandbox-design.md) | Agent 沙箱设计 | ✅ 当前 |
| [28-workflow-engine-design.md](design/28-workflow-engine-design.md) | 工作流引擎设计 | ✅ 当前 |
| [29-xinchuang-compliance.md](design/29-xinchuang-compliance.md) | 信创合规 | ✅ 当前 |
| [30-workflow-api-design.md](design/30-workflow-api-design.md) | 工作流 API 设计 | ✅ 当前 |
| [31-workflow-ui-design.md](design/31-workflow-ui-design.md) | 工作流 UI 设计 | ✅ 当前 |
| [32-test-strategy.md](design/32-test-strategy.md) | 测试策略 | ✅ 当前 |
| [33-dev-roadmap.md](design/33-dev-roadmap.md) | 开发路线图 | ✅ 当前 |
| [34-sandbox-lifecycle-implementation-plan.md](design/34-sandbox-lifecycle-implementation-plan.md) | 沙箱生命周期实现计划 | ✅ 当前 |

### api/ — API 接口文档

| 文档 | 说明 |
|------|------|
| [overview.md](api/overview.md) | API 概览 |
| [API-DOCUMENTATION.md](api/API-DOCUMENTATION.md) | 完整 API 文档 |
| [user-api.md](api/user-api.md) | 用户 API |
| [project-api.md](api/project-api.md) | 项目 API |
| [session-api.md](api/session-api.md) | 会话 API |
| [agent-api.md](api/agent-api.md) | Agent API |

### ops/ — 运维文档

| 文档 | 说明 |
|------|------|
| [OPERATIONS-MANUAL.md](ops/OPERATIONS-MANUAL.md) | 运维手册 |
| [DISASTER-RECOVERY.md](ops/DISASTER-RECOVERY.md) | 灾难恢复方案 |

### UAT/ — 验收文档

| 文档 | 说明 |
|------|------|
| [UAT-PLAN.md](UAT/UAT-PLAN.md) | UAT 验收计划 |
| [GO-LIVE-PLAN.md](UAT/GO-LIVE-PLAN.md) | 上线计划 |
| [POST-LAUNCH-MONITORING.md](UAT/POST-LAUNCH-MONITORING.md) | 上线后监控 |

### user/ — 用户文档

| 文档 | 说明 |
|------|------|
| [USER-MANUAL.md](user/USER-MANUAL.md) | 用户手册 |

### migration/ — 迁移指南

| 文档 | 说明 |
|------|------|
| [MIGRATION-GUIDE.md](migration/MIGRATION-GUIDE.md) | 迁移指南 |
| [MIGRATION-DESIGN.md](migration/MIGRATION-DESIGN.md) | 迁移设计 |
| [MIGRATION-CHECKLIST.md](migration/MIGRATION-CHECKLIST.md) | 迁移检查清单 |
| [MIGRATION-BEST-PRACTICES.md](migration/MIGRATION-BEST-PRACTICES.md) | 迁移最佳实践 |
| [MIGRATION-FAQ.md](migration/MIGRATION-FAQ.md) | 迁移 FAQ |
| [MIGRATION-CASE-STUDY.md](migration/MIGRATION-CASE-STUDY.md) | 迁移案例研究 |
| [MIGRATION-TEMPLATE.md](migration/MIGRATION-TEMPLATE.md) | 迁移模板 |

### project/ — 项目文档

| 文档 | 说明 | 状态 |
|------|------|------|
| [CHANGELOG.md](project/CHANGELOG.md) | 变更日志 | ✅ 当前 |
| [PROJECT-PLAN.md](project/PROJECT-PLAN.md) | 项目计划 | ✅ 当前 |
| [PROJECT-COMPLETION-REPORT.md](project/PROJECT-COMPLETION-REPORT.md) | 项目完成报告 | ✅ 当前 |
| [PROJECT-RESEARCH-REPORT.md](project/PROJECT-RESEARCH-REPORT.md) | 项目研究报告 | ✅ 当前 |
| [PLATFORM-ARCHITECTURE-FULL.md](project/PLATFORM-ARCHITECTURE-FULL.md) | 平台全景架构分析 | ✅ 当前 |
| [PHASE0-TASKS.md](project/PHASE0-TASKS.md) | Phase 0 任务清单 | ✅ 当前 |
| [SPRINT-9-COMPLETION-REPORT.md](project/SPRINT-9-COMPLETION-REPORT.md) | Sprint 9 完成报告 | ✅ 当前 |

### archive/ — 历史归档

> 以下文档为历史版本或已完成的技术探索，仅供参考，不代表当前方案。

| 文档 | 说明 | 归档原因 |
|------|------|---------|
| WENSHI-ARCHITECTURE-v2.md | Wenshi v2 架构方案 | 已被 v3 替代 |
| COMPREHENSIVE-REVIEW-V2.md | 综合评审 V2.0 | 已被 WENSHI 系列替代 |
| COMPREHENSIVE-REVIEW-V2.1.md | 综合评审 V2.1 | 已被 WENSHI 系列替代 |
| COMPREHENSIVE-REVIEW-V2.2.md | 综合评审 V2.2 | 已被 WENSHI 系列替代 |
| ARCHITECTURE-REVIEW.md | 架构评审报告 | 已被 PLATFORM-ARCHITECTURE-FULL 替代 |
| SP-01-oceanbase-k8s.md | OceanBase on K8s 探索 | 已完成 |
| SP-02-crypto-performance.md | 国密性能探索 | 已完成 |
| SP-03-xinchuang-compat.md | 信创兼容性探索 | 已完成 |
| SP-04-reactflow-performance.md | ReactFlow 性能探索 | 已完成 |
| SPRINT-0-completion-report.md | Sprint 0 完成报告 | 已完成 |
| SPRINT-1-completion-report.md | Sprint 1 完成报告 | 已完成 |
| SPRINT-11-completion-report.md | Sprint 11 完成报告 | 已完成 |
| SPRINT-12-completion-report.md | Sprint 12 完成报告 | 已完成 |
| SPRINT-13-completion-report.md | Sprint 13 完成报告 | 已完成 |

---

## 文档维护规范

1. **新增文档**：放入对应子目录，并更新本索引
2. **版本迭代**：保留最新版，旧版移至 `archive/`
3. **命名规范**：`编号-主题.md`（design/ 目录）或 `THEME-NAME.md`（其他目录）
4. **状态标注**：文档头部标注版本、日期、状态（草稿/评审中/已批准/已归档）
