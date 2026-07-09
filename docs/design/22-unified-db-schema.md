# 格物平台 统一数据库设计文档 V1.0

## 1. 文档信息

| 项目 | 内容 |
|------|------|
| 文档名称 | 统一数据库设计文档 — OpenCode × 格物融合方案 |
| 版本 | V1.2 |
| 创建日期 | 2026-07-08 |
| 文档状态 | 初稿 |
| 更新说明 | V1.1: 新增 6 张表（workflow_notification/workflow_permission/workflow_permission_matrix/workflow_audit_log/sandbox_audit_log）；总表数 26→32；更新 ER 图 V1.2: 新增 §2.5 数据库版本兼容性（含达梦/金仓特殊适配） |
| 主数据库 | OceanBase 4.2+ (MySQL 兼容模式) |
| 备选数据库 | MySQL 8.0 / 达梦 8.0 / 人大金仓 8.0 |
| 融合来源 | 格物平台 22 张核心表 + OpenCode 11 张表中的 4 张新增表 |
| 总表数 | 22 (格物) + 4 (OpenCode 迁移) + 6 (沙箱审计+工作流通知/权限/审计) = **32 张表** |

---

## 2. 设计原则

### 2.1 核心设计约束

| 原则 | 说明 |
|------|------|
| **主键策略** | 所有表使用 `VARCHAR(26) ULID` 作为主键，支持分布式场景。OpenCode 原有 TEXT PK 全部迁移为 ULID |
| **时间字段** | 统一使用 `BIGINT` 存储毫秒级时间戳。OpenCode 原有 INTEGER 时间戳保持兼容 |
| **逻辑删除** | 所有业务表支持逻辑删除，`deleted` 字段 (0=正常, 1=删除) |
| **乐观锁** | 关键表添加 `version` 字段用于乐观锁并发控制 |
| **审计字段** | 所有表包含 `created_at`, `updated_at`, `created_by`, `updated_by` |
| **命名规范** | 表名使用小写蛇形命名，字段名使用小写蛇形命名 |
| **字符集** | UTF-8 (utf8mb4) |
| **默认存储引擎** | OceanBase MySQL 兼容模式 / InnoDB |
| **JSON 字段** | 使用 JSON 类型替代 OpenCode 的 TEXT (JSON) 存储，利用数据库 JSON 函数 |
| **浮点金额** | OpenCode 的 REAL cost 迁移为 `DECIMAL(10,4)`，避免精度丢失 |

### 2.2 ULID 主键格式

```
01ARZ3NDEKTSV4RRFFQ69G5FAV
├─┬┴─┬──┴──────────┬───────┘
│  │              └─ 随机部分 (16字符, 80bit)
│  └───────────────── 时间戳部分 (10字符, 48bit, 毫秒级)
└──────────────────── 版本标识 (固定为 01)
```

### 2.3 OpenCode → 格物 类型映射

| OpenCode 类型 | 格物目标类型 | 说明 |
|---------------|-------------|------|
| TEXT PRIMARY KEY | VARCHAR(26) PRIMARY KEY | TEXT PK → ULID 主键 |
| TEXT (JSON) | JSON | SQLite TEXT 模拟 JSON 转为原生 JSON |
| REAL | DECIMAL(10,4) | 浮点成本转为定点数 |
| INTEGER (时间戳) | BIGINT | 保持兼容，统一为毫秒级 |
| TEXT | VARCHAR / TEXT | 根据长度选择 |

### 2.4 数据库命名规范

| 类别 | 规范 | 示例 |
|------|------|------|
| 数据库名 | `gewu_` + 环境 | `gewu_dev`, `gewu_prod` |
| 索引名 | `idx_` + 表名 + 字段 | `idx_session_project` |
| 唯一索引名 | `uk_` + 表名 + 字段 | `uk_user_email` |
| 外键名 | `fk_` + 表名 + 关联表 | `fk_session_project` |

### 2.5 数据库版本兼容性

#### 支持的数据库及最低版本

| 数据库 | 最低版本 | 推荐版本 | 兼容模式 | 状态 |
|--------|---------|---------|---------|------|
| **OceanBase** | 4.2.0 | 4.2.5+ | MySQL 兼容模式 | ✅ 主选 |
| **MySQL** | 8.0.28 | 8.0.35+ | 原生 | ✅ 备选 |
| **达梦 DM8** | 8.1.3 | 8.1.3+ | Oracle 兼容模式 | ✅ 备选 |
| **人大金仓** | V8.6.2 | V8R6.2+ | MySQL 兼容模式 | ✅ 备选 |

#### 必需数据库特性

| 特性 | 用途 | OceanBase | MySQL | 达梦 DM8 | 人大金仓 |
|------|------|-----------|-------|---------|---------|
| **JSON 类型** | 存储扩展字段 (event, metadata 等) | ✅ 原生 | ✅ 原生 | ✅ 支持 | ✅ 支持 |
| **唯一索引** | ULID 主键 + 复合唯一约束 | ✅ | ✅ | ✅ | ✅ |
| **事务 ACID** | 跨表事务 (Event Sourcing) | ✅ 分布式 | ✅ | ✅ | ✅ |
| **BIGINT 毫秒** | 时间戳字段 | ✅ | ✅ | ✅ | ✅ |
| **VARCHAR(26)** | ULID 主键 | ✅ | ✅ | ✅ | ✅ |
| **DECIMAL(10,4)** | 金额精度 | ✅ | ✅ | ✅ | ✅ |
| **逻辑删除** | `deleted` 字段软删除 | ✅ | ✅ | ✅ | ✅ |
| **乐观锁** | `version` 字段并发控制 | ✅ | ✅ | ✅ | ✅ |

#### 达梦 DM8 特殊适配

| 差异项 | OceanBase/MySQL | 达梦 DM8 | 适配方案 |
|--------|----------------|---------|---------|
| 大小写敏感 | 默认不敏感 | 默认敏感 | 连接串设置 `SET CASE_SENSITIVE=0` |
| BLOB | 原生 | DM_BLOB | MyBatis-Plus 类型处理器 |
| JSON | 原生 JSON | CLOB / TEXT | 应用层 JSON 序列化 |
| AUTO_INCREMENT | 自增列 | 序列 (SEQUENCE) | ULID 主键替代 |
| 注释语法 | `COMMENT` | `COMMENT ON` | DDL 分开执行 |

#### 人大金仓 KingbaseES 特殊适配

