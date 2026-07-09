# 格物平台 - 差异分析报告：OpenCode × 格物

## 文档信息

| 项目 | 内容 |
|------|------|
| 文档名称 | 差异分析报告 |
| 版本 | V1.0 |
| 创建日期 | 2026-07-08 |
| 分析范围 | opencode-1.17.14 ⟷ gewu-platform |
| 分析维度 | 产品需求、架构、数据库、API、安全、部署 |

---

## 目录

1. [分析概述](#1-分析概述)
2. [产品需求差异分析](#2-产品需求差异分析)
3. [架构差异分析](#3-架构差异分析)
4. [数据库差异分析](#4-数据库差异分析)
5. [API 差异分析](#5-api-差异分析)
6. [安全差异分析](#6-安全差异分析)
7. [功能覆盖矩阵](#7-功能覆盖矩阵)
8. [迁移映射](#8-迁移映射)
9. [合并策略与建议](#9-合并策略与建议)

---

## 1. 分析概述

### 1.1 两个项目的关系

| 维度 | OpenCode 1.17.14 | 格物平台 (gewu-platform) |
|------|-----------------|------------------------|
| **定位** | 开源 AI 编码代理 (单人工具) | 企业级 AI 智能开发协作平台 (团队协作) |
| **技术栈** | Bun 1.3.14 + TypeScript + Effect 4 (函数式) | JDK 21 + Spring Boot 3 + Spring Cloud (面向对象) |
| **前端** | SolidJS 1.9.10 + OpenTUI (CLI/TUI/Web) | React 18 + TypeScript (Web) |
| **数据库** | SQLite + Drizzle ORM | OceanBase 4.2+ + MyBatis-Plus |
| **部署** | 单机/Docker | 分布式集群/K8s |
| **用户规模** | 单人开发 | 500+ 并发用户 |
| **安全等级** | 基础权限/沙箱 | 等保 2.0 三级 + 国密 SM2/SM3/SM4 |
| **信创要求** | 无 | 全面信创合规 |

### 1.2 关键发现

**OpenCode 对格物平台的参考价值**：
- Agent 系统设计（会话管理、工具系统、MCP 集成）是格物平台 Agent 模块的直接参考源
- OpenCode 的 27 条业务规则可以直接映射到格物平台的 38 个用户故事
- OpenCode 的 11 个核心数据表设计提供了格物平台表结构设计的重要参考

**格物平台对 OpenCode 的超越**：
- 企业级功能：工作流引擎、监控告警、API 网关、沙箱隔离、审计日志
- 信创合规：国密算法、国产数据库/中间件/操作系统
- 多租户架构：支持企业级团队协作和权限隔离
- 7 种专业化用户角色 vs OpenCode 的 3 种泛化角色

---

## 2. 产品需求差异分析

### 2.1 用户角色对比

| OpenCode 角色 | 格物平台角色 | 差异 |
|-------------|------------|------|
| 开发者 | 后端开发 / 前端开发 | 格物细分为前后端角色 |
| 项目管理者 | 技术负责人 / 产品经理 | 格物细分为管理和产品角色 |
| 团队协作者 | 架构师 / 测试 / 运维 / 安全 | 格物扩展为 4 个专业化角色 |

**格物平台独有角色**：架构师、测试工程师、运维工程师、安全工程师、技术负责人、产品经理

### 2.2 用户故事对比

| 能力域 | OpenCode 用户故事数 | 格物用户故事数 | 重叠 | 格物独有 |
|-------|-------------------|--------------|------|---------|
| 用户管理 | 0 | 6 (US-UM-01~06) | 0 | 6 |
| 项目管理 | 1 (查看/创建项目) | 6 (US-PM-01~06) | 1 | 5 |
| 会话/消息 | 4 (创建/发送/历史/共享) | 5 (US-SM-01~05) | 3 | 2 (已读/@提及) |
| Agent 系统 | 3 (Agent/工具/权限) | 6 (US-AG-01~06) | 3 | 3 (编排/历史/会话调用) |
| 工具系统 | 4 (工具执行/验证/超时/结果) | 0 (内置在 Agent) | 4(映射) | - |
| MCP 集成 | 4 (连接/OAuth/发现/资源) | 0 (信创替代) | 0→迁移 | - |
| 工作流引擎 | 0 | 5 (US-WF-01~05) | 0 | 5 |
| 沙箱隔离 | 1 (文件系统沙箱) | 4 (US-SB-01~04) | 1 | 3 (运行时切换/资源限制/安全策略) |
| API 网关 | 0 | 4 (US-GW-01~04) | 0 | 4 |
| AI 增强 | 3 (问答/代码补全/审查) | 4 (US-AI-01~04) | 3 | 1 (自动测试生成) |
| 监控告警 | 0 | 4 (US-MN-01~04) | 0 | 4 |

**总计**：OpenCode 15 个核心故事 → 格物 38 个故事，重叠约 15 个，格物独有 23 个。

### 2.3 OpenCode 业务规则映射

| OpenCode 规则 | 格物映射 | 状态 |
|-------------|---------|------|
| **1. 会话管理规则 (4条)** | | |
| 会话创建 + 关联项目 | → F-SM-01 会话 CRUD | ✅ 已覆盖 |
| 会话标题自动生成 | → 需增强 (会话标题自动命名) | 🔧 需补充 |
| 会话版本管理 | → 格物使用 ULID + 乐观锁 | ✅ 已覆盖 |
| 会话归档 | → session 表增加 status 字段 | ✅ 已覆盖 |
| 会话共享 | → session 表 is_public 字段 | ✅ 已覆盖 |
| **2. 消息处理规则 (4条)** | | |
| 多种消息类型 | → message_type 字段支持多种类型 | ✅ 已覆盖 |
| 消息部分 (Part) | → 需 merge (OpenCode 的 part 表) | 🔧 需补充 |
| 消息持久化 | → session_message 表 | ✅ 已覆盖 |
| 流式传输 | → F-SM-03 SSE 实时推送 | ✅ 已覆盖 |
| **3. 工具执行规则 (4条)** | | |
| 权限控制 | → agent_permission 表 | ✅ 已覆盖 |
| 参数 Schema 验证 | → agent_tool 表 request_schema | ✅ 已覆盖 |
| 工具超时 (30s) | → agent_tool 表 timeout_ms | ✅ 已覆盖 |
| 结果大小限制 | → 需补充配置 | 🔧 需补充 |
| **4. MCP 集成规则 (4条)** | | |
| 连接管理 (stdio/SSE/HTTP) | → 信创环境不支持 MCP，需替换为国产协议 | ❌ 需替换 |
| OAuth 认证 | → 使用 SM2 加密替代 | ❌ 需替换 |
| 工具发现 | → 自研工具注册表 (agent_tool) | ✅ 已覆盖 |
| 资源管理 | → 自研资源管理模块 | 🔧 需补充 |
| **5. 权限系统规则 (4条)** | | |
| 细粒度权限 | → RBAC + agent_permission | ✅ 已覆盖 |
| 权限持久化 | → 数据库持久化 | ✅ 已覆盖 |
| 自动响应 | → 需在工作流引擎中实现 | 🔧 需补充 |
| 权限继承 | → 父子会话/项目继承 | 🔧 需补充 |
| **6. 配置管理规则 (4条)** | | |
| 多配置源 | → Spring Cloud Config | ✅ 已覆盖 |
| Schema 验证 | → JSR 303 + 自定义验证 | ✅ 已覆盖 |
| 热重载 | → Spring Cloud Bus + Nacos | ✅ 已覆盖 |
| 配置迁移 | → 版本化配置管理 | 🔧 需补充 |

---

## 3. 架构差异分析

### 3.1 总体架构对比

| 维度 | OpenCode | 格物平台 |
|------|---------|---------|
| **架构风格** | 函数式 (Effect) + 事件驱动 | DDD 分层 + 微服务 |
| **前端** | SolidJS + OpenTUI (TUI/CLI) | React 18 + TypeScript |
| **API 层** | Hono + Effect HTTP | 自研 API 网关 (Java/Netty) |
| **业务层** | 6 个核心模块 (扁平) | 9 个模块 (DDD Domain) |
| **数据层** | SQLite (单文件) | OceanBase 分布式 |
| **缓存** | 无 (轻量内存) | DragonflyDB |
| **消息队列** | Event 系统 (内存级) | RocketMQ |
| **部署** | 单机 | K8s 集群 |

### 3.2 模块映射

| OpenCode 模块 | 格物模块 | 映射方式 |
|-------------|---------|---------|
| **Session Manager** | F-SM 会话管理 | 直接映射，增强团队协作 |
| **Agent System** | F-AG Agent 系统 | 直接映射，增强编排能力 |
| **Tool System** | → F-AG-02 工具注册 | 合并入 Agent 系统 |
| **MCP Integration** | → 替换为自研工具/协议 | 因信创要求替换 |
| **Permission System** | F-UM-04 RBAC + F-AG-03 | 拆分到用户管理和 Agent |
| **Config Management** | Spring Cloud Config | 替换为 Spring 生态 |
| **Server/API** | F-GW API 网关 | 从 Hono 替换为自研网关 |
| **Plugin System** | → F-WF 工作流引擎 | 升级为工作流驱动 |
| **CLI/TUI/Desktop** | → React Web (暂不包含桌面) | 精简前端平台 |

### 3.3 OpenCode 独有的架构特性（需评估是否纳入格物）

| 特性 | 描述 | 是否纳入格物 | 理由 |
|------|------|------------|------|
| **Effect 函数式框架** | 强大的错误处理 + 依赖注入 | ❌ 不纳入 | 格物使用 Spring/Java，范式不兼容 |
| **Event Sourcing** | event_sequence + event 表 | ⚠️ 部分纳入 | 审计日志使用事件溯源思想 |
| **V2 会话架构** | SessionV2 + SessionInput + SessionContextEpoch | ⚠️ 参考 | 格物会话设计可借鉴版本管理 |
| **SSE 事件流** | 实时 SSE 推送 | ✅ 已纳入 | F-SM-03 SSE 实时推送 |
| **父子会话** | parent_id 递归 | ⚠️ 参考 | 格物 session 表暂不支持，后续可加 |
| **会话压缩** | 上下文过长时自动压缩 | ✅ 纳入 | 对于 AI 会话场景重要 |

---

## 4. 数据库差异分析

### 4.1 表覆盖矩阵

| OpenCode 表 | 格物对应表 | 兼容性 | 说明 |
|------------|----------|--------|------|
| session (25字段) | session (17字段) | ⚠️ 部分 | 格物缺少: parent_id, slug, directory, version, share_url, summary_*, cost, tokens_*, revert, permission, agent, model |
| message (5字段) | session_message (14字段) | ⚠️ 部分 | 格物增加了: sender_id, message_type, metadata, reply_to, mention_user_ids, client_id, edited |
| part (6字段) | ❌ 无对应表 | ❌ 缺失 | **需新增** part 表存储消息的多个部分 |
| todo (6字段) | ❌ 无对应表 | ❌ 缺失 | 格物中待办功能在工作流引擎中实现 |
| session_message (7字段) | session_message (已合并) | ✅ 合并 | 与 message 合并为一个表 |
| session_input (7字段) | ❌ 无对应表 | ❌ 缺失 | **需新增** 用于 AI 会话输入管理 |
| session_context_epoch (4字段) | ❌ 无对应表 | ❌ 缺失 | **需新增** 用于 AI 上下文快照 |
| project (12字段) | project (13字段) | ⚠️ 部分 | 格物增加了: visibility, owner_id, tech_stack(JSON) |
| project_directory (5字段) | ❌ 无对应表 | ❌ 缺失 | **需新增** 用于代码项目管理 |
| event_sequence (3字段) | ❌ 无对应表 | ❌ 缺失 | 事件溯源表，格物暂不需要 |
| event (5字段) | audit_log (13字段) | ⚠️ 替换 | 格物用 audit_log 替代 event 系统，更聚焦审计 |

**格物独有表**（OpenCode 无对应）：
- user_account, role, permission, user_role, role_permission (用户权限域 - 新增 5 表)
- project_member (项目成员 - 新增)
- session_member (会话成员 - 新增)
- agent, agent_tool, agent_permission, agent_execution (Agent 系统 - 新增 4 表)
- workflow, workflow_node, workflow_transition, workflow_instance, workflow_node_instance (工作流引擎 - 新增 6 表)
- audit_log, api_key (审计安全 - 新增 2 表)
- sandbox_config (沙箱配置 - 新增 1 表)

### 4.2 字段级差异（关键表）

#### session 表差异

| OpenCode 字段 | 格物字段 | 差异 |
|-------------|---------|------|
| id TEXT PK | id VARCHAR(26) PK | ULID vs TEXT, 格式不同 |
| project_id FK | project_id FK | 相同 |
| parent_id | ❌ | 格物暂不支持父子会话 |
| slug + directory + path | ❌ | 用于文件系统工作目录，格物暂不需要 |
| title | title | 相同 |
| version | ❌ | 格物使用乐观锁 version |
| share_url | ❌ | 格物使用 is_public |
| summary_* / cost / tokens_* | ❌ | 格物暂不追踪 AI 成本/统计 |
| ❌ | type TINYINT | 格物新增: 群聊/私聊/AI辅助 |
| ❌ | last_message_at | 格物新增: 最后消息时间 |
| ❌ | message_count | 格物新增: 消息总数 |
| ❌ | is_public | 格物新增: 是否公开 |

#### project 表差异

| OpenCode 字段 | 格物字段 | 差异 |
|-------------|---------|------|
| worktree | ❌ | 工作树路径，格物使用 Git 仓库概念 |
| vcs | ❌ | 版本控制信息 |
| sandboxes JSON | ❌ | 格物使用独立 sandbox_config 表 |
| commands JSON | ❌ | 格物暂不需要 |
| ❌ | visibility | 格物新增 |
| ❌ | owner_id | 格物新增 |
| ❌ | tech_stack JSON | 格物新增 |
| ❌ | deleted/version (审计) | 格物标准审计字段 |

---

## 5. API 差异分析

### 5.1 API 接口对比

| OpenCode API | 格物 API | 状态 |
|-------------|---------|------|
| **会话管理** | | |
| POST /session | POST /api/v1/session | ✅ 已设计 |
| GET /session | GET /api/v1/session | ✅ 已设计 |
| GET /session/:id | GET /api/v1/session/{id} | ✅ 已设计 |
| POST /session/:id/archive | PUT /api/v1/session/{id}/archive | ✅ 已设计 |
| **消息管理** | | |
| POST /session/:id/message | POST /api/v1/session/{id}/message | ✅ 已设计 |
| GET /session/:id/messages | GET /api/v1/session/{id}/messages | ✅ 已设计 |
| **工具管理** | | |
| POST /tool/execute | POST /api/v1/agent/execute | 🔧 重命名 URL |
| GET /tool | GET /api/v1/agent/tools | 🔧 重命名 URL |
| **MCP 管理** | | |
| POST /mcp/connect | ❌ 信创替换 | ❌ 需设计国产协议接口 |
| POST /mcp/disconnect | ❌ 信创替换 | ❌ 需设计国产协议接口 |
| **配置管理** | | |
| GET /config | 使用 Nacos/Spring Cloud Config | ❌ 替换为配置中心 |
| PUT /config | 使用 Nacos/Spring Cloud Config | ❌ 替换为配置中心 |
| **项目管理** | | |
| GET /project | GET /api/v1/projects | ✅ 已设计 |
| POST /project | POST /api/v1/projects | ✅ 已设计 |
| **事件流** | | |
| GET /event/subscribe | GET /api/v1/events/subscribe | ✅ 已设计 (SSE) |
| **格物独有 API** | | |
| ❌ | 用户管理 API (5 接口) | ✅ 已设计 |
| ❌ | 角色权限 API (4 接口) | ✅ 已设计 |
| ❌ | 工作流 API (8 接口) | ✅ 已设计 |
| ❌ | 监控 API (3 接口)  | ✅ 已设计 |
| ❌ | 沙箱管理 API (3 接口) | ✅ 已设计 |

### 5.2 接口规范差异

| 规范项 | OpenCode | 格物 |
|-------|---------|------|
| 基础路径 | `/api/v1` | `/api/v1` |
| 认证方式 | OAuth 2.0 + API Key | JWT + API Key + SM2 加密 |
| 响应格式 | JSON | JSON |
| 错误格式 | 统一 `{"error":{}}` | 统一 `{"code","message","requestId"}` |
| 限流 | 100/20/10 次/分钟 | 动态令牌桶 |
| 版本控制 | URL 路径版本 | Accept Header + 路径版本 |

---

## 6. 安全差异分析

| 安全维度 | OpenCode | 格物平台 | 差异 |
|---------|---------|---------|------|
| **密码加密** | 基础哈希 | SM3 + 盐值 | 格物使用国密 |
| **传输加密** | HTTPS (可选项) | 国密 TLS + SM2/SM4 | 格物全面国密 |
| **认证** | OAuth 2.0 | JWT + 国密签名 | 格物使用 SM2 签名的 JWT |
| **授权** | 基础权限 (allow/deny/ask) | RBAC + 资源级权限 | 格物企业级 |
| **沙箱** | 文件系统/Shell/网络沙箱 | Docker/gVisor/Firecracker | 格物容器化 |
| **审计** | Event 系统 | audit_log 全量审计 | 格物更完整 |
| **等保** | 无 | 等保 2.0 三级 | 格物完整合规 |
| **信创** | 无 | 信创目录全面适配 | 格物完整合规 |
| **国密** | 无 | SM2/SM3/SM4 全系列 | 格物完整国密 |
| **密钥管理** | 无 | api_key 表 + SM3 | 格物完整密钥管理 |

---

## 7. 功能覆盖矩阵

### 7.1 OpenCode 功能 → 格物覆盖状态

| OpenCode 功能 | 优先级 (OC) | 格物覆盖度 | 状态 |
|-------------|-----------|----------|------|
| 会话创建/归档/共享 | P0 | 100% | ✅ 已覆盖 |
| 消息发送/流式传输 | P0 | 100% | ✅ 已覆盖 |
| 消息历史/回溯 | P0 | 100% | ✅ 已覆盖 |
| 父子会话 | P1 | 0% | ❌ 未覆盖 → 待设计 |
| Agent 定义/配置 | P0 | 100% | ✅ 已覆盖 |
| 工具注册/执行 | P0 | 100% | ✅ 已覆盖 |
| 工具参数验证 | P0 | 100% | ✅ 已覆盖 |
| Agent 权限评估 | P0 | 100% | ✅ 已覆盖 |
| MCP 集成 (3协议) | P1 | 0% | ❌ 信创替换 |
| 文件系统沙箱 | P0 | 100% (Docker) | ✅ 升级覆盖 |
| Shell 沙箱 | P0 | 100% (Docker) | ✅ 升级覆盖 |
| 网络沙箱 | P1 | 100% (容器网络) | ✅ 升级覆盖 |
| 权限自动响应 | P1 | 50% | 🔧 需补充工作流节点 |
| 配置热重载 | P1 | 100% (Nacos) | ✅ 替换覆盖 |
| 会话共享 | P1 | 100% (is_public) | ✅ 已覆盖 |
| 会话版本管理 | P2 | 0% | ❌ 未覆盖 |

### 7.2 格物独有功能（OpenCode 完全没有）

| 格物功能 | 优先级 | 模块 | 建议开发阶段 |
|---------|--------|------|------------|
| 用户注册/登录/SSO | P0 | F-UM | MVP |
| RBAC 角色管理 | P0 | F-UM | MVP |
| 国密密码加密 (SM3) | P0 | F-UM | MVP |
| 操作审计日志 | P1 | F-UM | Phase 2 |
| 项目 CRUD + 成员管理 | P0 | F-PM | MVP |
| 项目统计报表 | P1 | F-PM | Phase 2 |
| @提及通知 | P1 | F-SM | Phase 2 |
| 已读状态 | P1 | F-SM | Phase 2 |
| Agent 编排引擎 | P2 | F-AG | Phase 3+ |
| Agent 执行历史 | P1 | F-AG | Phase 2 |
| 工作流模板/实例 | P1 | F-WF | Phase 2 |
| 工作流可视化设计器 | P2 | F-WF | Phase 3+ |
| Docker 沙箱 | P0 | F-SB | MVP |
| 资源限制管理 | P1 | F-SB | Phase 2 |
| API 网关路由转发 | P0 | F-GW | MVP |
| 网关限流熔断 | P1 | F-GW | Phase 2 |
| 协议转换 (WebSocket/gRPC) | P1 | F-GW | Phase 2 |
| AI 代码补全 | P1 | F-AI | Phase 2 |
| AI 代码审查 | P1 | F-AI | Phase 2 |
| 自动测试生成 | P2 | F-AI | Phase 3+ |
| 系统/应用指标监控 | P0 | F-MN | MVP |
| 告警规则/多渠道通知 | P0 | F-MN | MVP |

---

## 8. 迁移映射

### 8.1 OpenCode → 格物 模块迁移表

| 原 OpenCode 概念 | 迁移后格物概念 | 迁移方式 | 复杂度 |
|----------------|--------------|---------|--------|
| Session + parent_id | session + parent_id (新增) | 直接映射 | 低 |
| Message (data JSON) | session_message + content | 结构更改，保留语义 | 中 |
| Part | agent_execution.tool_calls JSON | 部分迁移 | 中 |
| Todo | workflow_instance (工作流替代) | 概念升级 | 高 |
| SessionInput | session_input 表 (新增) | 直接迁移 | 低 |
| SessionContextEpoch | session_context_epoch (新增) | 直接迁移 | 低 |
| Project | project (字段调整) | 直接映射 | 低 |
| ProjectDirectory | project_directory (新增) | 直接迁移 | 低 |
| EventSequence | audit_log (替代) | 功能替换 | 中 |
| Event | audit_log (替代) | 功能替换 | 中 |
| MCP Client | 自研工具注册中心 | 架构替换 | 高 |
| Permission System | RBAC + agent_permission | 概念升级 | 中 |
| Plugin System | 工作流引擎 + 工具注册 | 架构升级 | 高 |
| Config System | Nacos Config | 平台替换 | 低 |
| Effect Layer | Spring 容器 | 框架替换 | 中 |

### 8.2 关键技术替换

| OpenCode 技术 | 格物替代方案 | 原因 |
|-------------|------------|------|
| Bun 1.3.14 | JDK 21 + Spring Boot 3 | Xinchuang + 企业级 |
| TypeScript 5.8 | Java 21 | 信创合规 + 性能 |
| Effect 4 | Spring + Virtual Threads | 范式迁移 |
| SolidJS 1.9 | React 18 | 团队技术栈 + 生态 |
| SQLite | OceanBase 4.2+ | 信创 + 分布式 |
| Drizzle ORM | MyBatis-Plus | 信创 + 团队习惯 |
| Hono | 自研 Netty 网关 | 信创自主可控 |
| Vercel AI SDK | 自研 AI 集成层 | 信创 + 多模型支持 |
| OpenTUI | React Web (暂不包含 TUI) | 产品定位调整 |
| SST | K8s + 容器化 | 企业级部署 |
| MCP | 自研工具协议 | 信创合规 |

---

## 9. 合并策略与建议

### 9.1 三阶段合并方案

| 阶段 | 时间 | 目标 | 关键任务 |
|------|------|------|---------|
| **Phase A: 吸收** | 1-2 周 | 将 OpenCode 的业务规则全面吸收到格物设计 | • 27条规则 → 格物功能点映射<br>• 11个数据表设计审查<br>• 6个核心模块整合 |
| **Phase B: 扩展** | 3-4 周 | 基于格物架构重新实现核心功能 | • Agent 系统 (参考 OpenCode)<br>• 会话系统 (增强团队协作)<br>• 工具系统 (信创适配) |
| **Phase C: 超越** | 5-6 周 | 实现格物独有功能，超越 OpenCode | • 工作流引擎<br>• 监控告警<br>• API 网关<br>• 沙箱隔离 |

### 9.2 需新增的表（从 OpenCode 迁移）

以下表需从 OpenCode 迁移到格物数据库设计，当前格物设计缺失：

| 表名 | 来源 | 用途 | 优先级 |
|------|------|------|--------|
| part | OpenCode | 消息多部分存储 | P1-AI场景 |
| session_input | OpenCode | AI 会话输入管理 | P1-AI场景 |
| session_context_epoch | OpenCode | AI 上下文快照 | P2-AI场景 |
| project_directory | OpenCode | 代码项目目录管理 | P1-项目管理 |

### 9.3 需增强的表（现有表补充字段）

| 表名 | 需补充字段 | 来源 | 优先级 |
|------|-----------|------|--------|
| session | parent_id, version, agent, model, share_url | OpenCode | P1 |
| project | worktree, vcs, sandboxes | OpenCode | P2 |

### 9.4 关键风险

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| MCP 替换为国产协议 | Agent 工具系统需重新设计 | 先实现基础工具注册，协议替换作为独立工作项 |
| Effect 函数式 → Spring OO | Agent 状态管理需重新设计 | 使用 Spring State Machine 替代 |
| SQLite → OceanBase | 事务模型和查询语法差异 | 使用 MyBatis-Plus 抽象层，统一 MySQL 兼容模式 |
| SSE 流式传输实现 | 高并发下连接管理 | 使用 Reactive (WebFlux) 或 Netty 实现 |
| 会话压缩算法移植 | AI 上下文管理策略重新实现 | 先实现基础版本，逐步优化 |

### 9.5 总结建议

1. **立即纳入**：OpenCode 的 27 条业务规则、6 个核心模块设计、11 个数据表设计作为格物平台的核心参考
2. **选择性纳入**：父子会话、会话版本管理、事件溯源（用于审计）等高级特性
3. **替换**：MCP 集成→国产工具协议；Effect→Spring；SQLite→OceanBase
4. **新增**：格物独有的 23 个用户故事对应的全部功能
5. **优先级**：Phase A 吸收规则（1-2周）→ Phase B 核心实现（3-4周）→ Phase C 超越（5-6周）
