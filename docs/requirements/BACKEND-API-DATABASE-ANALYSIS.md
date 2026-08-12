# 格物平台 — 后端功能接口与数据库实现分析报告

> **版本**: v1.0  
> **日期**: 2026-07-20  
> **分析范围**: 全部 Controller、Service、Mapper、DDL、安全、缓存  
> **数据库**: MySQL 8.0 (业务) + PostgreSQL 16 + pgvector (Wenshi 向量)  

---

## 目录

1. [系统架构概览](#1-系统架构概览)
2. [API 接口完整清单](#2-api-接口完整清单)
3. [功能逻辑深度分析](#3-功能逻辑深度分析)
4. [数据库设计分析](#4-数据库设计分析)
5. [安全体系分析](#5-安全体系分析)
6. [缓存与 Token 管理](#6-缓存与-token-管理)
7. [Wenshi 知识增强层](#7-wenshi-知识增强层)
8. [待完善事项](#8-待完善事项)

---

## 1. 系统架构概览

### 1.1 模块划分

| 模块 | 职责 | 包路径 |
|------|------|--------|
| gewu-interface | API 控制器层 | `com.gewu.interfaceapi.controller` |
| gewu-application | 业务服务层 | `com.gewu.application.*` |
| gewu-domain | 领域实体层 | `com.gewu.domain.*` |
| gewu-infrastructure | 基础设施层 | `com.gewu.infrastructure.*` |
| gewu-common | 公共工具层 | `com.gewu.common.*` |
| gewu-sandbox | 沙箱服务 | `com.gewu.sandbox.*` |
| gewu-gateway | 网关服务 | `com.gewu.gateway.*` |

### 1.2 技术栈

| 层 | 技术 |
|---|------|
| 框架 | Spring Boot 3.2.5 |
| ORM | MyBatis-Plus |
| 安全 | Spring Security + JWT (HS256) |
| 缓存 | Redis (DragonflyDB 兼容) |
| 数据库 | MySQL 8.0 (业务) + PostgreSQL 16 + pgvector (向量) |
| 消息队列 | RocketMQ 5.1.4 |
| 连接池 | HikariCP |
| 参数校验 | Jakarta Validation |
| 文档 | SpringDoc OpenAPI |

---

## 2. API 接口完整清单

### 2.1 认证管理 (AuthController)

| 方法 | 路径 | 请求体 | 响应 | 说明 |
|------|------|--------|------|------|
| POST | `/api/v1/auth/login` | `LoginCommand` | `Result<TokenDTO>` | 用户登录 |
| POST | `/api/v1/auth/register` | `RegisterCommand` | `Result<TokenDTO>` | 用户注册 |
| POST | `/api/v1/auth/refresh` | `RefreshTokenCommand` | `Result<TokenDTO>` | 刷新令牌 |
| POST | `/api/v1/auth/logout` | Header: Authorization | `Result<Void>` | 用户登出 |

### 2.2 用户管理 (UserController)

| 方法 | 路径 | 请求体 | 响应 | 说明 |
|------|------|--------|------|------|
| GET | `/api/v1/users/me` | - | `Result<UserDTO>` | 获取当前用户 |
| GET | `/api/v1/users/{userId}` | - | `Result<UserDTO>` | 获取用户信息 |
| GET | `/api/v1/users` | PageQuery | `Result<PageResult<UserDTO>>` | 用户列表 |
| PUT | `/api/v1/users/me` | `UpdateUserCommand` | `Result<UserDTO>` | 更新当前用户 |

### 2.3 Agent 管理 (AgentController)

| 方法 | 路径 | 请求体 | 响应 | 说明 |
|------|------|--------|------|------|
| POST | `/api/v1/agents` | `CreateAgentCommand` | `Result<AgentDTO>` | 创建 Agent |
| GET | `/api/v1/agents/{agentId}` | - | `Result<AgentDTO>` | 获取 Agent |
| GET | `/api/v1/agents` | PageQuery | `Result<PageResult<AgentDTO>>` | Agent 列表 |
| PUT | `/api/v1/agents/{agentId}` | `UpdateAgentCommand` | `Result<AgentDTO>` | 更新 Agent |
| DELETE | `/api/v1/agents/{agentId}` | - | `Result<Void>` | 删除 Agent |
| GET | `/api/v1/agents/{agentId}/tools` | - | `Result<List<AgentToolDTO>>` | 获取工具列表 |

### 2.4 项目管理 (ProjectController)

| 方法 | 路径 | 请求体 | 响应 | 说明 |
|------|------|--------|------|------|
| POST | `/api/v1/projects` | `CreateProjectCommand` | `Result<ProjectDTO>` | 创建项目 |
| GET | `/api/v1/projects/{projectId}` | - | `Result<ProjectDTO>` | 获取项目 |
| GET | `/api/v1/projects` | PageQuery | `Result<PageResult<ProjectDTO>>` | 项目列表 |
| GET | `/api/v1/projects/my` | PageQuery | `Result<PageResult<ProjectDTO>>` | 我的项目 |
| PUT | `/api/v1/projects/{projectId}` | `UpdateProjectCommand` | `Result<ProjectDTO>` | 更新项目 |
| DELETE | `/api/v1/projects/{projectId}` | - | `Result<Void>` | 删除项目 |
| GET | `/api/v1/projects/{projectId}/members` | - | `Result<List<ProjectMemberDTO>>` | 成员列表 |
| POST | `/api/v1/projects/{projectId}/members` | `AddMemberCommand` | `Result<ProjectMemberDTO>` | 添加成员 |
| DELETE | `/api/v1/projects/{projectId}/members/{userId}` | - | `Result<Void>` | 移除成员 |
| PUT | `/api/v1/projects/{projectId}/members/{userId}` | roleCode | `Result<Void>` | 更新成员角色 |

### 2.5 会话管理 (SessionController + ChatSessionController)

| 方法 | 路径 | 请求体 | 响应 | 说明 |
|------|------|--------|------|------|
| POST | `/api/v1/sessions` | `CreateSessionCommand` | `Result<SessionDTO>` | 创建会话 |
| POST | `/api/v1/sessions/chat` | `CreateChatSessionCommand` | `Result<SessionDTO>` | 创建聊天会话 |
| GET | `/api/v1/sessions/{sessionId}` | - | `Result<SessionDTO>` | 获取会话 |
| GET | `/api/v1/sessions` | PageQuery | `Result<PageResult<SessionDTO>>` | 会话列表 |
| GET | `/api/v1/sessions/my` | PageQuery | `Result<PageResult<SessionDTO>>` | 我的会话 |
| PUT | `/api/v1/sessions/{sessionId}` | `UpdateSessionCommand` | `Result<SessionDTO>` | 更新会话 |
| DELETE | `/api/v1/sessions/{sessionId}` | - | `Result<Void>` | 删除会话 |
| GET | `/api/v1/sessions/{sessionId}/members` | - | `Result<List<SessionMemberDTO>>` | 成员列表 |

### 2.6 消息管理 (MessageController)

| 方法 | 路径 | 请求体 | 响应 | 说明 |
|------|------|--------|------|------|
| POST | `/api/v1/sessions/{sessionId}/messages` | `SendMessageCommand` | `Result<MessageDTO>` | 发送消息 |
| GET | `/api/v1/sessions/{sessionId}/messages` | PageQuery | `Result<PageResult<MessageDTO>>` | 消息列表 |
| GET | `/api/v1/sessions/{sessionId}/messages/search` | keyword | `Result<PageResult<MessageDTO>>` | 搜索消息 |
| GET | `/api/v1/sessions/{sessionId}/messages/{messageId}` | - | `Result<MessageDTO>` | 获取消息 |
| PUT | `/api/v1/sessions/{sessionId}/messages/{messageId}` | `EditMessageCommand` | `Result<MessageDTO>` | 编辑消息 |
| DELETE | `/api/v1/sessions/{sessionId}/messages/{messageId}` | - | `Result<Void>` | 删除消息 |

### 2.7 AI 聊天 (AiChatController)

| 方法 | 路径 | 请求体 | 响应 | 说明 |
|------|------|--------|------|------|
| POST | `/api/v1/chat` | `ChatRequest` | `Result<ChatResponse>` | 普通聊天 |
| POST | `/api/v1/chat/stream` | `ChatRequest` | `SseEmitter` | 流式聊天 |
| POST | `/api/v1/chat/wenshi` | `WenshiReasoningRequest` | `SseEmitter` | Wenshi 推理 |

### 2.8 SSE 推送 (SseController)

| 方法 | 路径 | 请求体 | 响应 | 说明 |
|------|------|--------|------|------|
| GET | `/api/v1/sse/sessions/{sessionId}` | - | `SseEmitter` | 订阅会话事件 |

### 2.9 工作流 (WorkflowController + WorkflowInstanceController)

| 方法 | 路径 | 请求体 | 响应 | 说明 |
|------|------|--------|------|------|
| POST | `/api/v1/workflows` | `CreateWorkflowCommand` | `Result<WorkflowDTO>` | 创建工作流 |
| GET | `/api/v1/workflows/{workflowId}` | - | `Result<WorkflowDTO>` | 获取工作流 |
| GET | `/api/v1/workflows` | PageQuery | `Result<PageResult<WorkflowDTO>>` | 工作流列表 |
| PUT | `/api/v1/workflows/{workflowId}` | `UpdateWorkflowCommand` | `Result<WorkflowDTO>` | 更新工作流 |
| DELETE | `/api/v1/workflows/{workflowId}` | - | `Result<Void>` | 删除工作流 |
| POST | `/api/v1/workflows/{workflowId}/publish` | - | `Result<WorkflowDTO>` | 发布工作流 |
| POST | `/api/v1/workflows/{workflowId}/instances` | `StartInstanceCommand` | `Result<WorkflowInstanceDTO>` | 启动实例 |
| GET | `/api/v1/workflows/instances/{instanceId}` | - | `Result<WorkflowInstanceDTO>` | 获取实例 |
| POST | `/api/v1/workflows/instances/{instanceId}/nodes/{nodeId}/complete` | `CompleteNodeCommand` | `Result<WorkflowInstanceDTO>` | 完成节点 |

### 2.10 MCP Server (McpServerController)

| 方法 | 路径 | 请求体 | 响应 | 说明 |
|------|------|--------|------|------|
| POST | `/api/v1/mcp-servers` | `CreateMcpServerCommand` | `Result<McpServerDTO>` | 创建 |
| GET | `/api/v1/mcp-servers` | PageQuery | `Result<PageResult<McpServerDTO>>` | 列表 |
| GET | `/api/v1/mcp-servers/{serverId}` | - | `Result<McpServerDTO>` | 获取 |
| DELETE | `/api/v1/mcp-servers/{serverId}` | - | `Result<Void>` | 删除 |
| GET | `/api/v1/mcp-servers/{serverId}/tools` | - | `Result<List<McpToolDTO>>` | 发现工具 |
| POST | `/api/v1/mcp-servers/{serverId}/activate` | - | `Result<McpServerDTO>` | 激活 |
| POST | `/api/v1/mcp-servers/{serverId}/deactivate` | - | `Result<McpServerDTO>` | 停用 |

### 2.11 沙箱 (SandboxController)

| 方法 | 路径 | 请求体 | 响应 | 说明 |
|------|------|--------|------|------|
| POST | `/api/v1/sandboxes` | `CreateSandboxCommand` | `Result<SandboxDTO>` | 创建沙箱 |
| GET | `/api/v1/sandboxes/{sandboxId}` | - | `Result<SandboxDTO>` | 获取沙箱 |
| DELETE | `/api/v1/sandboxes/{sandboxId}` | - | `Result<Void>` | 销毁沙箱 |
| POST | `/api/v1/sandboxes/{sandboxId}/execute` | `ExecuteCommand` | `Result<ExecuteResult>` | 执行命令 |
| GET | `/api/v1/sandboxes/{sandboxId}/logs` | - | `Result<List<String>>` | 获取日志 |

### 2.12 Agent 执行 (AgentExecutionController)

| 方法 | 路径 | 请求体 | 响应 | 说明 |
|------|------|--------|------|------|
| POST | `/api/v1/agents/{agentId}/execute` | `AgentExecutionRequest` | `SseEmitter` | 执行 Agent |
| POST | `/api/v1/agents/{agentId}/cancel` | - | `Result<Void>` | 取消执行 |

### 2.13 Agent 工具 (AgentToolController)

| 方法 | 路径 | 请求体 | 响应 | 说明 |
|------|------|--------|------|------|
| POST | `/api/v1/agents/{agentId}/tools` | `RegisterToolCommand` | `Result<AgentToolDTO>` | 注册工具 |
| GET | `/api/v1/agents/{agentId}/tools` | - | `Result<List<AgentToolDTO>>` | 工具列表 |
| DELETE | `/api/v1/agents/{agentId}/tools/{toolId}` | - | `Result<Void>` | 删除工具 |

---

## 3. 功能逻辑深度分析

### 3.1 认证域

#### 3.1.1 登录流程

```
LoginCommand → AuthService.login()
  1. 按 username 查询 user_account
  2. 校验用户状态（LOCKED/DISABLED）
  3. PasswordHasher.verify(password, passwordHash)
  4. 失败 → loginFailCount++，达到阈值 → 锁定
  5. 成功 → 重置 loginFailCount，更新 lastLoginAt
  6. 查询用户角色（user_role → role）
  7. 生成 accessToken + refreshToken
```

**关键设计**:
- 密码使用 SM3 国密哈希 + 盐值
- 登录失败 5 次自动锁定（`MAX_LOGIN_FAIL_COUNT`）
- 锁定持续时间由 `LOCK_DURATION_MS` 控制
- Token 类型：JWT (HS256)，含 JTI + Family 家族标识

#### 3.1.2 Token 轮换机制

```
refresh(refreshToken)
  1. validateRefreshToken() — 校验签名 + 类型
  2. validateNotBlacklisted() — 检查 JTI 是否在黑名单
  3. validateFamilyConsistency() — 校验家族一致性（防盗用）
  4. 旧 JTI 加入黑名单（Redis，7天TTL）
  5. 生成新 accessToken + 新 refreshToken
  6. 注册新 Token 家族
```

**安全特性**:
- Token 家族检测：检测令牌是否被盗用
- 黑名单吊销：支持主动吊销 + 自动过期
- 家族一致性：refreshToken 只能使用一次，使用后整个家族失效

#### 3.1.3 注册流程

```
RegisterCommand → AuthService.register()
  1. 校验用户名唯一性
  2. 校验邮箱唯一性
  3. 生成 ULID 主键
  4. SM3 哈希密码
  5. 插入 user_account
  6. 分配默认角色 USER
  7. 生成 Token 对
```

#### 3.1.4 登录安全策略 (LoginSecurityService)

| 策略 | 阈值 | TTL | 实现 |
|------|------|-----|------|
| 用户锁定 | 5 次失败 | 30 分钟 | Redis `gewu:login:lock:` |
| IP 封禁 | 20 次失败 | 5 分钟 | Redis `gewu:login:ipblock:` |
| 失败计数 | - | 5 分钟 | Redis `gewu:login:fail:` |

### 3.2 Agent 域

#### 3.2.1 Agent CRUD

| 操作 | 权限校验 | 说明 |
|------|----------|------|
| create | 无 | 创建后 createdBy = 当前用户 |
| get | 无 | 按 ID 查询 |
| list | 无 | 分页，按 createdAt 降序 |
| update | `checkOwnership` | 仅创建者可更新 |
| delete | `checkOwnership` | 仅创建者可删除 |
| getTools | 无 | 按 sortOrder 升序 |

#### 3.2.2 Agent 状态

| 状态码 | 描述 |
|--------|------|
| 1 | 启用 |
| 0 | 禁用 |

### 3.3 项目域

#### 3.3.1 项目 CRUD

| 操作 | 权限校验 | 说明 |
|------|----------|------|
| create | 需登录 | 创建后 ownerId = 当前用户 |
| get | 无 | 按 ID 查询 |
| list | 无 | 分页 |
| my | 需登录 | 查询当前用户参与的项目 |
| update | 无 | - |
| delete | 无 | - |

#### 3.3.2 项目成员管理

| 操作 | 说明 |
|------|------|
| addMember | 添加成员到项目 |
| removeMember | 从项目移除成员 |
| updateMemberRole | 更新成员角色编码 |

### 3.4 会话域

#### 3.4.1 会话类型

| type | 描述 |
|------|------|
| 1 | 对话 |
| 2 | 编码 |
| 3 | 调试 |
| 4 | 重构 |

#### 3.4.2 会话状态

| status | 描述 |
|--------|------|
| 0 | 进行中 |
| 1 | 已完成 |
| 2 | 已归档 |

#### 3.4.3 会话创建逻辑

```
createSession(command)
  1. 校验登录态（UserContext.currentUserId）
  2. 插入 session 表
  3. 插入 session_member（创建人 role=1 管理员）
  4. 返回 SessionDTO
```

### 3.5 消息域

#### 3.5.1 消息操作

| 操作 | 权限校验 | 说明 |
|------|----------|------|
| send | 需登录 | 发送消息到会话 |
| list | 无 | 分页，按 createdAt 升序 |
| search | 无 | 按关键词搜索 |
| get | 无 | 按 ID 查询 |
| edit | 仅可编辑自己的消息 | - |
| delete | 仅可删除自己的消息 | 软删除 |

### 3.6 工作流域

#### 3.6.1 工作流状态

| 设计状态 | 描述 | 运行状态 | 描述 |
|----------|------|----------|------|
| 0 (draft) | 草稿 | stopped | 已停止 |
| 1 (published) | 已发布 | running | 运行中 |
| 2 (archived) | 已归档 | paused | 已暂停 |

#### 3.6.2 工作流引擎

```
startInstance(workflowId)
  1. 校验工作流已发布
  2. 创建 workflow_instance
  3. 创建所有 workflow_node_instance
  4. 设置 current_node_id = 第一个节点

completeNode(instanceId, nodeId)
  1. 更新节点实例状态 = completed
  2. 根据 transition 找到下一个节点
  3. 更新 current_node_id
  4. 如到达 end 节点 → 实例完成
```

### 3.7 MCP Server 域

#### 3.7.1 传输方式

| transport | 说明 |
|-----------|------|
| stdio | 标准输入输出（本地进程） |
| sse | Server-Sent Events（HTTP 远程） |

#### 3.7.2 工具发现

```
listServerTools(serverId)
  1. 通过 McpServerManager 连接到 MCP Server
  2. 调用 MCP 协议的 tools/list
  3. 返回工具名称、描述、输入 Schema
```

---

## 4. 数据库设计分析

### 4.1 表统计

| 域 | 表数 | 表名 |
|----|------|------|
| 用户与权限 | 5 | user_account, role, permission, user_role, role_permission |
| 项目管理 | 3 | project, project_member, project_directory |
| 会话消息 | 6 | session, session_member, session_message, session_input, session_context_epoch, part |
| Agent 系统 | 4 | agent, agent_tool, agent_permission, agent_execution |
| 工作流引擎 | 9 | workflow, workflow_node, workflow_transition, workflow_instance, workflow_node_instance, workflow_notification, workflow_permission, workflow_permission_matrix, workflow_audit_log |
| 审计与安全 | 2 | audit_log, api_key |
| 沙箱配置 | 2 | sandbox_config, sandbox_audit_log |
| 数据迁移 | 1 | id_migration_map |
| Wenshi 向量 | 6 | semantic_fragment, episodic_event, procedural_memory, user_profile, experience, reasoning_trace |
| **总计** | **38** | - |

### 4.2 核心表结构

#### 4.2.1 user_account（用户账户）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(26) | ULID 主键 |
| username | VARCHAR(64) | 用户名（唯一） |
| email | VARCHAR(128) | 邮箱（唯一） |
| phone | VARCHAR(20) | 手机号（唯一） |
| password_hash | VARCHAR(256) | SM3 哈希 |
| password_salt | VARCHAR(64) | 盐值 |
| display_name | VARCHAR(64) | 显示名称 |
| avatar_url | VARCHAR(512) | 头像 URL |
| status | TINYINT | 1=启用 2=禁用 3=锁定 |
| last_login_at | BIGINT | 最后登录时间 |
| last_login_ip | VARCHAR(45) | 最后登录 IP |
| login_fail_count | INT | 登录失败次数 |
| locked_until | BIGINT | 锁定截止时间 |
| deleted | TINYINT | 逻辑删除 |
| version | INT | 乐观锁 |
| created_at | BIGINT | 创建时间 |
| updated_at | BIGINT | 更新时间 |
| created_by | VARCHAR(26) | 创建人 |
| updated_by | VARCHAR(26) | 更新人 |

#### 4.2.2 session（会话）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(26) | ULID 主键 |
| title | VARCHAR(256) | 会话标题 |
| type | TINYINT | 1=群聊 2=私聊 3=AI辅助 |
| project_id | VARCHAR(26) | 关联项目 |
| status | TINYINT | 1=活跃 2=归档 3=关闭 |
| is_public | TINYINT | 是否公开 |
| last_message_at | BIGINT | 最后消息时间 |
| message_count | INT | 消息总数 |
| parent_id | VARCHAR(26) | 父会话（会话树/分叉） |
| agent | VARCHAR(128) | 绑定 Agent |
| model | JSON | 模型配置 |
| slug | VARCHAR(128) | 唯一标识 |
| directory | VARCHAR(1024) | 工作目录 |
| cost | DECIMAL(10,4) | 累计成本 |
| tokens_input | INT | 输入 Token |
| tokens_output | INT | 输出 Token |
| tokens_reasoning | INT | 推理 Token |
| tokens_cache_read | INT | 缓存读取 Token |
| tokens_cache_write | INT | 缓存写入 Token |
| summary_additions | INT | 代码添加行数 |
| summary_deletions | INT | 代码删除行数 |
| metadata | JSON | 扩展元数据 |

#### 4.2.3 agent（Agent 配置）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(26) | ULID 主键 |
| agent_name | VARCHAR(128) | 名称 |
| description | VARCHAR(1024) | 描述 |
| model_provider | VARCHAR(64) | 模型提供商 |
| model_name | VARCHAR(128) | 模型名称 |
| model_config | JSON | 模型参数 |
| system_prompt | TEXT | 系统提示词 |
| status | TINYINT | 1=启用 2=禁用 |
| version | INT | 版本号 |

#### 4.2.4 workflow（工作流模板）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(26) | ULID 主键 |
| workflow_name | VARCHAR(128) | 名称 |
| description | TEXT | 描述 |
| version | INT | 版本号 |
| status | TINYINT | 0=草稿 1=已发布 2=已归档 |
| category | VARCHAR(64) | 分类 |
| config | JSON | 状态机定义 |
| published_at | BIGINT | 发布时间 |

### 4.3 实体基类

#### BaseEntity（完整审计字段）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | String | ULID 主键（IdType.INPUT） |
| deleted | Integer | 逻辑删除（@TableLogic） |
| createdAt | Long | 创建时间（自动填充） |
| updatedAt | Long | 更新时间（自动填充） |
| createdBy | String | 创建人（自动填充） |
| updatedBy | String | 更新人（自动填充） |

#### BaseSimpleEntity（轻量级）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | String | ULID 主键 |
| createdAt | Long | 创建时间 |

### 4.4 JSON 字段清单

| 表名 | JSON 字段 | 用途 |
|------|-----------|------|
| project | tech_stack, sandboxes, commands | 技术栈/沙箱/命令配置 |
| session | model, metadata, revert, summary_diffs | 模型/元数据/回滚/差异 |
| agent | model_config, request_schema, response_schema, auth_config | 模型/Schema/认证 |
| agent_tool | request_schema, response_schema, auth_config | Schema/认证 |
| agent_permission | condition_expr | 条件表达式 |
| agent_execution | input, output, tool_calls | 输入/输出/工具调用 |
| session_message | metadata, mention_user_ids | 元数据/提及用户 |
| session_input | prompt | 提示词数据 |
| session_context_epoch | snapshot | 上下文快照 |
| part | data | 部分数据 |
| workflow | config | 状态机定义 |
| workflow_node | config | 节点配置 |
| workflow_instance | variables | 流程变量 |
| workflow_node_instance | input, output | 节点输入/输出 |
| workflow_permission_matrix | - | - |
| audit_log | detail | 操作详情 |
| api_key | permissions | 权限范围 |
| sandbox_config | allowed_mounts, env_vars | 挂载/环境变量 |
| sandbox_audit_log | details | 操作详情 |

### 4.5 索引设计

| 索引类型 | 数量 | 示例 |
|----------|------|------|
| PRIMARY | 38 | 每个表的主键 |
| UNIQUE | 15 | username, email, role_code, slug, uk_session_member 等 |
| KEY | 80+ | 外键索引、时间排序索引 |

### 4.6 双数据库架构

| 数据库 | 用途 | 表 |
|--------|------|----|
| MySQL 8.0 | 业务数据 | 32 张核心业务表 |
| PostgreSQL 16 + pgvector | Wenshi 向量数据 | 6 张向量表 |
| Redis (DragonflyDB) | 缓存/Token 黑名单/家族 | - |
| RocketMQ | 异步消息 | - |

---

## 5. 安全体系分析

### 5.1 JWT 安全

| 项目 | 实现 |
|------|------|
| 算法 | HS256 |
| 密钥长度 | ≥ 256 bit |
| Access Token 有效期 | 30 分钟 |
| Refresh Token 有效期 | 7 天 |
| JTI | 每个 Token 唯一标识 |
| Family | Token 家族标识（检测盗用） |
| 黑名单 | Redis 存储，7 天 TTL |

### 5.2 密码安全

| 项目 | 实现 |
|------|------|
| 哈希算法 | SM3 国密 |
| 盐值 | 随机生成，存储在 password_salt |
| 验证 | PasswordHasher.verify() |

### 5.3 接口安全

| 项目 | 实现 |
|------|------|
| 认证过滤器 | JwtAuthenticationFilter |
| 安全响应头 | SecurityHeadersFilter (X-Frame-Options, CSP, HSTS) |
| XSS 防护 | XssFilter（可配置开关） |
| CORS | CorsFilter |
| 权限注解 | @PreAuthorize（部分接口） |

### 5.4 登录防护

| 策略 | 阈值 | TTL |
|------|------|-----|
| 用户锁定 | 5 次失败 | 30 分钟 |
| IP 封禁 | 20 次失败 | 5 分钟 |
| 失败计数 | - | 5 分钟 |

### 5.5 审计日志

| 字段 | 说明 |
|------|------|
| user_id | 操作用户 |
| action_type | LOGIN/CREATE/UPDATE/DELETE/EXECUTE |
| resource_type | USER/PROJECT/SESSION/AGENT/WORKFLOW |
| resource_id | 资源 ID |
| ip_address | 客户端 IP |
| user_agent | 客户端 UA |
| result | 1=成功 0=失败 |
| duration_ms | 操作耗时 |

---

## 6. 缓存与 Token 管理

### 6.1 Redis Key 设计

| Key 前缀 | 用途 | TTL |
|----------|------|-----|
| `gewu:login:lock:` | 用户登录锁定 | 30 分钟 |
| `gewu:login:fail:` | 用户登录失败计数 | 5 分钟 |
| `gewu:login:ipblock:` | IP 封禁 | 5 分钟 |
| `gewu:login:ipfail:` | IP 失败计数 | 5 分钟 |
| `gewu:token:blacklist:` | Token 黑名单 | 7 天 |
| `gewu:token:family:` | Token 家族 | 7 天 |
| `gewu:semantic:cache:` | 语义记忆缓存 | 配置 |
| `gewu:user:` | 用户缓存 | 配置 |
| `gewu:session:` | 会话缓存 | 配置 |

### 6.2 CacheService 方法

| 方法 | 说明 |
|------|------|
| `blacklistToken(jti, ttl)` | 将 JTI 加入黑名单 |
| `isTokenBlacklisted(jti)` | 检查 JTI 是否在黑名单 |
| `storeRefreshTokenFamily(family, jti, ttl)` | 存储 Token 家族 |
| `getRefreshTokenFamily(family)` | 获取 Token 家族 |
| `deleteRefreshTokenFamily(family)` | 删除 Token 家族 |
| `incrementWithExpire(key, ttl)` | 原子递增 + 过期 |
| `getCounter(key)` | 获取计数值 |
| `set(key, value, ttl)` | 写入 |
| `get(key)` | 读取 |
| `delete(key)` | 删除 |
| `exists(key)` | 存在性检查 |

---

## 7. Wenshi 知识增强层

### 7.1 三层认知架构

| 层 | 服务 | 存储 |
|----|------|------|
| 知识层（记忆） | SemanticMemoryService, EpisodicMemoryService, ProceduralMemoryService, ParametricMemoryService | PostgreSQL + pgvector |
| 推理层（思维） | WenshiReasoningEngine, Planner, SolverRouter, Critic | - |
| 学习层（进化） | ExperienceExtractor, QualityAssessor, SkillEvolver, ReflectionAgent | PostgreSQL + pgvector |

### 7.2 向量数据库表

| 表名 | 说明 |
|------|------|
| semantic_fragment | 语义片段（向量检索） |
| episodic_event | 情景事件 |
| procedural_memory | 程序性记忆 |
| user_profile | 用户画像 |
| experience | 经验记录 |
| reasoning_trace | 推理轨迹 |

### 7.3 适配器层

| 适配器 | 实现 | 说明 |
|--------|------|------|
| EmbeddingAdapter | BgeSmallEmbeddingAdapter, LlmNativeEmbeddingAdapter | 文本向量化 |
| VectorStoreAdapter | PgvectorAdapter | 向量存储 |

### 7.4 条件控制

```java
@ConditionalOnProperty(prefix = "gewu.wenshi", name = "enabled", havingValue = "true")
```

Wenshi 模块默认关闭，通过 `gewu.wenshi.enabled=true` 启用。

---

## 8. 待完善事项

### 8.1 功能缺失

| 优先级 | 事项 | 影响 |
|--------|------|------|
| P0 | 退出登录未在前端清除 token | Header.tsx |
| P0 | 会话流式 API 未对接前端 | AiChatController |
| P1 | 工作流引擎未完全实现 | WorkflowInstanceService |
| P1 | 沙箱执行器未完全实现 | SandboxService |
| P1 | 消息搜索未实现 SQL | MessageService.searchMessages |
| P2 | 审计日志未自动记录 | 需要 AOP 切面 |
| P2 | 权限校验不完整 | 部分接口缺少 @PreAuthorize |

### 8.2 技术债务

| 事项 | 说明 |
|------|------|
| 统一响应 `Result.success()` | 默认 message="操作成功"，但部分场景需要自定义 |
| 分页参数校验 | PageQuery 缺少 `@Min` 校验 |
| 软删除一致性 | 部分查询未过滤 `deleted=0` |
| 跨模块事务 | ProjectService 中使用 HttpClient 直接调用沙箱 API，非事务 |

### 8.3 数据库优化建议

| 表 | 建议 |
|----|------|
| session | `slug` 字段唯一索引已建，但部分查询未使用 |
| session_message | 大数据量时考虑按 session_id 分表 |
| audit_log | 考虑按月分表或归档 |
| workflow_audit_log | 考虑异步写入 |

### 8.4 缺失的 Mapper

以下表在 DDL 中定义但未找到对应 Mapper：

| 表名 | 状态 |
|------|------|
| role_permission | 无 Mapper |
| project_directory | 无 Mapper |
| session_input | 无 Mapper |
| session_context_epoch | 无 Mapper |
| part | 无 Mapper |
| agent_permission | 无 Mapper |
| workflow_notification | 无 Mapper |
| workflow_permission | 无 Mapper |
| workflow_permission_matrix | 无 Mapper |
| workflow_audit_log | 无 Mapper |
| api_key | 无 Mapper |
| sandbox_config | 无 Mapper |
| sandbox_audit_log | Sandbox 模块有独立 Mapper |
| id_migration_map | 无 Mapper |

---

> **文档结束** — 共分析 17 个 Controller、29 个 Service、38 张数据库表、60+ 个 API 接口、完整安全体系