| 差异项 | OceanBase/MySQL | 人大金仓 | 适配方案 |
|--------|----------------|---------|---------|
| 大小写敏感 | 默认不敏感 | 默认敏感 | 连接串设置 `SET case_sensitive=off` |
| JSON | 原生 JSON | JSON / JSONB | 使用 JSON 类型 |
| 序列 | 自增列 | SERIAL / SEQUENCE | ULID 主键替代 |
| 布尔类型 | TINYINT(1) | BOOLEAN | MyBatis-Plus Boolean 映射 |
| 表名 | 业务领域 + `_` + 实体 | `user_account`, `project_member` |
| 字段名 | 小写蛇形 | `user_name`, `created_at` |
| 主键 | `id` | `id` |
| 外键 | 关联表 + `_` + 关联字段 | `user_id`, `project_id` |
| 索引 | `idx_` + 表名 + `_` + 字段 | `idx_user_account_email` |
| 唯一索引 | `uk_` + 表名 + `_` + 字段 | `uk_user_account_username` |

---

## 3. 统一 ER 图

```mermaid
erDiagram
    %% ===== 用户与权限域 (5 tables) =====
    user_account ||--o{ user_role : has
    role ||--o{ user_role : contains
    role ||--o{ role_permission : grants
    permission ||--o{ role_permission : assigned_to

    %% ===== 项目管理域 (2 + 1 tables) =====
    user_account ||--o{ project : owns
    project ||--o{ project_member : has
    project ||--o{ project_directory : contains  "%% OpenCode 新增"

    %% ===== 会话消息域 (3 + 3 tables) =====
    session ||--o{ session_member : has
    session ||--o{ session_message : contains
    session ||--o{ session_input : receives  "%% OpenCode 新增"
    session ||--o{ session_context_epoch : snapshots  "%% OpenCode 新增"
    session_message ||--o{ part : composed_of  "%% OpenCode 新增"
    session ||--o{ session : parent  "%% 会话树(自引用)"

    %% ===== Agent 系统域 (4 tables) =====
    agent ||--o{ agent_tool : configures
    agent ||--o{ agent_permission : defines
    agent ||--o{ agent_execution : logs

    %% ===== 工作流引擎域 (9 tables: 5 核心 + 4 通知/权限/审计) =====
    workflow ||--o{ workflow_node : defines
    workflow ||--o{ workflow_transition : routes
    workflow_node ||--o{ workflow_transition : source
    workflow_node ||--o{ workflow_transition : target
    workflow ||--o{ workflow_instance : instantiated
    workflow_instance ||--o{ workflow_node_instance : executes
    workflow_node ||--o{ workflow_node_instance : templates

    %% ===== 审计与安全域 (2 tables) =====
    user_account ||--o{ audit_log : audits
    user_account ||--o{ api_key : owns

    %% ===== 工作流审计域 (新增表) =====
    workflow ||--o{ workflow_audit_log : audited
    workflow ||--o{ workflow_permission : controlled
    workflow ||--o{ workflow_permission_matrix : controlled
    workflow_instance ||--o{ workflow_notification : notifies

    %% ===== 沙箱配置域 (2 tables: config + audit_log) =====
    sandbox_config ||--o{ sandbox_audit_log : audited

    %% ===== 跨域关联 =====
    project ||--o{ session : hosts
    user_account ||--o{ session_member : participates
    user_account ||--o{ agent_execution : triggers
    project ||--o{ agent : belongs_to
    session ||--o{ agent_execution : runs_in
```

---

## 4. 表结构设计

### 4.1 用户与权限域 (User & Permission) — 5 张表

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
| status | TINYINT | NOT NULL DEFAULT 1 | 1=启用 2=禁用 3=锁定 |
| last_login_at | BIGINT | | 最后登录时间 |
| last_login_ip | VARCHAR(45) | | 最后登录IP |
| login_fail_count | INT | DEFAULT 0 | 登录失败次数 |
| locked_until | BIGINT | | 锁定截止时间 |
| deleted | TINYINT | DEFAULT 0 | 逻辑删除 |
| version | INT | DEFAULT 0 | 乐观锁 |
| created_at | BIGINT | NOT NULL | 创建时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |
| created_by | VARCHAR(26) | | 创建人ID |
| updated_by | VARCHAR(26) | | 更新人ID |

#### role — 角色表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| role_name | VARCHAR(64) | NOT NULL | 角色名称 |
| role_code | VARCHAR(64) | UNIQUE, NOT NULL | 角色编码 |
| description | VARCHAR(512) | | 角色描述 |
| is_system | TINYINT | DEFAULT 0 | 是否系统内置 |
| sort_order | INT | DEFAULT 0 | 排序顺序 |
| deleted | TINYINT | DEFAULT 0 | 逻辑删除 |
| created_at | BIGINT | NOT NULL | 创建时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |
| created_by | VARCHAR(26) | | 创建人ID |
| updated_by | VARCHAR(26) | | 更新人ID |

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
| source | VARCHAR(32) | DEFAULT 'system' | system/project |
| source_id | VARCHAR(26) | | 来源ID (如项目ID) |
| created_at | BIGINT | NOT NULL | 创建时间 |
| created_by | VARCHAR(26) | | 创建人ID |

#### role_permission — 角色权限关联表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| role_id | VARCHAR(26) | FK, NOT NULL | 角色ID |
| permission_id | VARCHAR(26) | FK, NOT NULL | 权限ID |
| created_at | BIGINT | NOT NULL | 创建时间 |
| created_by | VARCHAR(26) | | 创建人ID |

---

### 4.2 项目管理域 (Project) — 2 张表 + 1 张新增

#### project — 项目表（增强）

| 字段名 | 类型 | 约束 | 来源 | 说明 |
|--------|------|------|------|------|
| id | VARCHAR(26) | PK | Gewu | ULID 主键 |
| project_name | VARCHAR(128) | NOT NULL | Gewu | 项目名称 |
| description | TEXT | | Gewu | 项目描述 |
| visibility | TINYINT | DEFAULT 0 | Gewu | 0=私有 1=公开 |
| status | TINYINT | DEFAULT 1 | Gewu | 1=活跃 2=归档 3=关闭 |
| owner_id | VARCHAR(26) | FK, NOT NULL | Gewu | 项目所有者 |
| tech_stack | JSON | | Gewu | 技术栈配置 |
| **worktree** | **VARCHAR(1024)** | | **OpenCode** | **工作树路径 (代码项目定位)** |
| **vcs** | **VARCHAR(32)** | | **OpenCode** | **版本控制系统类型 (git/svn)** |
| **icon_url** | **VARCHAR(512)** | | **OpenCode** | **项目图标 URL** |
| **icon_color** | **VARCHAR(16)** | | **OpenCode** | **图标颜色** |
| **time_initialized** | **BIGINT** | | **OpenCode** | **初始化时间** |
| **sandboxes** | **JSON** | | **OpenCode** | **沙箱配置列表** |
| **commands** | **JSON** | | **OpenCode** | **自定义命令配置** |
| deleted | TINYINT | DEFAULT 0 | Gewu | 逻辑删除 |
| version | INT | DEFAULT 0 | Gewu | 乐观锁 |
| created_at | BIGINT | NOT NULL | Gewu | 创建时间 |
| updated_at | BIGINT | NOT NULL | Gewu | 更新时间 |
| created_by | VARCHAR(26) | NOT NULL | Gewu | 创建人 |
| updated_by | VARCHAR(26) | | Gewu | 更新人 |

