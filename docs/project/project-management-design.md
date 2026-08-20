# 项目管理功能完善 — 实施文档

> 版本：V1.0  
> 日期：2026-07-27  
> 状态：编码实施中

---

## 一、设计概览

### 数据模型关系

```
project (已有 + 新增 3 字段)
  ├── project_phase × 17 (新建)
  │     └── phase_document × N (新建)
  │           ├── review_status / reviewed_by / review_comment (审核字段)
  │           └── phase_document_version × N (新建, 版本历史)
  └── project_member (已有)
```

### 生命周期阶段（17 阶段）

| # | 编码 | 名称 | 关联 Agent | 可回退 | 必须文档 |
|---|------|------|------------|--------|----------|
| 1 | `RESEARCH` | 项目调研 | 需求分析 Agent | ✅ | 调研报告 |
| 2 | `PROTOTYPE` | 原型设计 | 原型设计 Agent | ✅ | 原型图/交互稿 |
| 3 | `INITIATION` | 立项中 | 项目规划 Agent | ✅ | 立项申请书 |
| 4 | `REQUIREMENT_REVIEW` | 需求评审 | 需求评审 Agent | ✅ | PRD 文档 |
| 5 | `DESIGN_REVIEW` | 设计评审 | 架构评审 Agent | ✅ | 技术方案/架构图 |
| 6 | `ESTIMATION` | 工作量评估 | 评估 Agent | ✅ | 工作量评估表 |
| 7 | `PLANNING` | 计划制定 | 计划制定 Agent | ✅ | 项目计划/甘特图 |
| 8 | `DEVELOPMENT` | 开发中 | 开发助手 Agent | ❌ | — |
| 9 | `SELF_TEST` | 开发自测 | 测试 Agent | ❌ | 自测报告 |
| 10 | `SMOKE_TEST` | 冒烟测试 | 测试 Agent | ❌ | 冒烟测试报告 |
| 11 | `PENDING_SIT` | 待 SIT 测试 | 测试 Agent | ❌ | SIT 准入 checklist |
| 12 | `SIT_TEST` | SIT 测试 | 测试 Agent | ❌ | SIT 测试报告 |
| 13 | `PENDING_UAT` | 待 UAT 测试 | 测试 Agent | ❌ | UAT 准入 checklist |
| 14 | `UAT_TEST` | UAT 测试 | 测试 Agent | ❌ | UAT 验收报告 |
| 15 | `PENDING_RELEASE` | 等待上线 | 运维 Agent | ❌ | 上线 checklist |
| 16 | `RELEASED` | 上线完成/部分上线 | 运维 Agent | ❌ | 上线报告 |
| 17 | `CLOSED` | 已结项 | 项目总结 Agent | ❌ | 结项报告 |

---

## 二、业务规则

| # | 规则 | 约束 |
|---|------|------|
| 1 | 阶段初始化 | 项目创建时自动创建 17 条阶段记录，状态均为"未开始" |
| 2 | 顺序推进 | 严格按序推进，不可跳过阶段 |
| 3 | 强制文档 | 标记阶段"已完成"前必须至少上传 1 份文档 |
| 4 | 回退限制 | 阶段 1-7（调研→计划制定）可回退；阶段 8（开发中）及以后不可回退 |
| 5 | 操作权限 | OWNER + ADMIN 角色可变更阶段状态 |
| 6 | 编辑约束 | 仅 `draft`/`pending`/`rejected` 状态可编辑文档 |
| 7 | 修改后强制评审 | 文档修改后状态变为 `pending`，弹框提示"是否提交 Agent 评审" |
| 8 | 锁定保护 | `in_review` 和 `approved` 状态下编辑按钮置灰，不可修改 |

### 文档审核状态机

```
draft ──保存──→ pending ──Agent评审──→ in_review ──通过──→ approved (🔒)
  ↑                ↑                      │
  │                │                      └──驳回──→ rejected ──修改──→ pending
  └──修改后────────┘
```

---

## 三、数据库 DDL

[参考 V7__project_lifecycle.sql]

---

## 四、后端 API

### 项目阶段

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/v1/projects/{id}/phases` | 阶段列表 |
| `PUT` | `/api/v1/projects/{id}/phases/{code}/start` | 开始阶段 |
| `PUT` | `/api/v1/projects/{id}/phases/{code}/complete` | 完成阶段 |
| `PUT` | `/api/v1/projects/{id}/phases/{code}/revert` | 回退阶段 |
| `POST` | `/api/v1/projects/{id}/phases/{code}/chat` | 阶段 Agent SSE 对话 |

### 文档管理

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/v1/documents/{id}` | 文档详情 |
| `GET` | `/api/v1/documents/{id}/content` | 文档正文 |
| `PUT` | `/api/v1/documents/{id}/content` | 编辑保存 |
| `POST` | `/api/v1/projects/{pid}/phases/{code}/docs` | 上传文档 |
| `POST` | `/api/v1/projects/{pid}/phases/{code}/docs/md` | 创建 Markdown 文档 |
| `DELETE` | `/api/v1/documents/{id}` | 删除文档 |
| `GET` | `/api/v1/documents/{id}/versions` | 版本历史 |
| `GET` | `/api/v1/documents/{id}/versions/{vno}` | 指定版本 |
| `POST` | `/api/v1/documents/{id}/versions/{v1}/diff/{v2}` | 版本对比 |
| `POST` | `/api/v1/documents/{id}/submit-review` | 提交审核 |
| `POST` | `/api/v1/documents/{id}/agent-review` | Agent 评审 |

---

## 五、实施任务清单

| 步骤 | 任务 | 验证 |
|------|------|------|
| P1 | docker-compose 增加 MinIO | `docker compose up -d minio` |
| P2 | V7 迁移脚本 | SQL 执行 |
| P3 | Domain 实体 | `mvn compile` |
| P4 | Infrastructure Mapper | `mvn compile` |
| P5 | MinioStorageService | `mvn test` |
| P6 | ProjectPhaseService | `mvn test` |
| P7 | PhaseDocumentService + VersionService | `mvn test` |
| P8 | ProjectController 扩展 | curl 测试 |
| P9 | ProjectDocumentController | curl 测试 |
| P10 | 前端 API 封装 project.ts | `npx tsc` |
| P11 | ProjectsPage 卡片列表 | `npx tsc` |
| P12 | ProjectDetailPage 时间线 | `npx tsc` |
| P13 | DocEditor + VersionDiff | `npx tsc` |
| P14 | ChatPage 阶段会话集成 | `npx tsc` |
| P15 | 编译验证 + 回归测试 | `mvn test` + `npx tsc` |
