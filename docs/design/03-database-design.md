# 格物平台 - 数据库设计文档

## 文档信息

| 项目 | 内容 |
|------|------|
| 文档名称 | 数据库设计文档 |
| 版本 | V1.0 |
| 创建日期 | 2026-07-08 |
| 文档状态 | 初稿 |
| 主数据库 | OceanBase 4.2+ (MySQL 兼容模式) |
| 备选数据库 | MySQL 8.0 / 达梦 8.0 / 人大金仓 8.0 |

---

## 目录

1. [设计原则](#1-设计原则)
2. [ER 图](#2-er-图)
3. [表结构设计](#3-表结构设计)
4. [DDL 脚本](#4-ddl-脚本)
5. [索引设计](#5-索引设计)
6. [数据字典](#6-数据字典)
7. [多数据库支持方案](#7-多数据库支持方案)
8. [数据迁移方案](#8-数据迁移方案)

---

## 1. 设计原则

### 1.1 核心设计约束

| 原则 | 说明 |
|------|------|
| **主键策略** | 所有表使用 `VARCHAR(26) ULID` 作为主键，支持分布式场景 |
| **时间字段** | 统一使用 `bigint` 存储毫秒级时间戳 |
| **逻辑删除** | 所有业务表支持逻辑删除，`deleted` 字段 (0=正常, 1=删除) |
| **乐观锁** | 关键表添加 `version` 字段用于乐观锁并发控制 |
| **审计字段** | 所有表包含 `created_at`, `updated_at`, `created_by`, `updated_by` |
| **命名规范** | 表名使用小写蛇形命名，字段名使用小写蛇形命名 |
| **字符集** | UTF-8 (utf8mb4) |
| **默认存储引擎** | OceanBase MySQL 兼容模式 / InnoDB |

### 1.2 ULID 主键格式

```
01ARZ3NDEKTSV4RRFFQ69G5FAV
├─┬┴─┬──┴──────────┬───────┘
│  │              └─ 随机部分 (16字符, 80bit)
│  └───────────────── 时间戳部分 (10字符, 48bit, 毫秒级)
└──────────────────── 版本标识 (固定为 01)
```

### 1.3 数据库命名规范

| 类别 | 规范 | 示例 |
|------|------|------|
| 数据库名 | `gewu_` + 环境 | `gewu_dev`, `gewu_prod` |
| 表名 | 业务领域 + `_` + 实体 | `user_account`, `project_member` |
| 字段名 | 小写蛇形 | `user_name`, `created_at` |
| 主键 | `id` | `id` |
| 外键 | 关联表 + `_` + 关联字段 | `user_id`, `project_id` |
| 索引 | `idx_` + 表名 + `_` + 字段 | `idx_user_account_email` |
| 唯一索引 | `uk_` + 表名 + `_` + 字段 | `uk_user_account_username` |

---

## 2. ER 图

```
┌───────────────────┐     ┌─────────────────────┐     ┌───────────────────┐
│   user_account     │     │   user_role         │     │   role            │
│───────────────────│     │─────────────────────│     │───────────────────│
│ PK id (ULID)      │────>│ PK id (ULID)        │<────│ PK id (ULID)      │
│    username        │     │ FK user_id          │     │    role_name      │
│    email           │     │ FK role_id          │     │    role_code      │
│    password_hash   │     │    ...              │     │    description    │
│    ...             │     └─────────────────────┘     │    ...            │
└───────────────────┘                                  └───────────────────┘
                                                               │
┌───────────────────┐     ┌─────────────────────┐              │
│   project          │     │   role_permission    │              │
│───────────────────│     │─────────────────────│              │
│ PK id (ULID)      │     │ PK id (ULID)        │<─────────────┤
│    project_name   │     │ FK role_id          │              │
│    description    │     │ FK permission_id    │              │
│    ...            │     └─────────────────────┘              │
└────────┬──────────┘                                         │
         │                     ┌───────────────────┐           │
         │                     │   permission      │           │
         │                     │───────────────────│           │
         │                     │ PK id (ULID)      │<──────────┘
         │                     │    permission_code │
         │                     │    resource_type   │
┌────────▼──────────┐          │    action          │
│   project_member   │          │    ...            │
│───────────────────│          └───────────────────┘
│ PK id (ULID)      │
│ FK project_id     │          ┌───────────────────┐
│ FK user_id        │          │   session         │
│    role (in proj) │          │───────────────────│
│    ...            │          │ PK id (ULID)      │
└───────────────────┘          │    title          │
                               │    type           │
┌───────────────────┐          │    ...            │
│   session_member   │          └────────┬──────────┘
│───────────────────│                   │
│ PK id (ULID)      │                   │
│ FK session_id     │                   │
│ FK user_id        │                   │
│    ...            │          ┌────────▼──────────┐
└───────────────────┘          │   session_message   │
                               │───────────────────│
┌───────────────────┐          │ PK id (ULID)      │
│   agent            │          │ FK session_id     │
│───────────────────│          │ FK sender_id      │
│ PK id (ULID)      │          │    content        │
│    agent_name     │          │    message_type   │
│    model_config   │          │    ...            │
│    ...            │          └───────────────────┘
└────────┬──────────┘
         │                ┌───────────────────┐
         │                │   agent_tool       │
         │                │───────────────────│
┌────────▼──────────┐     │ PK id (ULID)      │
│   agent_execution   │     │ FK agent_id       │
│───────────────────│     │    tool_name      │
│ PK id (ULID)      │     │    tool_params    │
│ FK agent_id       │     │    ...            │
│    status         │     └───────────────────┘
│    input          │
│    output         │     ┌───────────────────┐
│    ...            │     │   workflow         │
└───────────────────┘     │───────────────────│
                          │ PK id (ULID)      │
┌───────────────────┐     │    workflow_name  │
│   audit_log        │     │    version        │
│───────────────────│     │    ...            │
│ PK id (ULID)      │     └────────┬──────────┘
│ FK user_id        │              │
│    action_type    │     ┌────────▼──────────┐
│    resource_type  │     │   workflow_node    │
│    resource_id    │     │───────────────────│
│    detail         │     │ PK id (ULID)      │
│    ip_address     │     │ FK workflow_id    │
│    ...            │     │    node_type      │
└───────────────────┘     │    config         │
                          │    ...            │
                          └───────────────────┘
```

---

## 3. 表结构设计

### 3.1 用户与权限域

#### user_account — 用户账户表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| username | VARCHAR(64) | UNIQUE, NOT NULL | 用户名 |
| email | VARCHAR(128) | UNIQUE, NOT NULL | 邮箱 |
| phone | VARCHAR(20) | UNIQUE | 手机号 |
| password_hash | VARCHAR(256) | NOT NULL | SM3 密码哈希 |
| password_salt | VARCHAR(64) | NOT NULL | 密码盐值 |
| display_name | VARCHAR(64) | NOT NULL | 显示名称 |
| avatar_url | VARCHAR(512) | | 头像URL |
| status | TINYINT | NOT NULL DEFAULT 1 | 状态: 1=启用 2=禁用 3=锁定 |
| last_login_at | BIGINT | | 最后登录时间戳 |
| last_login_ip | VARCHAR(45) | | 最后登录IP |
| login_fail_count | INT | DEFAULT 0 | 登录失败次数 |
| locked_until | BIGINT | | 锁定截止时间 |
| deleted | TINYINT | DEFAULT 0 | 逻辑删除标志 |
| version | INT | DEFAULT 0 | 乐观锁版本 |
| created_at | BIGINT | NOT NULL | 创建时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |
| created_by | VARCHAR(26) | | 创建人 |
| updated_by | VARCHAR(26) | | 更新人 |

#### role — 角色表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| role_name | VARCHAR(64) | NOT NULL | 角色名称 |
| role_code | VARCHAR(64) | UNIQUE, NOT NULL | 角色编码 (如: ADMIN, DEVELOPER) |
| description | VARCHAR(512) | | 角色描述 |
| is_system | TINYINT | DEFAULT 0 | 是否系统内置 (1=系统, 0=自定义) |
| sort_order | INT | DEFAULT 0 | 排序顺序 |
| deleted | TINYINT | DEFAULT 0 | 逻辑删除 |
| created_at | BIGINT | NOT NULL | 创建时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |
| created_by | VARCHAR(26) | | 创建人 |
| updated_by | VARCHAR(26) | | 更新人 |

#### permission — 权限表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| permission_code | VARCHAR(128) | UNIQUE, NOT NULL | 权限编码 |
| permission_name | VARCHAR(64) | NOT NULL | 权限名称 |
| resource_type | VARCHAR(64) | NOT NULL | 资源类型 |
| action | VARCHAR(64) | NOT NULL | 操作 (CREATE/READ/UPDATE/DELETE/EXECUTE) |
| description | VARCHAR(512) | | 权限描述 |
| deleted | TINYINT | DEFAULT 0 | 逻辑删除 |
| created_at | BIGINT | NOT NULL | 创建时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |

#### user_role — 用户角色关联表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| user_id | VARCHAR(26) | FK, NOT NULL | 用户ID |
| role_id | VARCHAR(26) | FK, NOT NULL | 角色ID |
| source | VARCHAR(32) | DEFAULT 'system' | 来源: system/project |
| source_id | VARCHAR(26) | | 来源ID (如项目ID) |
| created_at | BIGINT | NOT NULL | 创建时间 |
| created_by | VARCHAR(26) | | 创建人 |

**索引**: UNIQUE KEY `uk_user_role` (`user_id`, `role_id`, `source`, `source_id`)

#### role_permission — 角色权限关联表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| role_id | VARCHAR(26) | FK, NOT NULL | 角色ID |
| permission_id | VARCHAR(26) | FK, NOT NULL | 权限ID |
| created_at | BIGINT | NOT NULL | 创建时间 |
| created_by | VARCHAR(26) | | 创建人 |

**索引**: UNIQUE KEY `uk_role_permission` (`role_id`, `permission_id`)

---

### 3.2 项目管理域

#### project — 项目表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| project_name | VARCHAR(128) | NOT NULL | 项目名称 |
| description | TEXT | | 项目描述 |
| visibility | TINYINT | DEFAULT 0 | 可见性: 0=私有 1=公开 |
| status | TINYINT | DEFAULT 1 | 状态: 1=活跃 2=归档 3=关闭 |
| owner_id | VARCHAR(26) | FK, NOT NULL | 项目所有者 |
| tech_stack | JSON | | 技术栈配置 |
| deleted | TINYINT | DEFAULT 0 | 逻辑删除 |
| version | INT | DEFAULT 0 | 乐观锁 |
| created_at | BIGINT | NOT NULL | 创建时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |
| created_by | VARCHAR(26) | NOT NULL | 创建人 |
| updated_by | VARCHAR(26) | | 更新人 |

#### project_member — 项目成员表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| project_id | VARCHAR(26) | FK, NOT NULL | 项目ID |
| user_id | VARCHAR(26) | FK, NOT NULL | 用户ID |
| role_code | VARCHAR(64) | NOT NULL | 项目内角色 (ADMIN/MEMBER/VIEWER) |
| joined_at | BIGINT | NOT NULL | 加入时间 |
| deleted | TINYINT | DEFAULT 0 | 逻辑删除 |
| created_at | BIGINT | NOT NULL | 创建时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |

**索引**: UNIQUE KEY `uk_project_member` (`project_id`, `user_id`)

---

### 3.3 会话消息域

#### session — 会话表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| title | VARCHAR(256) | | 会话标题 |
| type | TINYINT | NOT NULL DEFAULT 1 | 类型: 1=群聊 2=私聊 3=AI辅助 |
| project_id | VARCHAR(26) | FK | 关联项目ID |
| status | TINYINT | DEFAULT 1 | 状态: 1=活跃 2=归档 3=关闭 |
| is_public | TINYINT | DEFAULT 0 | 是否公开 |
| last_message_at | BIGINT | | 最后消息时间 |
| message_count | INT | DEFAULT 0 | 消息总数 |
| deleted | TINYINT | DEFAULT 0 | 逻辑删除 |
| version | INT | DEFAULT 0 | 乐观锁 |
| created_at | BIGINT | NOT NULL | 创建时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |
| created_by | VARCHAR(26) | NOT NULL | 创建人 |

#### session_member — 会话成员表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| session_id | VARCHAR(26) | FK, NOT NULL | 会话ID |
| user_id | VARCHAR(26) | FK, NOT NULL | 用户ID |
| role | TINYINT | DEFAULT 0 | 角色: 0=成员 1=管理员 |
| last_read_at | BIGINT | | 最后阅读时间 |
| is_muted | TINYINT | DEFAULT 0 | 是否静音 |
| joined_at | BIGINT | NOT NULL | 加入时间 |
| deleted | TINYINT | DEFAULT 0 | 逻辑删除 |
| created_at | BIGINT | NOT NULL | 创建时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |

**索引**: UNIQUE KEY `uk_session_member` (`session_id`, `user_id`)

#### session_message — 消息表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| session_id | VARCHAR(26) | FK, NOT NULL | 会话ID |
| sender_id | VARCHAR(26) | FK, NOT NULL | 发送者ID |
| message_type | VARCHAR(32) | NOT NULL DEFAULT 'text' | 消息类型: text/code/image/file/system/ai |
| content | TEXT | NOT NULL | 消息内容 (Markdown) |
| metadata | JSON | | 元数据 |
| reply_to | VARCHAR(26) | | 回复目标消息ID |
| mention_user_ids | JSON | | @提及的用户ID列表 |
| client_id | VARCHAR(64) | | 客户端消息ID (幂等) |
| edited | TINYINT | DEFAULT 0 | 是否已编辑 |
| deleted | TINYINT | DEFAULT 0 | 逻辑删除 |
| created_at | BIGINT | NOT NULL | 发送时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |

**索引**:
- KEY `idx_session_message_session` (`session_id`, `created_at`)
- KEY `idx_session_message_sender` (`sender_id`, `created_at`)
- UNIQUE KEY `uk_session_message_client` (`session_id`, `client_id`)

---

### 3.4 Agent 系统域

#### agent — Agent 配置表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| agent_name | VARCHAR(128) | NOT NULL | Agent 名称 |
| description | VARCHAR(1024) | | Agent 描述 |
| model_provider | VARCHAR(64) | NOT NULL | 模型提供商 (alibaba/deepseek/openai) |
| model_name | VARCHAR(128) | NOT NULL | 模型名称 |
| model_config | JSON | | 模型参数 (temperature/top_p/max_tokens等) |
| system_prompt | TEXT | | 系统提示词 |
| status | TINYINT | DEFAULT 1 | 状态: 1=启用 2=禁用 |
| version | INT | DEFAULT 0 | 版本号 |
| deleted | TINYINT | DEFAULT 0 | 逻辑删除 |
| created_at | BIGINT | NOT NULL | 创建时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |
| created_by | VARCHAR(26) | NOT NULL | 创建人 |
| updated_by | VARCHAR(26) | | 更新人 |

#### agent_tool — 工具注册表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| agent_id | VARCHAR(26) | FK, NOT NULL | Agent ID |
| tool_name | VARCHAR(128) | NOT NULL | 工具名称 |
| description | VARCHAR(1024) | | 工具描述 |
| tool_type | VARCHAR(64) | NOT NULL | 工具类型 (api/code/function/workflow) |
| endpoint | VARCHAR(512) | | API 端点 |
| request_schema | JSON | | 请求参数Schema |
| response_schema | JSON | | 响应参数Schema |
| auth_config | JSON | | 认证配置 |
| timeout_ms | INT | DEFAULT 30000 | 超时时间 |
| status | TINYINT | DEFAULT 1 | 状态: 1=启用 2=禁用 |
| sort_order | INT | DEFAULT 0 | 排序 |
| deleted | TINYINT | DEFAULT 0 | 逻辑删除 |
| created_at | BIGINT | NOT NULL | 创建时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |

**索引**: KEY `idx_agent_tool_agent` (`agent_id`, `tool_name`)

#### agent_permission — Agent 权限表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| agent_id | VARCHAR(26) | FK, NOT NULL | Agent ID |
| permission_code | VARCHAR(128) | NOT NULL | 权限编码 |
| effect | VARCHAR(16) | NOT NULL DEFAULT 'allow' | allow/deny |
| condition | JSON | | 条件表达式 |
| priority | INT | DEFAULT 0 | 优先级 (数字越大优先级越高) |
| created_at | BIGINT | NOT NULL | 创建时间 |
| created_by | VARCHAR(26) | | 创建人 |

#### agent_execution — Agent 执行记录表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| agent_id | VARCHAR(26) | FK, NOT NULL | Agent ID |
| session_id | VARCHAR(26) | FK | 所属会话ID |
| user_id | VARCHAR(26) | FK, NOT NULL | 发起人ID |
| status | VARCHAR(32) | NOT NULL | 状态: pending/running/completed/failed/cancelled |
| input | JSON | NOT NULL | 输入参数 |
| output | JSON | | 输出结果 |
| error_message | TEXT | | 错误信息 |
| tool_calls | JSON | | 工具调用记录 |
| tokens_used | INT | | Token 消耗 |
| started_at | BIGINT | | 开始时间 |
| completed_at | BIGINT | | 完成时间 |
| duration_ms | INT | | 执行耗时 |
| created_at | BIGINT | NOT NULL | 创建时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |

**索引**:
- KEY `idx_agent_execution_agent` (`agent_id`, `created_at`)
- KEY `idx_agent_execution_user` (`user_id`, `created_at`)
- KEY `idx_agent_execution_session` (`session_id`, `created_at`)

---

### 3.5 工作流引擎域

#### workflow — 工作流模板表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| workflow_name | VARCHAR(128) | NOT NULL | 工作流名称 |
| description | TEXT | | 工作流描述 |
| version | INT | NOT NULL DEFAULT 1 | 版本号 |
| status | TINYINT | DEFAULT 0 | 状态: 0=草稿 1=已发布 2=已归档 |
| category | VARCHAR(64) | | 分类 |
| config | JSON | | 工作流配置 (状态机定义) |
| published_at | BIGINT | | 发布时间 |
| deleted | TINYINT | DEFAULT 0 | 逻辑删除 |
| created_at | BIGINT | NOT NULL | 创建时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |
| created_by | VARCHAR(26) | NOT NULL | 创建人 |
| updated_by | VARCHAR(26) | | 更新人 |

#### workflow_node — 工作流节点表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| workflow_id | VARCHAR(26) | FK, NOT NULL | 工作流ID |
| node_name | VARCHAR(128) | NOT NULL | 节点名称 |
| node_type | VARCHAR(32) | NOT NULL | 节点类型: start/end/task/approval/condition/subprocess |
| config | JSON | | 节点配置 |
| position_x | FLOAT | | 画布 X 坐标 |
| position_y | FLOAT | | 画布 Y 坐标 |
| sort_order | INT | DEFAULT 0 | 排序顺序 |
| created_at | BIGINT | NOT NULL | 创建时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |

**索引**: KEY `idx_workflow_node_workflow` (`workflow_id`, `sort_order`)

#### workflow_transition — 工作流流转表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| workflow_id | VARCHAR(26) | FK, NOT NULL | 工作流ID |
| from_node_id | VARCHAR(26) | FK, NOT NULL | 源节点ID |
| to_node_id | VARCHAR(26) | FK, NOT NULL | 目标节点ID |
| condition_expr | TEXT | | 流转条件表达式 |
| label | VARCHAR(64) | | 流转标签 |
| sort_order | INT | DEFAULT 0 | 排序 |
| created_at | BIGINT | NOT NULL | 创建时间 |

**索引**: KEY `idx_workflow_transition_workflow` (`workflow_id`, `from_node_id`)

#### workflow_instance — 工作流实例表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| workflow_id | VARCHAR(26) | FK, NOT NULL | 工作流模板ID |
| workflow_version | INT | NOT NULL | 使用的版本号 |
| title | VARCHAR(256) | | 实例标题 |
| status | VARCHAR(32) | NOT NULL | 状态: running/completed/failed/suspended/terminated |
| initiator_id | VARCHAR(26) | FK, NOT NULL | 发起人ID |
| current_node_id | VARCHAR(26) | FK | 当前节点ID |
| variables | JSON | | 流程变量 |
| started_at | BIGINT | NOT NULL | 开始时间 |
| completed_at | BIGINT | | 完成时间 |
| deleted | TINYINT | DEFAULT 0 | 逻辑删除 |
| created_at | BIGINT | NOT NULL | 创建时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |

**索引**:
- KEY `idx_workflow_instance_workflow` (`workflow_id`, `created_at`)
- KEY `idx_workflow_instance_initiator` (`initiator_id`, `created_at`)
- KEY `idx_workflow_instance_status` (`status`, `created_at`)

#### workflow_node_instance — 工作流节点实例表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| instance_id | VARCHAR(26) | FK, NOT NULL | 工作流实例ID |
| node_id | VARCHAR(26) | FK, NOT NULL | 节点模板ID |
| node_name | VARCHAR(128) | NOT NULL | 节点名称 |
| node_type | VARCHAR(32) | NOT NULL | 节点类型 |
| status | VARCHAR(32) | NOT NULL | 状态: pending/running/completed/failed/skipped |
| assignee_id | VARCHAR(26) | | 处理人ID |
| input | JSON | | 节点输入 |
| output | JSON | | 节点输出 |
| started_at | BIGINT | | 开始时间 |
| completed_at | BIGINT | | 完成时间 |
| remark | TEXT | | 处理备注 |
| created_at | BIGINT | NOT NULL | 创建时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |

**索引**: KEY `idx_workflow_node_inst_instance` (`instance_id`, `node_id`)

---

### 3.6 审计与安全域

#### audit_log — 审计日志表（流水型，无逻辑删除）

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| user_id | VARCHAR(26) | FK | 操作用户ID |
| username | VARCHAR(64) | | 用户名（冗余，用户删除后仍可追溯） |
| action_type | VARCHAR(64) | NOT NULL | 操作类型 (LOGIN/CREATE/UPDATE/DELETE/EXECUTE) |
| resource_type | VARCHAR(64) | NOT NULL | 资源类型 (USER/PROJECT/SESSION/AGENT/WORKFLOW) |
| resource_id | VARCHAR(26) | | 资源ID |
| detail | JSON | | 操作详情 (变更前后对比) |
| ip_address | VARCHAR(45) | | 客户端IP |
| user_agent | VARCHAR(512) | | 客户端UA |
| result | TINYINT | NOT NULL | 结果: 1=成功 0=失败 |
| error_message | TEXT | | 失败原因 |
| duration_ms | INT | | 操作耗时 |
| created_at | BIGINT | NOT NULL | 操作时间 |

**索引**:
- KEY `idx_audit_log_user` (`user_id`, `created_at`)
- KEY `idx_audit_log_action` (`action_type`, `created_at`)
- KEY `idx_audit_log_resource` (`resource_type`, `resource_id`, `created_at`)
- KEY `idx_audit_log_time` (`created_at`)

#### api_key — API 密钥表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| user_id | VARCHAR(26) | FK, NOT NULL | 所属用户ID |
| key_name | VARCHAR(64) | NOT NULL | 密钥名称 |
| key_hash | VARCHAR(256) | NOT NULL | 密钥哈希 (SM3) |
| key_prefix | VARCHAR(8) | NOT NULL | 密钥前缀 (用于识别) |
| permissions | JSON | | 权限范围 |
| expires_at | BIGINT | | 过期时间 (null=永不过期) |
| last_used_at | BIGINT | | 最后使用时间 |
| status | TINYINT | DEFAULT 1 | 状态: 1=启用 2=禁用 |
| deleted | TINYINT | DEFAULT 0 | 逻辑删除 |
| created_at | BIGINT | NOT NULL | 创建时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |

**索引**: KEY `idx_api_key_user` (`user_id`)

---

### 3.7 沙箱配置域

#### sandbox_config — 沙箱配置表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| runtime | VARCHAR(32) | NOT NULL | 运行时类型: docker/gvisor/firecracker |
| image | VARCHAR(256) | | 镜像地址 |
| cpu_limit | INT | DEFAULT 1 | CPU 核心数限制 |
| memory_limit_mb | INT | DEFAULT 512 | 内存限制 (MB) |
| disk_limit_mb | INT | DEFAULT 1024 | 磁盘限制 (MB) |
| network_enabled | TINYINT | DEFAULT 0 | 是否启用网络 |
| seccomp_profile | TEXT | | Seccomp 安全策略 |
| apparmor_profile | TEXT | | AppArmor 安全策略 |
| read_only_fs | TINYINT | DEFAULT 1 | 文件系统是否只读 |
| allowed_mounts | JSON | | 允许挂载的路径 |
| env_vars | JSON | | 环境变量 |
| timeout_seconds | INT | DEFAULT 300 | 超时时间 |
| status | TINYINT | DEFAULT 1 | 状态: 1=启用 2=禁用 |
| created_at | BIGINT | NOT NULL | 创建时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |

---

## 4. DDL 脚本

### 4.1 OceanBase/MySQL DDL

```sql
-- ========================================
-- 格物平台 - 数据库初始化脚本
-- 目标数据库: OceanBase 4.2+ (MySQL模式) / MySQL 8.0
-- 字符集: utf8mb4
-- ========================================

CREATE DATABASE IF NOT EXISTS gewu
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE gewu;

-- ========================================
-- 4.1 用户与权限域
-- ========================================

-- 用户账户表
CREATE TABLE IF NOT EXISTS user_account (
    id              VARCHAR(26)     NOT NULL COMMENT 'ULID 主键',
    username        VARCHAR(64)     NOT NULL COMMENT '用户名',
    email           VARCHAR(128)    NOT NULL COMMENT '邮箱',
    phone           VARCHAR(20)              COMMENT '手机号',
    password_hash   VARCHAR(256)    NOT NULL COMMENT 'SM3密码哈希',
    password_salt   VARCHAR(64)     NOT NULL COMMENT '密码盐值',
    display_name    VARCHAR(64)     NOT NULL COMMENT '显示名称',
    avatar_url      VARCHAR(512)             COMMENT '头像URL',
    status          TINYINT         NOT NULL DEFAULT 1 COMMENT '1=启用 2=禁用 3=锁定',
    last_login_at   BIGINT                   COMMENT '最后登录时间',
    last_login_ip   VARCHAR(45)              COMMENT '最后登录IP',
    login_fail_count INT            DEFAULT 0 COMMENT '登录失败次数',
    locked_until    BIGINT                   COMMENT '锁定截止时间',
    deleted         TINYINT         DEFAULT 0 COMMENT '逻辑删除',
    version         INT             DEFAULT 0 COMMENT '乐观锁',
    created_at      BIGINT          NOT NULL COMMENT '创建时间戳',
    updated_at      BIGINT          NOT NULL COMMENT '更新时间戳',
    created_by      VARCHAR(26)              COMMENT '创建人ID',
    updated_by      VARCHAR(26)              COMMENT '更新人ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_account_username (username),
    UNIQUE KEY uk_user_account_email (email),
    UNIQUE KEY uk_user_account_phone (phone),
    KEY idx_user_account_status (status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户账户表';

-- 角色表
CREATE TABLE IF NOT EXISTS role (
    id              VARCHAR(26)     NOT NULL COMMENT 'ULID 主键',
    role_name       VARCHAR(64)     NOT NULL COMMENT '角色名称',
    role_code       VARCHAR(64)     NOT NULL COMMENT '角色编码',
    description     VARCHAR(512)             COMMENT '角色描述',
    is_system       TINYINT         DEFAULT 0 COMMENT '是否系统内置',
    sort_order      INT             DEFAULT 0 COMMENT '排序顺序',
    deleted         TINYINT         DEFAULT 0 COMMENT '逻辑删除',
    created_at      BIGINT          NOT NULL COMMENT '创建时间戳',
    updated_at      BIGINT          NOT NULL COMMENT '更新时间戳',
    created_by      VARCHAR(26)              COMMENT '创建人ID',
    updated_by      VARCHAR(26)              COMMENT '更新人ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表';

-- 权限表
CREATE TABLE IF NOT EXISTS permission (
    id              VARCHAR(26)     NOT NULL COMMENT 'ULID 主键',
    permission_code VARCHAR(128)    NOT NULL COMMENT '权限编码',
    permission_name VARCHAR(64)     NOT NULL COMMENT '权限名称',
    resource_type   VARCHAR(64)     NOT NULL COMMENT '资源类型',
    action          VARCHAR(64)     NOT NULL COMMENT '操作',
    description     VARCHAR(512)             COMMENT '权限描述',
    deleted         TINYINT         DEFAULT 0 COMMENT '逻辑删除',
    created_at      BIGINT          NOT NULL COMMENT '创建时间戳',
    updated_at      BIGINT          NOT NULL COMMENT '更新时间戳',
    PRIMARY KEY (id),
    UNIQUE KEY uk_permission_code (permission_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限表';

-- 用户角色关联表
CREATE TABLE IF NOT EXISTS user_role (
    id              VARCHAR(26)     NOT NULL COMMENT 'ULID 主键',
    user_id         VARCHAR(26)     NOT NULL COMMENT '用户ID',
    role_id         VARCHAR(26)     NOT NULL COMMENT '角色ID',
    source          VARCHAR(32)     DEFAULT 'system' COMMENT '来源: system/project',
    source_id       VARCHAR(26)              COMMENT '来源ID',
    created_at      BIGINT          NOT NULL COMMENT '创建时间戳',
    created_by      VARCHAR(26)              COMMENT '创建人ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role (user_id, role_id, source, source_id),
    KEY idx_user_role_user (user_id),
    KEY idx_user_role_role (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关联表';

-- 角色权限关联表
CREATE TABLE IF NOT EXISTS role_permission (
    id              VARCHAR(26)     NOT NULL COMMENT 'ULID 主键',
    role_id         VARCHAR(26)     NOT NULL COMMENT '角色ID',
    permission_id   VARCHAR(26)     NOT NULL COMMENT '权限ID',
    created_at      BIGINT          NOT NULL COMMENT '创建时间戳',
    created_by      VARCHAR(26)              COMMENT '创建人ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_permission (role_id, permission_id),
    KEY idx_role_permission_permission (permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色权限关联表';

-- ========================================
-- 4.2 项目管理域
-- ========================================

-- 项目表
CREATE TABLE IF NOT EXISTS project (
    id              VARCHAR(26)     NOT NULL COMMENT 'ULID 主键',
    project_name    VARCHAR(128)    NOT NULL COMMENT '项目名称',
    description     TEXT                     COMMENT '项目描述',
    visibility      TINYINT         DEFAULT 0 COMMENT '0=私有 1=公开',
    status          TINYINT         DEFAULT 1 COMMENT '1=活跃 2=归档 3=关闭',
    owner_id        VARCHAR(26)     NOT NULL COMMENT '项目所有者ID',
    tech_stack      JSON                     COMMENT '技术栈配置',
    deleted         TINYINT         DEFAULT 0 COMMENT '逻辑删除',
    version         INT             DEFAULT 0 COMMENT '乐观锁',
    created_at      BIGINT          NOT NULL COMMENT '创建时间戳',
    updated_at      BIGINT          NOT NULL COMMENT '更新时间戳',
    created_by      VARCHAR(26)     NOT NULL COMMENT '创建人ID',
    updated_by      VARCHAR(26)              COMMENT '更新人ID',
    PRIMARY KEY (id),
    KEY idx_project_owner (owner_id, deleted),
    KEY idx_project_status (status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目表';

-- 项目成员表
CREATE TABLE IF NOT EXISTS project_member (
    id              VARCHAR(26)     NOT NULL COMMENT 'ULID 主键',
    project_id      VARCHAR(26)     NOT NULL COMMENT '项目ID',
    user_id         VARCHAR(26)     NOT NULL COMMENT '用户ID',
    role_code       VARCHAR(64)     NOT NULL COMMENT '项目内角色',
    joined_at       BIGINT          NOT NULL COMMENT '加入时间',
    deleted         TINYINT         DEFAULT 0 COMMENT '逻辑删除',
    created_at      BIGINT          NOT NULL COMMENT '创建时间戳',
    updated_at      BIGINT          NOT NULL COMMENT '更新时间戳',
    PRIMARY KEY (id),
    UNIQUE KEY uk_project_member (project_id, user_id),
    KEY idx_project_member_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目成员表';

-- ========================================
-- 4.3 会话消息域
-- ========================================

-- 会话表
CREATE TABLE IF NOT EXISTS session (
    id              VARCHAR(26)     NOT NULL COMMENT 'ULID 主键',
    title           VARCHAR(256)            COMMENT '会话标题',
    type            TINYINT         NOT NULL DEFAULT 1 COMMENT '1=群聊 2=私聊 3=AI辅助',
    project_id      VARCHAR(26)             COMMENT '关联项目ID',
    status          TINYINT         DEFAULT 1 COMMENT '1=活跃 2=归档 3=关闭',
    is_public       TINYINT         DEFAULT 0 COMMENT '0=私有 1=公开',
    last_message_at BIGINT                   COMMENT '最后消息时间',
    message_count   INT             DEFAULT 0 COMMENT '消息总数',
    deleted         TINYINT         DEFAULT 0 COMMENT '逻辑删除',
    version         INT             DEFAULT 0 COMMENT '乐观锁',
    created_at      BIGINT          NOT NULL COMMENT '创建时间戳',
    updated_at      BIGINT          NOT NULL COMMENT '更新时间戳',
    created_by      VARCHAR(26)     NOT NULL COMMENT '创建人ID',
    PRIMARY KEY (id),
    KEY idx_session_project (project_id, created_at),
    KEY idx_session_type (type, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话表';

-- 会话成员表
CREATE TABLE IF NOT EXISTS session_member (
    id              VARCHAR(26)     NOT NULL COMMENT 'ULID 主键',
    session_id      VARCHAR(26)     NOT NULL COMMENT '会话ID',
    user_id         VARCHAR(26)     NOT NULL COMMENT '用户ID',
    role            TINYINT         DEFAULT 0 COMMENT '0=成员 1=管理员',
    last_read_at    BIGINT                   COMMENT '最后阅读时间',
    is_muted        TINYINT         DEFAULT 0 COMMENT '是否静音',
    joined_at       BIGINT          NOT NULL COMMENT '加入时间',
    deleted         TINYINT         DEFAULT 0 COMMENT '逻辑删除',
    created_at      BIGINT          NOT NULL COMMENT '创建时间戳',
    updated_at      BIGINT          NOT NULL COMMENT '更新时间戳',
    PRIMARY KEY (id),
    UNIQUE KEY uk_session_member (session_id, user_id),
    KEY idx_session_member_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话成员表';

-- 消息表
CREATE TABLE IF NOT EXISTS session_message (
    id              VARCHAR(26)     NOT NULL COMMENT 'ULID 主键',
    session_id      VARCHAR(26)     NOT NULL COMMENT '会话ID',
    sender_id       VARCHAR(26)     NOT NULL COMMENT '发送者ID',
    message_type    VARCHAR(32)     NOT NULL DEFAULT 'text' COMMENT 'text/code/image/file/system/ai',
    content         TEXT            NOT NULL COMMENT '消息内容(Markdown)',
    metadata        JSON                     COMMENT '元数据',
    reply_to        VARCHAR(26)              COMMENT '回复目标消息ID',
    mention_user_ids JSON                    COMMENT '@提及用户ID列表',
    client_id       VARCHAR(64)              COMMENT '客户端消息ID(幂等)',
    edited          TINYINT         DEFAULT 0 COMMENT '是否已编辑',
    deleted         TINYINT         DEFAULT 0 COMMENT '逻辑删除',
    created_at      BIGINT          NOT NULL COMMENT '发送时间戳',
    updated_at      BIGINT          NOT NULL COMMENT '更新时间戳',
    PRIMARY KEY (id),
    KEY idx_session_message_session (session_id, created_at),
    KEY idx_session_message_sender (sender_id, created_at),
    UNIQUE KEY uk_session_message_client (session_id, client_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息表';

-- ========================================
-- 4.4 Agent 系统域
-- ========================================

-- Agent 配置表
CREATE TABLE IF NOT EXISTS agent (
    id              VARCHAR(26)     NOT NULL COMMENT 'ULID 主键',
    agent_name      VARCHAR(128)    NOT NULL COMMENT 'Agent名称',
    description     VARCHAR(1024)           COMMENT 'Agent描述',
    model_provider  VARCHAR(64)     NOT NULL COMMENT '模型提供商',
    model_name      VARCHAR(128)    NOT NULL COMMENT '模型名称',
    model_config    JSON                     COMMENT '模型参数',
    system_prompt   TEXT                     COMMENT '系统提示词',
    status          TINYINT         DEFAULT 1 COMMENT '1=启用 2=禁用',
    version         INT             DEFAULT 0 COMMENT '版本号',
    deleted         TINYINT         DEFAULT 0 COMMENT '逻辑删除',
    created_at      BIGINT          NOT NULL COMMENT '创建时间戳',
    updated_at      BIGINT          NOT NULL COMMENT '更新时间戳',
    created_by      VARCHAR(26)     NOT NULL COMMENT '创建人ID',
    updated_by      VARCHAR(26)              COMMENT '更新人ID',
    PRIMARY KEY (id),
    KEY idx_agent_status (status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent配置表';

-- 工具注册表
CREATE TABLE IF NOT EXISTS agent_tool (
    id              VARCHAR(26)     NOT NULL COMMENT 'ULID 主键',
    agent_id        VARCHAR(26)     NOT NULL COMMENT 'Agent ID',
    tool_name       VARCHAR(128)    NOT NULL COMMENT '工具名称',
    description     VARCHAR(1024)           COMMENT '工具描述',
    tool_type       VARCHAR(64)     NOT NULL COMMENT 'api/code/function/workflow',
    endpoint        VARCHAR(512)            COMMENT 'API端点',
    request_schema  JSON                     COMMENT '请求参数Schema',
    response_schema JSON                     COMMENT '响应参数Schema',
    auth_config     JSON                     COMMENT '认证配置',
    timeout_ms      INT             DEFAULT 30000 COMMENT '超时时间(ms)',
    status          TINYINT         DEFAULT 1 COMMENT '1=启用 2=禁用',
    sort_order      INT             DEFAULT 0 COMMENT '排序',
    deleted         TINYINT         DEFAULT 0 COMMENT '逻辑删除',
    created_at      BIGINT          NOT NULL COMMENT '创建时间戳',
    updated_at      BIGINT          NOT NULL COMMENT '更新时间戳',
    PRIMARY KEY (id),
    KEY idx_agent_tool_agent (agent_id, tool_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工具注册表';

-- Agent 权限表
CREATE TABLE IF NOT EXISTS agent_permission (
    id              VARCHAR(26)     NOT NULL COMMENT 'ULID 主键',
    agent_id        VARCHAR(26)     NOT NULL COMMENT 'Agent ID',
    permission_code VARCHAR(128)    NOT NULL COMMENT '权限编码',
    effect          VARCHAR(16)     NOT NULL DEFAULT 'allow' COMMENT 'allow/deny',
    condition       JSON                     COMMENT '条件表达式',
    priority        INT             DEFAULT 0 COMMENT '优先级',
    created_at      BIGINT          NOT NULL COMMENT '创建时间戳',
    created_by      VARCHAR(26)              COMMENT '创建人ID',
    PRIMARY KEY (id),
    KEY idx_agent_permission_agent (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent权限表';

-- Agent 执行记录表
CREATE TABLE IF NOT EXISTS agent_execution (
    id              VARCHAR(26)     NOT NULL COMMENT 'ULID 主键',
    agent_id        VARCHAR(26)     NOT NULL COMMENT 'Agent ID',
    session_id      VARCHAR(26)              COMMENT '所属会话ID',
    user_id         VARCHAR(26)     NOT NULL COMMENT '发起人ID',
    status          VARCHAR(32)     NOT NULL COMMENT 'pending/running/completed/failed/cancelled',
    input           JSON            NOT NULL COMMENT '输入参数',
    output          JSON                     COMMENT '输出结果',
    error_message   TEXT                     COMMENT '错误信息',
    tool_calls      JSON                     COMMENT '工具调用记录',
    tokens_used     INT                      COMMENT 'Token消耗',
    started_at      BIGINT                   COMMENT '开始时间',
    completed_at    BIGINT                   COMMENT '完成时间',
    duration_ms     INT                      COMMENT '执行耗时(ms)',
    created_at      BIGINT          NOT NULL COMMENT '创建时间戳',
    updated_at      BIGINT          NOT NULL COMMENT '更新时间戳',
    PRIMARY KEY (id),
    KEY idx_agent_execution_agent (agent_id, created_at),
    KEY idx_agent_execution_user (user_id, created_at),
    KEY idx_agent_execution_session (session_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent执行记录表';

-- ========================================
-- 4.5 工作流引擎域
-- ========================================

-- 工作流模板表
CREATE TABLE IF NOT EXISTS workflow (
    id              VARCHAR(26)     NOT NULL COMMENT 'ULID 主键',
    workflow_name   VARCHAR(128)    NOT NULL COMMENT '工作流名称',
    description     TEXT                     COMMENT '工作流描述',
    version         INT             NOT NULL DEFAULT 1 COMMENT '版本号',
    status          TINYINT         DEFAULT 0 COMMENT '0=草稿 1=已发布 2=已归档',
    category        VARCHAR(64)              COMMENT '分类',
    config          JSON                     COMMENT '工作流配置',
    published_at    BIGINT                   COMMENT '发布时间',
    deleted         TINYINT         DEFAULT 0 COMMENT '逻辑删除',
    created_at      BIGINT          NOT NULL COMMENT '创建时间戳',
    updated_at      BIGINT          NOT NULL COMMENT '更新时间戳',
    created_by      VARCHAR(26)     NOT NULL COMMENT '创建人ID',
    updated_by      VARCHAR(26)              COMMENT '更新人ID',
    PRIMARY KEY (id),
    KEY idx_workflow_status (status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流模板表';

-- 工作流节点表
CREATE TABLE IF NOT EXISTS workflow_node (
    id              VARCHAR(26)     NOT NULL COMMENT 'ULID 主键',
    workflow_id     VARCHAR(26)     NOT NULL COMMENT '工作流ID',
    node_name       VARCHAR(128)    NOT NULL COMMENT '节点名称',
    node_type       VARCHAR(32)     NOT NULL COMMENT 'start/end/task/approval/condition/subprocess',
    config          JSON                     COMMENT '节点配置',
    position_x      FLOAT                    COMMENT '画布X坐标',
    position_y      FLOAT                    COMMENT '画布Y坐标',
    sort_order      INT             DEFAULT 0 COMMENT '排序顺序',
    created_at      BIGINT          NOT NULL COMMENT '创建时间戳',
    updated_at      BIGINT          NOT NULL COMMENT '更新时间戳',
    PRIMARY KEY (id),
    KEY idx_workflow_node_workflow (workflow_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流节点表';

-- 工作流流转表
CREATE TABLE IF NOT EXISTS workflow_transition (
    id              VARCHAR(26)     NOT NULL COMMENT 'ULID 主键',
    workflow_id     VARCHAR(26)     NOT NULL COMMENT '工作流ID',
    from_node_id    VARCHAR(26)     NOT NULL COMMENT '源节点ID',
    to_node_id      VARCHAR(26)     NOT NULL COMMENT '目标节点ID',
    condition_expr  TEXT                     COMMENT '流转条件表达式',
    label           VARCHAR(64)              COMMENT '流转标签',
    sort_order      INT             DEFAULT 0 COMMENT '排序',
    created_at      BIGINT          NOT NULL COMMENT '创建时间戳',
    PRIMARY KEY (id),
    KEY idx_workflow_transition_workflow (workflow_id, from_node_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流流转表';

-- 工作流实例表
CREATE TABLE IF NOT EXISTS workflow_instance (
    id              VARCHAR(26)     NOT NULL COMMENT 'ULID 主键',
    workflow_id     VARCHAR(26)     NOT NULL COMMENT '工作流模板ID',
    workflow_version INT            NOT NULL COMMENT '使用的版本号',
    title           VARCHAR(256)            COMMENT '实例标题',
    status          VARCHAR(32)     NOT NULL COMMENT 'running/completed/failed/suspended/terminated',
    initiator_id    VARCHAR(26)     NOT NULL COMMENT '发起人ID',
    current_node_id VARCHAR(26)              COMMENT '当前节点ID',
    variables       JSON                     COMMENT '流程变量',
    started_at      BIGINT          NOT NULL COMMENT '开始时间',
    completed_at    BIGINT                   COMMENT '完成时间',
    deleted         TINYINT         DEFAULT 0 COMMENT '逻辑删除',
    created_at      BIGINT          NOT NULL COMMENT '创建时间戳',
    updated_at      BIGINT          NOT NULL COMMENT '更新时间戳',
    PRIMARY KEY (id),
    KEY idx_workflow_instance_workflow (workflow_id, created_at),
    KEY idx_workflow_instance_initiator (initiator_id, created_at),
    KEY idx_workflow_instance_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流实例表';

-- 工作流节点实例表
CREATE TABLE IF NOT EXISTS workflow_node_instance (
    id              VARCHAR(26)     NOT NULL COMMENT 'ULID 主键',
    instance_id     VARCHAR(26)     NOT NULL COMMENT '工作流实例ID',
    node_id         VARCHAR(26)     NOT NULL COMMENT '节点模板ID',
    node_name       VARCHAR(128)    NOT NULL COMMENT '节点名称',
    node_type       VARCHAR(32)     NOT NULL COMMENT '节点类型',
    status          VARCHAR(32)     NOT NULL COMMENT 'pending/running/completed/failed/skipped',
    assignee_id     VARCHAR(26)              COMMENT '处理人ID',
    input           JSON                     COMMENT '节点输入',
    output          JSON                     COMMENT '节点输出',
    started_at      BIGINT                   COMMENT '开始时间',
    completed_at    BIGINT                   COMMENT '完成时间',
    remark          TEXT                     COMMENT '处理备注',
    created_at      BIGINT          NOT NULL COMMENT '创建时间戳',
    updated_at      BIGINT          NOT NULL COMMENT '更新时间戳',
    PRIMARY KEY (id),
    KEY idx_workflow_node_inst_instance (instance_id, node_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流节点实例表';

-- ========================================
-- 4.6 审计与安全域
-- ========================================

-- 审计日志表 (流水型，无逻辑删除)
CREATE TABLE IF NOT EXISTS audit_log (
    id              VARCHAR(26)     NOT NULL COMMENT 'ULID 主键',
    user_id         VARCHAR(26)              COMMENT '操作用户ID',
    username        VARCHAR(64)              COMMENT '用户名(冗余)',
    action_type     VARCHAR(64)     NOT NULL COMMENT '操作类型',
    resource_type   VARCHAR(64)     NOT NULL COMMENT '资源类型',
    resource_id     VARCHAR(26)              COMMENT '资源ID',
    detail          JSON                     COMMENT '操作详情',
    ip_address      VARCHAR(45)              COMMENT '客户端IP',
    user_agent      VARCHAR(512)             COMMENT '客户端UA',
    result          TINYINT         NOT NULL COMMENT '1=成功 0=失败',
    error_message   TEXT                     COMMENT '失败原因',
    duration_ms     INT                      COMMENT '操作耗时(ms)',
    created_at      BIGINT          NOT NULL COMMENT '操作时间戳',
    PRIMARY KEY (id),
    KEY idx_audit_log_user (user_id, created_at),
    KEY idx_audit_log_action (action_type, created_at),
    KEY idx_audit_log_resource (resource_type, resource_id, created_at),
    KEY idx_audit_log_time (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审计日志表';

-- API 密钥表
CREATE TABLE IF NOT EXISTS api_key (
    id              VARCHAR(26)     NOT NULL COMMENT 'ULID 主键',
    user_id         VARCHAR(26)     NOT NULL COMMENT '所属用户ID',
    key_name        VARCHAR(64)     NOT NULL COMMENT '密钥名称',
    key_hash        VARCHAR(256)    NOT NULL COMMENT '密钥哈希(SM3)',
    key_prefix      VARCHAR(8)      NOT NULL COMMENT '密钥前缀',
    permissions     JSON                     COMMENT '权限范围',
    expires_at      BIGINT                   COMMENT '过期时间',
    last_used_at    BIGINT                   COMMENT '最后使用时间',
    status          TINYINT         DEFAULT 1 COMMENT '1=启用 2=禁用',
    deleted         TINYINT         DEFAULT 0 COMMENT '逻辑删除',
    created_at      BIGINT          NOT NULL COMMENT '创建时间戳',
    updated_at      BIGINT          NOT NULL COMMENT '更新时间戳',
    PRIMARY KEY (id),
    KEY idx_api_key_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='API密钥表';

-- ========================================
-- 4.7 沙箱配置域
-- ========================================

-- 沙箱配置表
CREATE TABLE IF NOT EXISTS sandbox_config (
    id              VARCHAR(26)     NOT NULL COMMENT 'ULID 主键',
    runtime         VARCHAR(32)     NOT NULL COMMENT 'docker/gvisor/firecracker',
    image           VARCHAR(256)            COMMENT '镜像地址',
    cpu_limit       INT             DEFAULT 1 COMMENT 'CPU核心数',
    memory_limit_mb INT             DEFAULT 512 COMMENT '内存限制(MB)',
    disk_limit_mb   INT             DEFAULT 1024 COMMENT '磁盘限制(MB)',
    network_enabled TINYINT         DEFAULT 0 COMMENT '是否启用网络',
    seccomp_profile TEXT                     COMMENT 'Seccomp策略',
    apparmor_profile TEXT                    COMMENT 'AppArmor策略',
    read_only_fs    TINYINT         DEFAULT 1 COMMENT '文件系统只读',
    allowed_mounts  JSON                     COMMENT '允许挂载路径',
    env_vars        JSON                     COMMENT '环境变量',
    timeout_seconds INT             DEFAULT 300 COMMENT '超时时间(s)',
    status          TINYINT         DEFAULT 1 COMMENT '1=启用 2=禁用',
    created_at      BIGINT          NOT NULL COMMENT '创建时间戳',
    updated_at      BIGINT          NOT NULL COMMENT '更新时间戳',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='沙箱配置表';

-- ========================================
-- 4.8 种子数据
-- ========================================

-- 初始角色
INSERT INTO role (id, role_name, role_code, description, is_system, sort_order, created_at, updated_at) VALUES
('01ARZ3NDEKTSV4RRFFQ69G5F01', '系统管理员', 'SUPER_ADMIN', '系统超级管理员，拥有全部权限', 1, 1, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),
('01ARZ3NDEKTSV4RRFFQ69G5F02', '架构师', 'ARCHITECT', '系统架构师，负责技术架构和设计', 1, 2, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),
('01ARZ3NDEKTSV4RRFFQ69G5F03', '产品经理', 'PRODUCT_MANAGER', '产品经理，负责需求管理', 1, 3, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),
('01ARZ3NDEKTSV4RRFFQ69G5F04', '后端开发', 'BACKEND_DEV', '后端开发工程师', 1, 4, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),
('01ARZ3NDEKTSV4RRFFQ69G5F05', '前端开发', 'FRONTEND_DEV', '前端开发工程师', 1, 5, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),
('01ARZ3NDEKTSV4RRFFQ69G5F06', '测试工程师', 'TESTER', '测试工程师，负责质量保障', 1, 6, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),
('01ARZ3NDEKTSV4RRFFQ69G5F07', '运维工程师', 'DEVOPS', '运维工程师，负责部署和监控', 1, 7, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),
('01ARZ3NDEKTSV4RRFFQ69G5F08', '安全工程师', 'SECURITY', '安全工程师，负责安全合规', 1, 8, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),
('01ARZ3NDEKTSV4RRFFQ69G5F09', '普通成员', 'MEMBER', '普通项目成员', 1, 9, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000);
```

### 4.2 达梦 DDL 差异说明

| 差异项 | OceanBase/MySQL | 达梦 | 适配方案 |
|--------|----------------|------|----------|
| 自增/ULID | VARCHAR + 应用生成 | VARCHAR + 应用生成 | 兼容，无需修改 |
| JSON 类型 | JSON | VARCHAR/TEXT | 使用 MyBatis-Plus 类型处理器 |
| TINYINT | TINYINT | SMALLINT | 建表时替换 TINYINT → SMALLINT |
| COMMENT | 行内 COMMENT | 独立的 COMMENT ON | 使用 MyBatis-Plus DDL 自动生成 |
| 引擎 | ENGINE=InnoDB | 无需指定 | DDL 中移除 engine 子句 |

### 4.3 人大金仓 DDL 差异说明

| 差异项 | OceanBase/MySQL | 人大金仓 | 适配方案 |
|--------|----------------|----------|----------|
| 兼容模式 | MySQL 模式 | PostgreSQL 兼容模式 | 推荐使用 PostgreSQL 兼容模式 |
| 序列 | AUTO_INCREMENT | SERIAL/BIGSERIAL | 主键使用 ULID 应用生成 |
| JSON | JSON | JSONB | 兼容 |
| 引擎 | ENGINE=InnoDB | 无需指定 | DDL 中移除 engine 子句 |

---

## 5. 索引设计

### 5.1 索引策略

| 索引类型 | 策略 | 说明 |
|----------|------|------|
| 主键索引 | ULID 聚簇索引 | 时间顺序插入，减少页分裂 |
| 唯一索引 | 业务唯一键 | 确保数据完整性 |
| 普通索引 | 高频查询字段 | 覆盖 WHERE/JOIN/ORDER BY 场景 |
| 联合索引 | 多条件查询 | 遵循最左前缀原则 |
| 覆盖索引 | 包含所有查询字段 | 避免回表查询 |

### 5.2 高频查询索引覆盖分析

| 查询场景 | 涉及表 | 索引策略 | 覆盖程度 |
|----------|--------|----------|----------|
| 用户登录 (by email/username) | user_account | UK on email, UK on username | ✅ 完全覆盖 |
| 项目成员列表 | project_member | UK on (project_id,user_id) | ✅ 完全覆盖 |
| 会话消息列表 (分页) | session_message | KEY on (session_id,created_at) | ✅ 完全覆盖 |
| Agent 执行历史 | agent_execution | KEY on (agent_id,created_at) | ✅ 完全覆盖 |
| 工作流实例查询 | workflow_instance | KEY on (initiator_id,created_at) | ✅ 完全覆盖 |
| 审计日志查询 | audit_log | KEY on (user_id,created_at) | ⚠️ 需覆盖索引优化 |

### 5.3 索引维护建议

| 维护项 | 频率 | 说明 |
|--------|------|------|
| 慢查询日志分析 | 每日 | 识别缺失索引或低效查询 |
| 索引碎片整理 | 每月 | OceanBase 自动维护，MySQL 需定期 OPTIMIZE |
| 冗余索引清理 | 每季度 | 移除重复或未被使用的索引 |
| 查询计划分析 | 每季度 | 验证索引是否被正确使用 |

---

## 6. 数据字典

### 6.1 状态枚举

| 字段 | 值 | 说明 |
|------|----|------|
| user_account.status | 1=启用, 2=禁用, 3=锁定 | 用户状态 |
| session.type | 1=群聊, 2=私聊, 3=AI辅助 | 会话类型 |
| session.status | 1=活跃, 2=归档, 3=关闭 | 会话状态 |
| agent_execution.status | pending/running/completed/failed/cancelled | 执行状态 |
| workflow.status | 0=草稿, 1=已发布, 2=已归档 | 工作流模板状态 |
| workflow_instance.status | running/completed/failed/suspended/terminated | 工作流实例状态 |
| workflow_node_instance.status | pending/running/completed/failed/skipped | 节点实例状态 |
| message_type | text/code/image/file/system/ai | 消息类型 |
| sandbox_config.runtime | docker/gvisor/firecracker | 沙箱运行时类型 |

---

## 7. 多数据库支持方案

### 7.1 架构设计

```
┌────────────────────────────────────────────────────────────┐
│                      应用层 (Application)                    │
│  纯业务逻辑，不感知具体数据库类型                             │
└────────────────────────┬───────────────────────────────────┘
                         │
┌────────────────────────▼───────────────────────────────────┐
│                    Repository 接口 (Domain)                  │
│  业务语义接口：UserRepository, ProjectRepository, ...        │
└────────────────────────┬───────────────────────────────────┘
                         │
┌────────────────────────▼───────────────────────────────────┐
│                MyBatis-Plus 数据访问层                       │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐   │
│  │ OceanBase│  │  MySQL   │  │  达梦     │  │ 人大金仓  │   │
│  │ 驱动     │  │  驱动    │  │  驱动     │  │  驱动    │   │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘   │
└────────────────────────────────────────────────────────────┘
```

### 7.2 数据源切换配置

```yaml
# application.yml 多数据源配置
gewu:
  database:
    type: oceanbase  # oceanbase | mysql | dm | kingbase

spring:
  datasource:
    # OceanBase (MySQL兼容模式)
    oceanbase:
      url: jdbc:mysql://localhost:2881/gewu?useUnicode=true&characterEncoding=utf-8
      driver-class-name: com.mysql.cj.jdbc.Driver
    # MySQL
    mysql:
      url: jdbc:mysql://localhost:3306/gewu?useUnicode=true&characterEncoding=utf-8
      driver-class-name: com.mysql.cj.jdbc.Driver
    # 达梦
    dm:
      url: jdbc:dm://localhost:5236/gewu
      driver-class-name: dm.jdbc.driver.DmDriver
    # 人大金仓
    kingbase:
      url: jdbc:kingbase8://localhost:54321/gewu
      driver-class-name: com.kingbase8.Driver
```

### 7.3 MyBatis-Plus 多方言配置

```java
@Configuration
public class MyBatisPlusConfig {
    
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 动态表名插件 (支持不同数据库的表名/字段差异)
        DynamicTableNameInnerInterceptor dynamicInterceptor = new DynamicTableNameInnerInterceptor();
        dynamicInterceptor.setTableNameHandler((sql, tableName) -> {
            // 根据当前数据库类型返回对应的表名
            return tableName;
        });
        interceptor.addInnerInterceptor(dynamicInterceptor);
        // 分页插件
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
```

---

## 8. 数据迁移方案

### 8.1 数据库版本管理 (Flyway)

```yaml
# application.yml Flyway 配置
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
    baseline-version: 0
    table: flyway_schema_history
```

### 8.2 迁移脚本命名规范

```
db/migration/
├── V1__init_schema.sql                  # 初始化建表
├── V1.1__seed_roles.sql                 # 种子数据
├── V2__add_agent_execution_index.sql    # 新增索引
├── V3__add_sandbox_config_table.sql     # 新增表
└── V3.1__add_sandbox_config_columns.sql # 修改字段
```

### 8.3 数据迁移流程

```bash
# 1. 创建迁移脚本
# 2. 启动应用 (Flyway 自动执行迁移)
mvn flyway:migrate

# 3. 验证迁移结果
mvn flyway:info

# 4. 回滚 (Flyway 不支持自动回滚，需手动编写 undo 脚本)
mvn flyway:undo
```

### 8.4 OceanBase → 达梦 迁移策略

| 步骤 | 操作 | 工具 |
|------|------|------|
| 1 | 导出 OceanBase 数据 | obdumper / mysqldump |
| 2 | 转换 DDL (TINYINT→SMALLINT, 移除 ENGINE) | 脚本转换 |
| 3 | 导入达梦数据库 | dmfldr / disql |
| 4 | 验证数据完整性 | 行数对比 + 抽样校验 |
| 5 | 切换数据源配置 | 修改 application.yml |

---

## 附录A：修订历史

| 版本 | 日期 | 修订内容 | 修订人 |
|------|------|---------|--------|
| V1.0 | 2026-07-08 | 初稿：20张核心表 + DDL + 索引 + 多库支持 | - |

---

**文档结束**