> **OpenCode 增强字段以粗体标记**。新增 `worktree` 和 `vcs` 字段支持代码项目管理，`sandboxes` 和 `commands` 字段替代独立的沙箱/命令配置表。

**增强索引**:

| 索引名 | 字段 | 类型 | 说明 |
|--------|------|------|------|
| idx_project_worktree | worktree | 普通索引 | 工作树路径查询 |

#### project_member — 项目成员表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| project_id | VARCHAR(26) | FK, NOT NULL | 项目ID |
| user_id | VARCHAR(26) | FK, NOT NULL | 用户ID |
| role_code | VARCHAR(64) | NOT NULL | 项目内角色 |
| joined_at | BIGINT | NOT NULL | 加入时间 |
| deleted | TINYINT | DEFAULT 0 | 逻辑删除 |
| created_at | BIGINT | NOT NULL | 创建时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |

#### project_directory — 项目目录表（OpenCode 新增）

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| project_id | VARCHAR(26) | FK, NOT NULL | 项目ID |
| directory | VARCHAR(1024) | NOT NULL | 目录路径 |
| type | VARCHAR(32) | NOT NULL | 目录类型 (source/build/config/test) |
| strategy | VARCHAR(32) | | 监控策略 (watch/ignore/include) |
| time_created | BIGINT | NOT NULL | 创建时间 |

**主键**: (project_id, directory)

> 此表用于管理项目下的目录结构，支持代码项目的目录变更监控和文件系统观察策略。

---

### 4.3 会话消息域 (Session & Message) — 3 张表 + 3 张新增

#### session — 会话表（增强）

| 字段名 | 类型 | 约束 | 来源 | 说明 |
|--------|------|------|------|------|
| id | VARCHAR(26) | PK | Gewu | ULID 主键 |
| title | VARCHAR(256) | | Gewu | 会话标题 |
| type | TINYINT | NOT NULL DEFAULT 1 | Gewu | 1=群聊 2=私聊 3=AI辅助 |
| project_id | VARCHAR(26) | FK | Gewu | 关联项目ID |
| status | TINYINT | DEFAULT 1 | Gewu | 1=活跃 2=归档 3=关闭 |
| is_public | TINYINT | DEFAULT 0 | Gewu | 是否公开 |
| last_message_at | BIGINT | | Gewu | 最后消息时间 |
| message_count | INT | DEFAULT 0 | Gewu | 消息总数 |
| **parent_id** | **VARCHAR(26)** | | **OpenCode** | **父会话ID (会话树/分叉)** |
| **agent** | **VARCHAR(128)** | | **OpenCode** | **绑定的 Agent 名称** |
| **model** | **JSON** | | **OpenCode** | **模型配置 (provider/model/params)** |
| **share_url** | **VARCHAR(512)** | | **OpenCode** | **分享链接** |
| **slug** | **VARCHAR(128)** | | **OpenCode** | **会话唯一标识** |
| **directory** | **VARCHAR(1024)** | | **OpenCode** | **关联工作目录** |
| **path** | **VARCHAR(1024)** | | **OpenCode** | **会话存储路径** |
| **workspace_id** | **VARCHAR(26)** | | **OpenCode** | **工作区ID** |
| **metadata** | **JSON** | | **OpenCode** | **扩展元数据** |
| **cost** | **DECIMAL(10,4)** | **DEFAULT 0** | **OpenCode** | **累计调用成本** |
| **tokens_input** | **INT** | **DEFAULT 0** | **OpenCode** | **输入 Token 总数** |
| **tokens_output** | **INT** | **DEFAULT 0** | **OpenCode** | **输出 Token 总数** |
| **tokens_reasoning** | **INT** | **DEFAULT 0** | **OpenCode** | **推理 Token 总数** |
| **tokens_cache_read** | **INT** | **DEFAULT 0** | **OpenCode** | **缓存读取 Token 数** |
| **tokens_cache_write** | **INT** | **DEFAULT 0** | **OpenCode** | **缓存写入 Token 数** |
| **summary_additions** | **INT** | | **OpenCode** | **代码添加行数** |
| **summary_deletions** | **INT** | | **OpenCode** | **代码删除行数** |
| **summary_files** | **INT** | | **OpenCode** | **变更文件数** |
| **summary_diffs** | **JSON** | | **OpenCode** | **差异摘要** |
| **revert** | **JSON** | | **OpenCode** | **回滚配置** |
| **version** | **VARCHAR(32)** | | **OpenCode** | **会话版本标识 (注意: OpenCode 的 version 字段, 与乐观锁不同)** |
| **time_compacting** | **BIGINT** | | **OpenCode** | **上下文压缩时间** |
| **time_archived** | **BIGINT** | | **OpenCode** | **归档时间** |
| deleted | TINYINT | DEFAULT 0 | Gewu | 逻辑删除 |
| version_opt | INT | DEFAULT 0 | Gewu | 乐观锁 (区别于 OpenCode version) |
| created_at | BIGINT | NOT NULL | Gewu | 创建时间 |
| updated_at | BIGINT | NOT NULL | Gewu | 更新时间 |
| created_by | VARCHAR(26) | NOT NULL | Gewu | 创建人ID |
| updated_by | VARCHAR(26) | | Gewu | 更新人ID |

> OpenCode 增强字段以粗体标记。`parent_id` 支持会话分叉/回退树形结构；`agent` 和 `model` 支撑 AI 会话绑定；`tokens_*` 和 `cost` 提供计费与用量追踪。

**增强索引**:

| 索引名 | 字段 | 类型 | 说明 |
|--------|------|------|------|
| idx_session_agent | agent | 普通索引 | Agent 查询 |
| idx_session_parent | parent_id | 普通索引 | 会话树查询 |
| idx_session_slug | slug | UNIQUE | 标识唯一性 |

#### session_member — 会话成员表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| session_id | VARCHAR(26) | FK, NOT NULL | 会话ID |
| user_id | VARCHAR(26) | FK, NOT NULL | 用户ID |
| role | TINYINT | DEFAULT 0 | 0=成员 1=管理员 |
| last_read_at | BIGINT | | 最后阅读时间 |
| is_muted | TINYINT | DEFAULT 0 | 是否静音 |
| joined_at | BIGINT | NOT NULL | 加入时间 |
| deleted | TINYINT | DEFAULT 0 | 逻辑删除 |
| created_at | BIGINT | NOT NULL | 创建时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |

#### session_message — 会话消息表（增强）

| 字段名 | 类型 | 约束 | 来源 | 说明 |
|--------|------|------|------|------|
| id | VARCHAR(26) | PK | Gewu | ULID 主键 |
| session_id | VARCHAR(26) | FK, NOT NULL | Gewu | 会话ID |
| sender_id | VARCHAR(26) | FK, NOT NULL | Gewu | 发送者ID |
| message_type | VARCHAR(32) | NOT NULL DEFAULT 'text' | Gewu | text/code/image/file/system/ai |
| content | TEXT | NOT NULL | Gewu | 消息内容 (Markdown) |
| metadata | JSON | | Gewu | 元数据 |
| reply_to | VARCHAR(26) | | Gewu | 回复目标消息ID |
| mention_user_ids | JSON | | Gewu | @提及用户ID列表 |
| client_id | VARCHAR(64) | | Gewu | 客户端幂等ID |
| **seq** | **INT** | | **OpenCode** | **消息序列号** |
| edited | TINYINT | DEFAULT 0 | Gewu | 是否已编辑 |
| deleted | TINYINT | DEFAULT 0 | Gewu | 逻辑删除 |
| created_at | BIGINT | NOT NULL | Gewu | 发送时间 |
| updated_at | BIGINT | NOT NULL | Gewu | 更新时间 |

> 新增 `seq` 字段从 OpenCode session_message 移植，支持消息顺序严格保证。

**增强索引**:

| 索引名 | 字段 | 类型 | 说明 |
|--------|------|------|------|
| uk_session_message_seq | session_id, seq | UNIQUE | 消息序列唯一性 |

#### session_input — 会话输入表（OpenCode 新增）

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| session_id | VARCHAR(26) | FK, NOT NULL | 会话ID |
| prompt | JSON | NOT NULL | 提示词数据 (messages + tools) |
| delivery | VARCHAR(32) | NOT NULL | 交付方式 (steer/queue/resume) |
| admitted_seq | INT | NOT NULL | 接收序列号 |
| promoted_seq | INT | | 提升序列号 (null=待处理) |
| time_created | BIGINT | NOT NULL | 创建时间 |

> 此表管理 AI 会话的用户输入排队与交付，支持 steering、queuing 和 resume 三种模式。`admitted_seq` 记录入队顺序，`promoted_seq` 记录提升为可见消息的顺序。

#### session_context_epoch — 会话上下文纪元表（OpenCode 新增）

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| session_id | VARCHAR(26) | PK, FK | 会话ID |
| baseline | TEXT | NOT NULL | 基线标识 |
| snapshot | JSON | NOT NULL | 上下文快照 (系统消息 + 历史摘要) |
| baseline_seq | INT | NOT NULL | 基线对应的消息序列号 |

> 每个 session 最多一条记录，用于保存上下文压缩后的基线快照，支持 AI 会话的上下文窗口管理。

#### part — 消息部分表（OpenCode 新增）

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| message_id | VARCHAR(26) | FK, NOT NULL | 所属消息ID |
| session_id | VARCHAR(26) | FK, NOT NULL | 会话ID (冗余，便于按会话清理) |
| part_type | VARCHAR(32) | NOT NULL | 部分类型: text/code/tool_use/tool_result/thinking/image |
| data | JSON | NOT NULL | 部分数据 (内容、语言、元数据等) |
| sort_order | INT | DEFAULT 0 | 排序顺序 |
| time_created | BIGINT | NOT NULL | 创建时间 |
| time_updated | BIGINT | | 更新时间 |

> OpenCode 的 Part 模型支持流式消息的多部分组装 (text block、tool call、tool result、thinking block 等)，`part_type` 字段区分不同类型。

---

### 4.4 Agent 系统域 (Agent System) — 4 张表

#### agent — Agent 配置表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| agent_name | VARCHAR(128) | NOT NULL | Agent 名称 |
| description | VARCHAR(1024) | | Agent 描述 |
| model_provider | VARCHAR(64) | NOT NULL | 模型提供商 (alibaba/deepseek/openai) |
| model_name | VARCHAR(128) | NOT NULL | 模型名称 |
| model_config | JSON | | 模型参数 |
| system_prompt | TEXT | | 系统提示词 |
| status | TINYINT | DEFAULT 1 | 1=启用 2=禁用 |
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
| tool_type | VARCHAR(64) | NOT NULL | api/code/function/workflow |
| endpoint | VARCHAR(512) | | API 端点 |
| request_schema | JSON | | 请求 Schema |
| response_schema | JSON | | 响应 Schema |
| auth_config | JSON | | 认证配置 |
| timeout_ms | INT | DEFAULT 30000 | 超时时间 |
| status | TINYINT | DEFAULT 1 | 1=启用 2=禁用 |
| sort_order | INT | DEFAULT 0 | 排序 |
| deleted | TINYINT | DEFAULT 0 | 逻辑删除 |
| created_at | BIGINT | NOT NULL | 创建时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |

#### agent_permission — Agent 权限表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| agent_id | VARCHAR(26) | FK, NOT NULL | Agent ID |
| permission_code | VARCHAR(128) | NOT NULL | 权限编码 |
| effect | VARCHAR(16) | NOT NULL DEFAULT 'allow' | allow/deny |
| condition | JSON | | 条件表达式 |
| priority | INT | DEFAULT 0 | 优先级 |
| created_at | BIGINT | NOT NULL | 创建时间 |
| created_by | VARCHAR(26) | | 创建人 |

#### agent_execution — Agent 执行记录表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| agent_id | VARCHAR(26) | FK, NOT NULL | Agent ID |
| session_id | VARCHAR(26) | FK | 所属会话ID |
| user_id | VARCHAR(26) | FK, NOT NULL | 发起人ID |
| status | VARCHAR(32) | NOT NULL | pending/running/completed/failed/cancelled |
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

---

### 4.5 工作流引擎域 (Workflow) — 5 张表

#### workflow — 工作流模板表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| workflow_name | VARCHAR(128) | NOT NULL | 工作流名称 |
| description | TEXT | | 工作流描述 |
| version | INT | NOT NULL DEFAULT 1 | 版本号 |
| status | TINYINT | DEFAULT 0 | 0=草稿 1=已发布 2=已归档 |
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
| node_type | VARCHAR(32) | NOT NULL | start/end/task/approval/condition/subprocess |
| config | JSON | | 节点配置 |
| position_x | FLOAT | | 画布 X 坐标 |
| position_y | FLOAT | | 画布 Y 坐标 |
| sort_order | INT | DEFAULT 0 | 排序 |
| created_at | BIGINT | NOT NULL | 创建时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |

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

#### workflow_instance — 工作流实例表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| workflow_id | VARCHAR(26) | FK, NOT NULL | 工作流模板ID |
| workflow_version | INT | NOT NULL | 使用的版本号 |
| title | VARCHAR(256) | | 实例标题 |
| status | VARCHAR(32) | NOT NULL | running/completed/failed/suspended/terminated |
| initiator_id | VARCHAR(26) | FK, NOT NULL | 发起人ID |
| current_node_id | VARCHAR(26) | FK | 当前节点ID |
| variables | JSON | | 流程变量 |
| started_at | BIGINT | NOT NULL | 开始时间 |
| completed_at | BIGINT | | 完成时间 |
| deleted | TINYINT | DEFAULT 0 | 逻辑删除 |
| created_at | BIGINT | NOT NULL | 创建时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |

#### workflow_node_instance — 工作流节点实例表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| instance_id | VARCHAR(26) | FK, NOT NULL | 工作流实例ID |
| node_id | VARCHAR(26) | FK, NOT NULL | 节点模板ID |
| node_name | VARCHAR(128) | NOT NULL | 节点名称 |
| node_type | VARCHAR(32) | NOT NULL | 节点类型 |
| status | VARCHAR(32) | NOT NULL | pending/running/completed/failed/skipped |
| assignee_id | VARCHAR(26) | | 处理人ID |
| input | JSON | | 节点输入 |
| output | JSON | | 节点输出 |
| started_at | BIGINT | | 开始时间 |
| completed_at | BIGINT | | 完成时间 |
| remark | TEXT | | 处理备注 |
| created_at | BIGINT | NOT NULL | 创建时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |

---

#### workflow_notification — 工作流通知表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| instance_id | VARCHAR(26) | FK, NOT NULL | 工作流实例ID |
| node_instance_id | VARCHAR(26) | FK | 节点实例ID |
| type | VARCHAR(50) | NOT NULL | TASK_CREATED/TASK_ASSIGNED/REVIEW_REQUIRED/TIMEOUT_WARNING |
| recipient_id | VARCHAR(26) | NOT NULL | 接收人ID |
| title | VARCHAR(200) | NOT NULL | 通知标题 |
| content | TEXT | | 通知内容 |
| is_read | TINYINT | DEFAULT 0 | 是否已读 |
| sent_at | BIGINT | NOT NULL | 发送时间 |
| created_at | BIGINT | NOT NULL |

**索引**: KEY `idx_notification_recipient_read` (`recipient_id`, `is_read`), KEY `idx_notification_type_sent` (`type`, `sent_at`)

#### workflow_permission — 工作流权限表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | BIGINT | PK AUTO_INCREMENT | 主键 |
| workflow_id | BIGINT | NOT NULL | 工作流ID |
| role_code | VARCHAR(50) | NOT NULL | 角色编码 |
| permission_type | VARCHAR(20) | NOT NULL | START/EXECUTE/REVIEW/MANAGE |
| created_at | TIMESTAMP | DEFAULT NOW | 创建时间 |
| updated_at | TIMESTAMP | DEFAULT NOW | 更新时间 |

**唯一约束**: UNIQUE KEY `uk_workflow_role_permission` (`workflow_id`, `role_code`, `permission_type`)

#### workflow_permission_matrix — 工作流权限矩阵表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | BIGINT | PK AUTO_INCREMENT | 主键 |
| workflow_id | BIGINT | NOT NULL | 工作流ID |
| node_type | VARCHAR(50) | NOT NULL | REQUIREMENT/DEVELOPMENT/TESTING/DEPLOYMENT |
| required_role | VARCHAR(50) | NOT NULL | PROJECT_MANAGER/DEVELOPER/TESTER/ARCHITECT |
| permission_level | VARCHAR(20) | NOT NULL | EXECUTE/REVIEW/APPROVE |
| created_at | TIMESTAMP | DEFAULT NOW | 创建时间 |

**唯一约束**: UNIQUE KEY `uk_workflow_node_role` (`workflow_id`, `node_type`, `required_role`)

#### workflow_audit_log — 工作流审计日志表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | BIGINT | PK AUTO_INCREMENT | 主键 |
| workflow_id | BIGINT | NOT NULL | 工作流ID |
| instance_id | BIGINT | | 工作流实例ID |
| node_id | VARCHAR(50) | | 节点ID |
| operation | VARCHAR(50) | NOT NULL | START/COMPLETE/REVIEW/REJECT/CANCEL/TIMEOUT |
| operator_id | BIGINT | NOT NULL | 操作人 |
| operator_name | VARCHAR(50) | | 操作人名称 |
| before_state | VARCHAR(20) | | 操作前状态 |
| after_state | VARCHAR(20) | | 操作后状态 |
| ip_address | VARCHAR(45) | | 客户端IP |
| request_body | TEXT | | 请求内容 |
| response_code | INT | | 响应码 |
| response_time | BIGINT | | 响应时间 |
| created_at | TIMESTAMP | DEFAULT NOW | 审计时间 |

**索引**: KEY `idx_audit_workflow_id` (`workflow_id`), KEY `idx_audit_instance_id` (`instance_id`), KEY `idx_audit_operator_id` (`operator_id`)

---

### 4.6 审计与安全域 (Audit & Security) — 2 张表

#### audit_log — 审计日志表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| user_id | VARCHAR(26) | FK | 操作用户ID |
| username | VARCHAR(64) | | 用户名 (冗余) |
| action_type | VARCHAR(64) | NOT NULL | LOGIN/CREATE/UPDATE/DELETE/EXECUTE |
| resource_type | VARCHAR(64) | NOT NULL | USER/PROJECT/SESSION/AGENT/WORKFLOW |
| resource_id | VARCHAR(26) | | 资源ID |
| detail | JSON | | 操作详情 |
| ip_address | VARCHAR(45) | | 客户端IP |
| user_agent | VARCHAR(512) | | 客户端UA |
| result | TINYINT | NOT NULL | 1=成功 0=失败 |
| error_message | TEXT | | 失败原因 |
| duration_ms | INT | | 操作耗时 |
| created_at | BIGINT | NOT NULL | 操作时间 |

#### api_key — API 密钥表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| user_id | VARCHAR(26) | FK, NOT NULL | 所属用户ID |
| key_name | VARCHAR(64) | NOT NULL | 密钥名称 |
| key_hash | VARCHAR(256) | NOT NULL | 密钥哈希 |
| key_prefix | VARCHAR(8) | NOT NULL | 密钥前缀 |
| permissions | JSON | | 权限范围 |
| expires_at | BIGINT | | 过期时间 |
| last_used_at | BIGINT | | 最后使用时间 |
| status | TINYINT | DEFAULT 1 | 1=启用 2=禁用 |
| deleted | TINYINT | DEFAULT 0 | 逻辑删除 |
| created_at | BIGINT | NOT NULL | 创建时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |

---

### 4.7 沙箱配置域 (Sandbox) — 2 张表

#### sandbox_config — 沙箱配置表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| runtime | VARCHAR(32) | NOT NULL | docker/gvisor/firecracker |
| image | VARCHAR(256) | | 镜像地址 |
| cpu_limit | INT | DEFAULT 1 | CPU 核心数 |
| memory_limit_mb | INT | DEFAULT 512 | 内存限制 (MB) |
| disk_limit_mb | INT | DEFAULT 1024 | 磁盘限制 (MB) |
| network_enabled | TINYINT | DEFAULT 0 | 是否启用网络 |
| seccomp_profile | TEXT | | Seccomp 策略 |
| apparmor_profile | TEXT | | AppArmor 策略 |
| read_only_fs | TINYINT | DEFAULT 1 | 文件系统只读 |
| allowed_mounts | JSON | | 允许挂载路径 |
| env_vars | JSON | | 环境变量 |
| timeout_seconds | INT | DEFAULT 300 | 超时时间 |
| status | TINYINT | DEFAULT 1 | 1=启用 2=禁用 |
| created_at | BIGINT | NOT NULL | 创建时间 |
| updated_at | BIGINT | NOT NULL | 更新时间 |

---

#### sandbox_audit_log — 沙箱审计日志表

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(26) | PK | ULID 主键 |
| sandbox_id | VARCHAR(26) | FK, NOT NULL | 沙箱ID |
| user_id | VARCHAR(26) | FK, NOT NULL | 操作用户ID |
| action | VARCHAR(64) | NOT NULL | COMMAND_EXECUTED / FILE_ACCESS |
| resource | VARCHAR(64) | NOT NULL | SANDBOX |
| details | JSON | | 操作详情 (命令内容/文件路径等) |
| ip_address | VARCHAR(45) | | 客户端IP |
| result | VARCHAR(16) | NOT NULL | SUCCESS / FAIL (操作结果) |
| log_hash | VARCHAR(64) | | SM3 哈希链 (防篡改校验) |
| timestamp | BIGINT | NOT NULL | 操作时间 |

**索引**: KEY `idx_audit_sandbox` (`sandbox_id`), KEY `idx_audit_user` (`user_id`), KEY `idx_audit_time` (`timestamp` DESC)

---

## 5. DDL 脚本 — 新增表与增强表

### 5.1 新增表完整 DDL

以下 DDL 包含 4 张从 OpenCode 迁移的新表，全部使用 OceanBase/MySQL 兼容语法。

```sql
-- ========================================
-- 格物平台 — OpenCode 迁移新增表 DDL
-- 目标数据库: OceanBase 4.2+ (MySQL 模式) / MySQL 8.0
-- 字符集: utf8mb4
-- ========================================

-- ----------------------------------------
-- 1. part — AI 消息部分表
-- 用于存储消息的多模态部分（文本、代码块、工具调用、工具结果等）
-- 对应 OpenCode: Part
-- ----------------------------------------
CREATE TABLE IF NOT EXISTS part (
    id              VARCHAR(26)     NOT NULL COMMENT 'ULID 主键',
    message_id      VARCHAR(26)     NOT NULL COMMENT '所属消息ID (FK → session_message.id)',
    session_id      VARCHAR(26)     NOT NULL COMMENT '所属会话ID (冗余，便于按会话清理)',
    part_type       VARCHAR(32)     NOT NULL COMMENT '部分类型: text/code/tool_use/tool_result/thinking/image',
    data            JSON            NOT NULL COMMENT '部分数据 JSON (包含内容、语言、元数据等)',
    sort_order      INT             DEFAULT 0 COMMENT '部分顺序 (用于流式消息组装)',
    time_created    BIGINT          NOT NULL COMMENT '创建时间戳',
    time_updated    BIGINT                   COMMENT '更新时间戳',
    PRIMARY KEY (id),
    KEY idx_part_message (message_id, sort_order),
    KEY idx_part_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI消息部分表 — 多模态消息块 (text/code/tool_use/tool_result)';

-- ----------------------------------------
-- 2. session_input — 会话输入管理表
-- 管理 AI 会话的用户输入排队与交付
-- 对应 OpenCode: SessionInput
-- ----------------------------------------
CREATE TABLE IF NOT EXISTS session_input (
    id              VARCHAR(26)     NOT NULL COMMENT 'ULID 主键',
    session_id      VARCHAR(26)     NOT NULL COMMENT '会话ID (FK → session.id)',
    prompt          JSON            NOT NULL COMMENT '提示词数据 (messages + tools 结构)',
    delivery        VARCHAR(32)     NOT NULL COMMENT '交付方式: steer/queue/resume',
    admitted_seq    INT             NOT NULL COMMENT '接收序列号 (入队顺序)',
    promoted_seq    INT                      COMMENT '提升序列号 (转为可见消息的顺序, null=待处理)',
    time_created    BIGINT          NOT NULL COMMENT '创建时间戳',
    PRIMARY KEY (id),
    KEY idx_session_input_session (session_id, promoted_seq, delivery, admitted_seq),
    UNIQUE KEY uk_session_input_admitted (session_id, admitted_seq),
    UNIQUE KEY uk_session_input_promoted (session_id, promoted_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话输入管理表 — AI 输入的排队、交付与提升';

-- ----------------------------------------
-- 3. session_context_epoch — 会话上下文纪元表
-- 保存上下文压缩后的基线快照，每个 session 最多一条
-- 对应 OpenCode: SessionContextEpoch
-- ----------------------------------------
CREATE TABLE IF NOT EXISTS session_context_epoch (
    session_id      VARCHAR(26)     NOT NULL COMMENT '会话ID (PK, FK → session.id)',
    baseline        TEXT            NOT NULL COMMENT '基线标识 (标识上下文基线版本)',
    snapshot        JSON            NOT NULL COMMENT '上下文快照 (系统消息 + 历史摘要)',
    baseline_seq    INT             NOT NULL COMMENT '基线对应的消息序列号',
    PRIMARY KEY (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话上下文纪元表 — AI 上下文窗口压缩快照';

-- ----------------------------------------
-- 4. project_directory — 项目目录表
-- 管理项目下的目录结构、监控策略和文件系统观察规则
-- 对应 OpenCode: ProjectDirectory
-- ----------------------------------------
CREATE TABLE IF NOT EXISTS project_directory (
    project_id      VARCHAR(26)     NOT NULL COMMENT '项目ID (FK → project.id)',
    directory       VARCHAR(1024)   NOT NULL COMMENT '目录相对路径',
    type            VARCHAR(32)     NOT NULL COMMENT '目录类型: source/build/config/test/asset',
    strategy        VARCHAR(32)              COMMENT '监控策略: watch/ignore/include',
    time_created    BIGINT          NOT NULL COMMENT '创建时间戳',
    PRIMARY KEY (project_id, directory),
    KEY idx_project_directory_type (project_id, type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目目录表 — 目录结构与监控策略管理';
```

### 5.2 增强表变更 DDL

#### session 表 — ALTER TABLE 变更

```sql
-- 会话表增强：添加 OpenCode 字段（AI 会话支持）
ALTER TABLE session
    ADD COLUMN parent_id          VARCHAR(26)          COMMENT '父会话ID (会话树/分叉)' AFTER created_by,
    ADD COLUMN agent              VARCHAR(128)         COMMENT '绑定的 Agent 名称' AFTER parent_id,
    ADD COLUMN model              JSON                 COMMENT '模型配置 (provider/model/params)' AFTER agent,
    ADD COLUMN share_url          VARCHAR(512)         COMMENT '分享链接' AFTER model,
    ADD COLUMN slug               VARCHAR(128)         COMMENT '会话唯一标识' AFTER share_url,
    ADD COLUMN directory          VARCHAR(1024)        COMMENT '关联工作目录' AFTER slug,
    ADD COLUMN path               VARCHAR(1024)        COMMENT '会话存储路径' AFTER directory,
    ADD COLUMN workspace_id       VARCHAR(26)          COMMENT '工作区ID' AFTER path,
    ADD COLUMN metadata           JSON                 COMMENT '扩展元数据' AFTER workspace_id,
    ADD COLUMN cost               DECIMAL(10,4) DEFAULT 0 COMMENT '累计调用成本' AFTER metadata,
    ADD COLUMN tokens_input       INT DEFAULT 0        COMMENT '输入 Token 总数' AFTER cost,
    ADD COLUMN tokens_output      INT DEFAULT 0        COMMENT '输出 Token 总数' AFTER tokens_input,
    ADD COLUMN tokens_reasoning   INT DEFAULT 0        COMMENT '推理 Token 总数' AFTER tokens_output,
    ADD COLUMN tokens_cache_read  INT DEFAULT 0        COMMENT '缓存读取 Token 数' AFTER tokens_reasoning,
    ADD COLUMN tokens_cache_write INT DEFAULT 0        COMMENT '缓存写入 Token 数' AFTER tokens_cache_read,
    ADD COLUMN summary_additions  INT                  COMMENT '代码添加行数' AFTER tokens_cache_write,
    ADD COLUMN summary_deletions  INT                  COMMENT '代码删除行数' AFTER summary_additions,
    ADD COLUMN summary_files      INT                  COMMENT '变更文件数' AFTER summary_deletions,
    ADD COLUMN summary_diffs      JSON                 COMMENT '差异摘要 JSON' AFTER summary_files,
    ADD COLUMN revert             JSON                 COMMENT '回滚配置' AFTER summary_diffs,
    ADD COLUMN time_compacting    BIGINT               COMMENT '上下文压缩时间' AFTER revert,
    ADD COLUMN time_archived      BIGINT               COMMENT '归档时间' AFTER time_compacting;

-- 新增索引
CREATE INDEX idx_session_agent ON session (agent);
CREATE INDEX idx_session_parent ON session (parent_id);
CREATE UNIQUE INDEX idx_session_slug ON session (slug);
```

#### project 表 — ALTER TABLE 变更

```sql
-- 项目表增强：添加 OpenCode 字段（代码项目管理）
ALTER TABLE project
    ADD COLUMN worktree           VARCHAR(1024)        COMMENT '工作树路径 (代码项目定位)' AFTER tech_stack,
    ADD COLUMN vcs                VARCHAR(32)          COMMENT '版本控制系统类型 (git/svn)' AFTER worktree,
    ADD COLUMN icon_url           VARCHAR(512)         COMMENT '项目图标 URL' AFTER vcs,
    ADD COLUMN icon_color         VARCHAR(16)          COMMENT '图标颜色' AFTER icon_url,
    ADD COLUMN time_initialized   BIGINT               COMMENT '初始化时间' AFTER icon_color,
    ADD COLUMN sandboxes          JSON                 COMMENT '沙箱配置列表' AFTER time_initialized,
    ADD COLUMN commands           JSON                 COMMENT '自定义命令配置' AFTER sandboxes;

-- 新增索引
CREATE INDEX idx_project_worktree ON project (worktree);
```

#### session_message 表 — ALTER TABLE 变更

```sql
-- 消息表增强：添加消息序列号
ALTER TABLE session_message
    ADD COLUMN seq INT COMMENT '消息序列号' AFTER content;

-- 新增唯一索引
CREATE UNIQUE INDEX uk_session_message_seq ON session_message (session_id, seq);
```

---

## 6. 索引设计

### 6.1 新增表索引总览

| 表名 | 索引名 | 字段 | 类型 | 说明 |
|------|--------|------|------|------|
| part | PRIMARY | id | 聚簇 | ULID 主键 |
| part | idx_part_message | message_id, sort_order | 复合 | 按消息查询其所有部分 |
| part | idx_part_session | session_id | 普通 | 按会话清理 |
| session_input | PRIMARY | id | 聚簇 | ULID 主键 |
| session_input | idx_session_input_session | session_id, promoted_seq, delivery, admitted_seq | 复合 | 待处理输入排队查询 |
| session_input | uk_session_input_admitted | session_id, admitted_seq | 唯一 | 入队顺序唯一性 |
| session_input | uk_session_input_promoted | session_id, promoted_seq | 唯一 | 提升顺序唯一性 |
| session_context_epoch | PRIMARY | session_id | 聚簇 | 会话ID 主键 |
| project_directory | PRIMARY | project_id, directory | 联合主键 | 项目+目录唯一性 |
| project_directory | idx_project_directory_type | project_id, type | 复合 | 按类型查询目录 |

### 6.2 增强表新增索引总览

| 表名 | 索引名 | 字段 | 类型 | 说明 |
|------|--------|------|------|------|
| session | idx_session_agent | agent | 普通 | Agent 维度查询 |
| session | idx_session_parent | parent_id | 普通 | 会话树遍历 |
| session | idx_session_slug | slug | 唯一 | slug 唯一性 |
| project | idx_project_worktree | worktree | 普通 | 工作树路径查找 |
| session_message | uk_session_message_seq | session_id, seq | 唯一 | 消息序列号唯一性 |

### 6.3 索引设计原则

| 原则 | 说明 |
|------|------|
| 主键索引 | ULID 聚簇索引，时间顺序插入减少页分裂 |
| 复合索引最左前缀 | 多字段查询时，索引字段顺序匹配 WHERE 条件顺序 |
| 覆盖索引 | 高频查询场景设计覆盖索引避免回表 |
| 唯一索引 | 业务唯一键确保数据完整性 |
| 索引数量控制 | 单表索引不超过 8 个，避免写入性能下降 |

---

## 7. OpenCode 迁移适配说明

### 7.1 表迁移对照

| OpenCode 表名 | 迁移方式 | 目标说明 |
|---------------|----------|----------|
| session | 增强 | 合并字段到 gewu.session |
| message | 合并 | 对应 gewu.session_message，增强已覆盖 |
| part | **新增** | 新建 gewu.part |
| todo | 暂不迁移 | 待办功能由 gewu 自身工作流取代 |
| session_message | 合并 | 对应 gewu.session_message，增强已覆盖 |
| session_input | **新增** | 新建 gewu.session_input |
| session_context_epoch | **新增** | 新建 gewu.session_context_epoch |
| project | 增强 | 合并字段到 gewu.project |
| project_directory | **新增** | 新建 gewu.project_directory |
| event_sequence | 暂不迁移 | 事件溯源架构由 gewu 独立设计 |
| event | 暂不迁移 | 事件溯源架构由 gewu 独立设计 |

### 7.2 数据迁移注意事项

1. **主键转换** (TEXT → VARCHAR(26) ULID): OpenCode 使用 UUID/TEXT 主键，迁移时需生成新的 ULID，建立新旧 ID 映射表
2. **JSON 字段转换** (TEXT → JSON): OpenCode 的 TEXT(JSON) 字段需验证 JSON 合法性后转换为原生 JSON 类型
3. **REAL → DECIMAL(10,4)**: 成本字段进行精度转换，注意浮点误差
4. **字段重命名**: OpenCode `time_created`/`time_updated` → 格物 `created_at`/`updated_at`
5. **审计字段补充**: OpenCode 表缺少 created_by/updated_by，迁移时根据会话上下文补充

### 7.3 字段值映射

| OpenCode 字段 | 格物字段 | 转换规则 |
|---------------|----------|----------|
| id (TEXT) | id (VARCHAR(26)) | 生成新 ULID，建立 ID 映射 |
| project_id (TEXT) | project_id (VARCHAR(26)) | 通过 ID 映射转换 |
| session_id (TEXT) | session_id (VARCHAR(26)) | 通过 ID 映射转换 |
| message_id (TEXT) | message_id (VARCHAR(26)) | 通过 ID 映射转换 |
| time_created (INTEGER) | created_at (BIGINT) | 直接迁移，统一为毫秒级 |
| time_updated (INTEGER) | updated_at (BIGINT) | 直接迁移，统一为毫秒级 |
| data (TEXT JSON) | data (JSON) | 验证 JSON 后直接转换 |
| cost (REAL) | cost (DECIMAL(10,4)) | ROUND(cost, 4) |

---

## 8. 数据字典 — 状态枚举

### 8.1 新增表状态与类型枚举

| 字段 | 值 | 说明 |
|------|----|------|
| part.part_type | text | 纯文本消息块 |
| part.part_type | code | 代码块 (含语言标识) |
| part.part_type | tool_use | 工具调用请求 |
| part.part_type | tool_result | 工具调用结果 |
| part.part_type | thinking | AI 推理过程 |
| part.part_type | image | 图片消息块 |
| session_input.delivery | steer | 直接驱动 (立即提升) |
| session_input.delivery | queue | 排队等待 (会话空闲时提升) |
| session_input.delivery | resume | 恢复会话 |
| project_directory.type | source | 源代码目录 |
| project_directory.type | build | 构建输出目录 |
| project_directory.type | config | 配置文件目录 |
| project_directory.type | test | 测试目录 |
| project_directory.type | asset | 静态资源目录 |
| project_directory.strategy | watch | 监控目录变更 |
| project_directory.strategy | ignore | 忽略目录 |
| project_directory.strategy | include | 包含目录 |

### 8.2 全量状态枚举

| 字段 | 值 | 说明 |
|------|----|------|
| user_account.status | 1/2/3 | 启用/禁用/锁定 |
| session.type | 1/2/3 | 群聊/私聊/AI辅助 |
| session.status | 1/2/3 | 活跃/归档/关闭 |
| agent_execution.status | pending/running/completed/failed/cancelled | 执行状态 |
| workflow.status | 0/1/2 | 草稿/已发布/已归档 |
| workflow_instance.status | running/completed/failed/suspended/terminated | 实例状态 |
| workflow_node_instance.status | pending/running/completed/failed/skipped | 节点实例状态 |
| session_message.message_type | text/code/image/file/system/ai | 消息类型 |
| sandbox_config.runtime | docker/gvisor/firecracker | 沙箱运行时 |
| agent_tool.tool_type | api/code/function/workflow | 工具类型 |

---

## 9. 数据迁移方案

### 9.1 迁移流程

```bash
# 1. 创建 ID 映射表
CREATE TABLE id_migration_map (
    old_id  TEXT    NOT NULL,
    new_id  VARCHAR(26) NOT NULL,
    table_name  VARCHAR(64) NOT NULL,
    PRIMARY KEY (table_name, old_id)
);

# 2. 导出 OpenCode 数据 (SQLite)
sqlite3 opencode.db .dump > opencode_dump.sql

# 3. 执行迁移脚本 (Python/Bash)
#    - 生成 ULID 并写入映射表
#    - 转换 JSON 字段格式
#    - 补充审计字段 (created_by, updated_by)

# 4. 导入到 OceanBase
#    - 使用 LOAD DATA 或分批 INSERT
```

### 9.2 验证策略

| 验证项 | 方法 | 通过标准 |
|--------|------|----------|
| 行数一致性 | SELECT COUNT(*) 对比 | 源库=目标库 |
| 主键完整性 | 检查 FK 引用 | 无孤立外键 |
| JSON 字段 | JSON_VALID() 函数 | 全部通过 |
| 时间戳 | 抽样对比原始值 | 误差 < 1ms |

---

## 附录A：修订历史

| 版本 | 日期 | 修订内容 | 修订人 |
|------|------|---------|--------|
| V1.0 | 2026-07-08 | 初稿：22 张格物核心表 + 4 张 OpenCode 迁移新增表 + 3 张增强表 | - |

---

## 附录B：OpenCode 暂不迁移表说明

以下 OpenCode 表当前暂不纳入统一架构，保留在 OpenCode 原有 SQLite 数据库中：

| 表名 | 原因 | 后续计划 |
|------|------|----------|
| todo | 功能由 gewu 工作流/任务模块替代 | 视需求在 V2.0 迁移 |
| event / event_sequence | 事件溯源架构需独立设计 | V2.0 评估 |
| message | 由 session_message 统一覆盖 | 已合并 |

---

## 附录C：数据库对象统计

| 统计项 | 数量 |
|--------|------|
| 总表数 | **32** |
| 格物原有表 | 22 |
| OpenCode 迁移新增表 | 4 |
| 沙箱审计表（V1.1 新增） | 1 (sandbox_audit_log) |
| 工作流模块表（V1.1 新增） | 4 (workflow_notification, workflow_permission, workflow_permission_matrix, workflow_audit_log) |
| 数据迁移工具表（V1.2 新增） | 1 (id_migration_map) |
| OpenCode 增强表 | 3 (session, project, session_message) |
| 新增索引总数 | 10 (4 张 OpenCode 新增表) + 6 (6 张 V1.1 新增表) + 5 (3 张增强表) = **21** |
| 审计字段全覆盖 | 是 |

---

**文档结束**
