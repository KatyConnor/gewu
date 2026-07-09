# 格物平台 统一API接口规范 V1.0

## 1. 文档信息

| 项目 | 内容 |
|------|------|
| 文档名称 | 统一API接口规范 — OpenCode × 格物融合方案 |
| 版本 | V1.1 |
| 创建日期 | 2026-07-08 |
| 文档状态 | 初稿 |
| 更新说明 | V1.1: 重构 §11 工作流 API（15→22 端点，含节点操作/通知/监控）；增强 §12 沙箱 API（含三级沙箱/预热池/审计）；新增参考文档 27/28/29/30 |
| 融合来源 | OpenCode 7 类 API (Session/Message/Tool/MCP/Config/Project/Event) + 格物 5 类 API (User/Project/Session/Agent/Auth) |
| 总接口数 | **11 个模块，约 80+ 端点** |
| 协议 | HTTP/HTTPS |
| 数据格式 | JSON (UTF-8) |
| 基础路径 | `/api/v1` |

## 2. 修订历史

| 版本 | 日期 | 修订内容 | 修订人 |
|------|------|---------|--------|
| V1.0 | 2026-07-08 | 初稿：OpenCode × 格物 统一 API 接口规范 | - |

---

## 3. 术语表

| 术语 | 说明 |
|------|------|
| JWT | JSON Web Token，使用 SM3withSM2 国密算法签名 |
| SSE | Server-Sent Events，服务端推送实时事件 |
| ULID | Universally Unique Lexicographically Sortable Identifier，全局唯一有序 ID |
| Agent | AI 代理，可配置模型、工具、权限，执行智能任务 |
| Nacos | 阿里巴巴开源的动态服务发现、配置管理和服务管理平台 |
| 自研工具协议 | 格物平台自研的 LLM 工具调用协议，替代 OpenCode 的 MCP |
| Token Bucket | 令牌桶限流算法，支持突发流量 |

---

## 4. 通用规范

### 4.1 协议与编码

| 项 | 规范 |
|---|------|
| 协议 | HTTP/HTTPS |
| 请求体格式 | `application/json` |
| 响应体格式 | `application/json` |
| SSE 响应格式 | `text/event-stream` |
| 字符编码 | UTF-8 |
| 时间格式 | BSON Date |

### 4.2 基础路径

所有 API 以 `/api/v1` 为统一前缀：

```
/api/v1/{资源路径}
```

| 环境 | 网关地址 | 说明 |
|------|---------|------|
| 开发 | `http://dev-gateway.gewu.local:8080` | 内部开发环境 |
| 测试 | `https://test-gateway.gewu.cn` | 测试环境 |
| 生产 | `https://gateway.gewu.cn` | 生产环境 (HTTPS 强制) |

### 4.3 认证鉴权

平台统一采用 **JWT (SM3withSM2)** + **API Key** 双认证模式。

| 认证方式 | 适用场景 | 传递方式 |
|----------|---------|---------|
| **JWT Bearer Token** | 用户交互类 API (所有业务接口) | `Authorization: Bearer {token}` |
| **API Key** | 服务间调用 / 自动化脚本 | `X-API-Key: {api_key}` |

> **融合说明**：OpenCode 原使用 OAuth 2.0 认证，格物平台采用国密算法 JWT。统一方案保留 API Key 对接能力，OAuth 2.0 可在未来版本通过网关适配层支持，当前版本暂不实现。

#### 4.3.1 JWT 令牌结构

| 字段 | 类型 | 说明 |
|------|------|------|
| `sub` | string | 用户 ID (ULID) |
| `roles` | string[] | 角色编码列表 |
| `permissions` | string[] | 权限编码列表 |
| `exp` | number | 过期时间戳 (毫秒) |
| `iat` | number | 签发时间戳 (毫秒) |
| `jti` | string | 令牌唯一 ID (ULID) |

**签名算法**: SM3withSM2 (国密 SM2 椭圆曲线 + SM3 哈希)

#### 4.3.2 令牌生命周期

| 参数 | 值 | 说明 |
|------|----|------|
| Access Token 有效期 | 30 分钟 | 用于接口鉴权，配合 Refresh Token 续期 |
| Refresh Token 有效期 | 30 天 | 用于刷新令牌 |
| Token 最大签发数 | 5/用户 | 超出后最早令牌失效 |

### 4.4 通用请求头

| 请求头 | 必填 | 说明 |
|--------|------|------|
| `Authorization` | 是 (认证接口除外) | `Bearer {jwt_token}` 或 `Bearer {api_key}` |
| `Content-Type` | POST/PUT | `application/json` |
| `X-Request-Id` | 否 | 请求追踪 ID (ULID 格式)，建议客户端生成 |
| `Accept-Language` | 否 | 首选语言: `zh-CN` (默认), `en-US` |
| `Accept-Version` | 否 | API 版本声明，例如 `v1`, `v2` (见版本控制章节) |

### 4.5 通用响应格式

**成功响应**：

```json
{
  "code": 200,
  "message": "success",
  "data": {},
  "requestId": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
  "timestamp": 1762473600000
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| code | int | 业务状态码，200 表示成功 |
| message | string | 提示信息 |
| data | object/array | 业务数据载体 |
| requestId | string | 请求追踪 ID，用于日志排查 |
| timestamp | long | 响应时间戳 (毫秒) |

**创建成功 (201)**：

```json
{
  "code": 200,
  "message": "创建成功",
  "data": { "id": "01ARZ3NDEKTSV4RRFFQ69G5FAV" },
  "requestId": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
  "timestamp": 1762473600000
}
```

**异步提交成功 (202)**：

```json
{
  "code": 200,
  "message": "任务已提交",
  "data": {
    "executionId": "01ARZ3NDEKTSV4RRFFQ69G5E01",
    "status": "pending"
  },
  "requestId": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
  "timestamp": 1762473600000
}
```

> **融合说明**：OpenCode 使用 `{"error":{"code":"ERROR_CODE","message":"错误描述"}}` 格式。统一后采用格物 `{"code":...,"message":...,"data":...,"requestId":...}` 格式。OpenCode 的老格式在兼容期内仍可通过 `Accept-Version: v0` 获取。

### 4.6 错误响应格式

```json
{
  "code": 40001,
  "message": "参数验证失败",
  "details": [
    {
      "field": "email",
      "message": "邮箱格式不正确"
    }
  ],
  "requestId": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
  "timestamp": 1762473600000
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| code | int | 是 | 业务错误码 |
| message | string | 是 | 错误描述 |
| details | array | 否 | 参数级错误详情列表 |
| requestId | string | 是 | 请求追踪 ID |
| timestamp | long | 是 | 错误时间戳 |

### 4.7 错误码定义

| 错误码 | HTTP 状态码 | 说明 | 对应 OpenCode |
|--------|------------|------|---------------|
| **通用错误** | | | |
| 200 | 200 | 成功 | — |
| 40000 | 400 | 请求参数错误 | 400 |
| 40001 | 400 | 参数验证失败 | 400 |
| 40002 | 400 | 请求体格式错误 | 400 |
| 40100 | 401 | 未认证 | 401 |
| 40101 | 401 | 令牌已过期 | 401 |
| 40102 | 401 | 令牌无效 | 401 |
| 40300 | 403 | 无权限 | 403 |
| 40301 | 403 | 资源被锁定 | — |
| 40400 | 404 | 资源不存在 | 404 |
| 40500 | 405 | 请求方法不支持 | — |
| 40900 | 409 | 资源冲突 (已存在) | — |
| 42900 | 429 | 请求过于频繁 | — |
| **业务错误** | | | |
| 50000 | 500 | 服务器内部错误 | 500 |
| 50001 | 500 | 数据库操作失败 | — |
| 50002 | 500 | 第三方服务调用失败 | — |
| 50003 | 500 | 缓存操作失败 | — |
| 50004 | 500 | 消息队列操作失败 | — |
| 50300 | 503 | 服务不可用 | 503 |

### 4.8 通用分页

分页参数统一使用 `page` 和 `size`，page 从 1 开始计数。

**请求参数**：

| 参数 | 类型 | 必填 | 默认值 | 最大值 | 说明 |
|------|------|------|--------|--------|------|
| page | int | 否 | 1 | 10000 | 页码 (从 1 开始) |
| size | int | 否 | 20 | 200 | 每页条数 |
| sort | string | 否 | `created_at,desc` | — | 排序字段和方向 |

**响应格式**：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "content": [],
    "page": 1,
    "size": 20,
    "totalElements": 100,
    "totalPages": 5,
    "first": true,
    "last": false
  },
  "requestId": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
  "timestamp": 1762473600000
}
```

### 4.9 限流规则

采用格物平台的**自适应令牌桶**算法，支持突发流量和动态调节。

| 接口分类 | 默认限额 | 说明 |
|----------|---------|------|
| 读取接口 | 自适应 (基准 200/分钟) | GET 请求，根据系统负载动态调整 |
| 写入接口 | 自适应 (基准 50/分钟) | POST/PUT/DELETE 请求 |
| Agent 执行 | 自适应 (基准 30/分钟) | Agent 执行与工具调用 |
| 认证接口 | 10/分钟/IP | 登录、注册等认证接口 |
| 事件流 | 1/连接/用户 | SSE 长连接 (每用户仅限一个) |

> **限流响应**：当请求被限流时，返回 HTTP 429 状态码，错误码 `42900`。响应头包含 `Retry-After` 字段（秒），以及 `X-RateLimit-Remaining` 和 `X-RateLimit-Reset` 指示剩余额度与重置时间。

> **融合说明**：OpenCode 使用静态限流（Read 100/min, Write 20/min, Tool 10/min, MCP 5/min）。统一后采用格物自适应令牌桶方案，通过网关动态调节。OpenCode 的历史限流规则仅作为基准参考值。

### 4.10 版本控制

支持**URL 路径版本**和**Accept 请求头版本**双模式。

#### 4.10.1 URL 路径版本

```
/api/v1/users      # 当前版本 (v1)
/api/v2/users      # 下一主版本
```

| 版本 | 状态 | 说明 |
|------|------|------|
| v0 | 废弃过渡 | OpenCode 原格式兼容层，仅限迁移期使用 |
| v1 | 当前 | 本规范定义的标准版本 |

#### 4.10.2 Accept 请求头版本

客户端可通过 `Accept-Version` 请求头指定版本，覆盖 URL 路径中的版本号：

```
Accept-Version: v1
```

#### 4.10.3 版本兼容策略

| 变更类型 | 兼容性 | 版本策略 |
|----------|--------|----------|
| 新增字段 | 向后兼容 | 小版本更新 |
| 新增端点 | 向后兼容 | 小版本更新 |
| 新增可选参数 | 向后兼容 | 小版本更新 |
| 修改字段语义 | 不兼容 | 新主版本 (v2) |
| 删除字段 | 不兼容 | 新主版本 (v2) |
| 删除端点 | 不兼容 | 新主版本 (v2) |
| 修改认证方式 | 不兼容 | 新主版本 (v2) |

#### 4.10.4 废弃策略

| 阶段 | 说明 | 持续时间 |
|------|------|----------|
| 废弃公告 | 发布废弃通知，提供迁移指南 | 第 1 个月 |
| 双版本运行 | 新旧版本同时可用 | 第 2-6 个月 |
| 旧版本下线 | 废弃接口返回 410 Gone | 第 7 个月起 |

---

## 5. API 分组概览

| 序号 | 模块名称 | 基础路径 | 来源 | 优先级 |
|------|---------|---------|------|--------|
| 1 | 用户管理 API | `/api/v1/auth`, `/api/v1/users`, `/api/v1/roles` | 格物 | P0 |
| 2 | 项目管理 API | `/api/v1/projects` | 格物 + OpenCode | P0 |
| 3 | 会话管理 API | `/api/v1/sessions` | 格物 + OpenCode | P0 |
| 4 | 消息管理 API | `/api/v1/sessions/{id}/messages` | 格物 + OpenCode | P0 |
| 5 | Agent 系统 API | `/api/v1/agents` | 格物 + OpenCode (Tool) | P0 |
| 6 | 工作流 API | `/api/v1/workflows` | 格物 (Phase 2) | P1 |
| 7 | 沙箱 API | `/api/v1/sandbox` | 格物 (自研) | P1 |
| 8 | 监控 API | `/api/v1/monitor` | 格物 (Nightingale) | P1 |
| 9 | 配置管理 API | `/api/v1/config` | OpenCode → Nacos 适配 | P1 |
| 10 | 事件流 API | `/api/v1/events/stream` | 格物 + OpenCode | P0 |
| 11 | 审计日志 API | `/api/v1/audit-logs` | 格物 | P1 |

---

## 6. 用户管理 API

### 6.1 认证接口

#### POST /auth/register — 用户注册

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| username | string | 是 | 用户名 (4-32 字符，字母开头) |
| email | string | 是 | 邮箱 (唯一) |
| password | string | 是 | 密码 (8-64 字符，需含字母和数字) |
| displayName | string | 否 | 显示名称 (默认同 username) |

**响应** (201 Created)：

```json
{
  "code": 200,
  "message": "注册成功",
  "data": {
    "id": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
    "username": "zhangsan",
    "email": "zhangsan@example.com",
    "displayName": "张三",
    "status": 1,
    "createdAt": 1762473600000
  }
}
```

#### POST /auth/login — 用户登录

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| login | string | 是 | 用户名或邮箱 |
| password | string | 是 | 密码 |

**响应**：

```json
{
  "code": 200,
  "message": "登录成功",
  "data": {
    "accessToken": "eyJhbGciOiJTTTN3aXRoU00yIiwidHlwIjoiSldUIn0...",
    "refreshToken": "eyJhbGciOiJTTTN3aXRoU00yIiwidHlwIjoiSldUIn0...",
    "expiresIn": 1800000,
    "tokenType": "Bearer",
    "user": {
      "id": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
      "username": "zhangsan",
      "displayName": "张三",
      "roles": ["BACKEND_DEV", "ARCHITECT"]
    }
  }
}
```

#### POST /auth/refresh — 刷新令牌

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| refreshToken | string | 是 | 刷新令牌 |

**响应**：同登录响应，返回新的 accessToken 和 refreshToken。

#### POST /auth/logout — 退出登录

使当前 JWT 令牌立即失效 (加入黑名单)。

#### POST /auth/change-password — 修改密码

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| oldPassword | string | 是 | 旧密码 |
| newPassword | string | 是 | 新密码 (8-64 字符) |

### 6.2 用户管理接口

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/users/me` | 获取当前用户信息 | 用户登录 |
| PUT | `/users/me` | 更新当前用户信息 | 用户登录 |
| GET | `/users/{id}` | 获取指定用户信息 | 用户登录 |
| GET | `/users` | 用户列表 (管理用) | user:read |
| PUT | `/users/{id}/status` | 更新用户状态 (启用/禁用) | user:write |

#### GET /users — 用户列表查询参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 页码 |
| size | int | 否 | 每页条数 |
| status | int | 否 | 状态筛选 (1=正常, 2=禁用) |
| keyword | string | 否 | 搜索关键词 (匹配用户名/邮箱/显示名) |

#### UserResponse 数据模型

```json
{
  "id": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
  "username": "zhangsan",
  "email": "zhangsan@example.com",
  "phone": "138****1234",
  "displayName": "张三",
  "avatarUrl": "https://example.com/avatar.png",
  "status": 1,
  "roles": [
    { "id": "01ARZ3NDEKTSV4RRFFQ69G5F04", "roleName": "后端开发", "roleCode": "BACKEND_DEV" }
  ],
  "lastLoginAt": 1762473600000,
  "createdAt": 1762387200000
}
```

### 6.3 角色管理接口

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/roles` | 角色列表 | role:read |
| POST | `/roles` | 创建角色 | role:write |
| GET | `/roles/{id}` | 角色详情 | role:read |
| PUT | `/roles/{id}` | 更新角色 | role:write |
| DELETE | `/roles/{id}` | 删除角色 | role:delete |
| PUT | `/roles/{id}/permissions` | 分配角色权限 | role:write |
| PUT | `/users/{id}/roles` | 分配用户角色 | user:write |

#### POST /roles — 创建角色请求

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| roleName | string | 是 | 角色名称 |
| roleCode | string | 是 | 角色编码 (大写蛇形，唯一) |
| description | string | 否 | 角色描述 |
| permissionIds | string[] | 否 | 关联权限 ID 列表 |

#### RoleResponse 数据模型

```json
{
  "id": "01ARZ3NDEKTSV4RRFFQ69G5F04",
  "roleName": "后端开发",
  "roleCode": "BACKEND_DEV",
  "description": "后端开发工程师",
  "isSystem": true,
  "permissions": [
    {
      "id": "perm1",
      "permissionCode": "project:read",
      "permissionName": "项目读取",
      "resourceType": "PROJECT",
      "action": "READ"
    }
  ],
  "sortOrder": 4,
  "createdAt": 1762387200000
}
```

### 6.4 预定义权限列表

| 权限编码 | 权限名称 | 资源类型 | 操作 |
|----------|---------|---------|------|
| `user:read` | 用户读取 | USER | READ |
| `user:write` | 用户写入 | USER | CREATE/UPDATE |
| `user:delete` | 用户删除 | USER | DELETE |
| `role:read` | 角色读取 | ROLE | READ |
| `role:write` | 角色写入 | ROLE | CREATE/UPDATE |
| `role:delete` | 角色删除 | ROLE | DELETE |
| `project:read` | 项目读取 | PROJECT | READ |
| `project:write` | 项目写入 | PROJECT | CREATE/UPDATE |
| `project:delete` | 项目删除 | PROJECT | DELETE |
| `session:read` | 会话读取 | SESSION | READ |
| `session:write` | 会话写入 | SESSION | CREATE/UPDATE |
| `session:invite` | 会话邀请 | SESSION | INVITE |
| `agent:read` | Agent 读取 | AGENT | READ |
| `agent:write` | Agent 写入 | AGENT | CREATE/UPDATE |
| `agent:execute` | Agent 执行 | AGENT | EXECUTE |
| `workflow:read` | 工作流读取 | WORKFLOW | READ |
| `workflow:write` | 工作流写入 | WORKFLOW | CREATE/UPDATE |
| `workflow:execute` | 工作流执行 | WORKFLOW | EXECUTE |
| `audit:read` | 审计日志读取 | AUDIT | READ |
| `system:config` | 系统配置 | SYSTEM | CONFIG |
| `sandbox:execute` | 沙箱执行 | SANDBOX | EXECUTE |
| `monitor:read` | 监控读取 | MONITOR | READ |

---

## 7. 项目管理 API

### 7.1 项目 CRUD

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/projects` | 创建项目 | 用户登录 |
| GET | `/projects` | 项目列表 | 用户登录 |
| GET | `/projects/{id}` | 项目详情 | project:read |
| PUT | `/projects/{id}` | 更新项目 | project:write |
| DELETE | `/projects/{id}` | 删除项目 (归档) | project:delete |

#### POST /projects — 创建项目请求

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| projectName | string | 是 | 项目名称 (2-64 字符) |
| description | string | 否 | 项目描述 |
| visibility | int | 否 | 可见性 (0=私有, 1=团队, 2=公开, 默认 0) |
| techStack | object | 否 | 技术栈信息 |

**响应** (201 Created)：

```json
{
  "code": 200,
  "message": "项目创建成功",
  "data": {
    "id": "01ARZ3NDEKTSV4RRFFQ69G5F01",
    "projectName": "格物平台开发",
    "description": "AI 驱动的智能开发协作平台",
    "visibility": 0,
    "status": 1,
    "ownerId": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
    "memberCount": 1,
    "createdAt": 1762473600000
  }
}
```

#### GET /projects — 项目列表查询参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 页码 (默认 1) |
| size | int | 否 | 每页条数 (默认 20, 最大 100) |
| status | int | 否 | 状态筛选 (1=活跃 2=归档 3=关闭) |
| keyword | string | 否 | 搜索关键词 (匹配名称和描述) |

### 7.2 项目成员管理

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/projects/{id}/members` | 成员列表 | project:read |
| POST | `/projects/{id}/members` | 添加成员 | project:write |
| PUT | `/projects/{id}/members/{userId}` | 修改成员角色 | project:write |
| DELETE | `/projects/{id}/members/{userId}` | 移除成员 | project:write |

#### POST /projects/{id}/members — 添加成员请求

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| userId | string | 是 | 用户 ID |
| roleCode | string | 是 | 角色编码 |

#### ProjectMemberResponse 数据模型

```json
{
  "id": "01ARZ3NDEKTSV4RRFFQ69G5F01",
  "userId": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
  "displayName": "张三",
  "email": "zhangsan@example.com",
  "roleCode": "BACKEND_DEV",
  "joinedAt": 1762473600000
}
```

### 7.3 项目统计

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/projects/{id}/stats` | 项目统计信息 | project:read |

**响应**：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "totalSessions": 42,
    "totalMessages": 1560,
    "totalAgents": 5,
    "totalExecutions": 230,
    "activeMembers": 8,
    "storageUsed": 256000000
  }
}
```

### 7.4 项目配置

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/projects/{id}/config` | 获取项目配置 | project:read |
| PUT | `/projects/{id}/config` | 更新项目配置 | project:write |

### 7.5 数据模型

#### ProjectResponse

```json
{
  "id": "01ARZ3NDEKTSV4RRFFQ69G5F01",
  "projectName": "格物平台开发",
  "description": "AI 驱动的智能开发协作平台",
  "visibility": 0,
  "status": 1,
  "owner": {
    "id": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
    "displayName": "张三"
  },
  "memberCount": 5,
  "techStack": {
    "language": "Java 21",
    "framework": "Spring Boot 3.2",
    "database": "OceanBase 4.2"
  },
  "createdAt": 1762473600000,
  "updatedAt": 1762473600000
}
```

---

## 8. 会话管理 API

### 8.1 会话 CRUD

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/sessions` | 创建会话 | 用户登录 |
| GET | `/sessions` | 会话列表 | 用户登录 |
| GET | `/sessions/{id}` | 会话详情 | session:read |
| PUT | `/sessions/{id}` | 更新会话 | session:write |
| DELETE | `/sessions/{id}` | 删除会话 | session:write |
| POST | `/sessions/{id}/archive` | 归档会话 | session:write |

#### POST /sessions — 创建会话请求

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| title | string | 否 | 会话标题 (私聊可不填) |
| type | int | 是 | 1=群聊 2=私聊 3=AI辅助 |
| projectId | string | 否 | 关联项目 ID |
| agent | string | 否 | AI 代理名称 (type=3 时可用) |
| model | object | 否 | 模型配置 (type=3 时可用) |
| isPublic | boolean | 否 | 是否公开 (默认 false) |
| memberIds | string[] | 否 | 初始成员 ID 列表 |

**响应** (201 Created)：

```json
{
  "code": 200,
  "message": "会话创建成功",
  "data": {
    "id": "01ARZ3NDEKTSV4RRFFQ69G5F01",
    "title": "架构设计讨论",
    "type": 1,
    "projectId": "01ARZ3NDEKTSV4RRFFQ69G5F01",
    "status": 1,
    "memberCount": 3,
    "createdAt": 1762473600000
  }
}
```

#### GET /sessions — 会话列表查询参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 页码 |
| size | int | 否 | 每页条数 |
| type | int | 否 | 类型筛选 (1=群聊 2=私聊 3=AI辅助) |
| status | int | 否 | 状态筛选 (1=活跃 2=归档) |
| projectId | string | 否 | 项目筛选 |
| limit | int | 否 | (OpenCode 兼容) 返回数量限制 |
| offset | int | 否 | (OpenCode 兼容) 分页偏移量 |

#### SessionResponse 数据模型

```json
{
  "id": "01ARZ3NDEKTSV4RRFFQ69G5F01",
  "title": "架构设计讨论",
  "type": 1,
  "typeName": "群聊",
  "projectId": "01ARZ3NDEKTSV4RRFFQ69G5F01",
  "agent": "build",
  "model": { "id": "gpt-4", "providerID": "openai" },
  "status": 1,
  "isPublic": false,
  "memberCount": 5,
  "lastMessage": {
    "id": "01ARZ3NDEKTSV4RRFFQ69G5M01",
    "content": "最后一条消息",
    "senderName": "张三",
    "senderId": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
    "createdAt": 1762473600000
  },
  "unreadCount": 3,
  "createdAt": 1762473600000,
  "createdBy": "01ARZ3NDEKTSV4RRFFQ69G5FAV"
}
```

### 8.2 会话成员管理

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/sessions/{id}/members` | 成员列表 | session:read |
| POST | `/sessions/{id}/members` | 邀请成员 | session:invite |
| DELETE | `/sessions/{id}/members/{userId}` | 移除成员 | session:write |

#### POST /sessions/{id}/members — 邀请成员

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| userId | string | 是 | 用户 ID |

### 8.3 @提及与会话标记

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| PUT | `/sessions/{id}/read` | 标记会话已读 | session:read |
| GET | `/sessions/{id}/mentions` | 获取 @提及列表 | session:read |

#### PUT /sessions/{id}/read

标记当前用户在指定会话中的所有消息为已读。

---

## 9. 消息管理 API

### 9.1 消息发送与列表

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/sessions/{id}/messages` | 发送消息 | session:read |
| GET | `/sessions/{id}/messages` | 消息历史 | session:read |
| PUT | `/sessions/{id}/messages/{messageId}` | 编辑消息 | session:write |
| DELETE | `/sessions/{id}/messages/{messageId}` | 删除消息 | session:write |

#### POST /sessions/{id}/messages — 发送消息请求

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| content | string | 是 | 消息内容 (Markdown 格式) |
| messageType | string | 是 | text / code / image / file / system / ai |
| replyTo | string | 否 | 回复目标消息 ID |
| mentionUserIds | string[] | 否 | @提及用户 ID 列表 |
| clientId | string | 否 | 客户端消息 ID (幂等, 重发检测) |
| parts | array | 否 | (OpenCode 兼容) 消息部分 |

**响应** (201 Created)：

```json
{
  "code": 200,
  "message": "消息发送成功",
  "data": {
    "id": "01ARZ3NDEKTSV4RRFFQ69G5M01",
    "sessionId": "01ARZ3NDEKTSV4RRFFQ69G5F01",
    "sender": {
      "id": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
      "displayName": "张三"
    },
    "messageType": "text",
    "content": "这是一条测试消息",
    "replyTo": null,
    "mentionUserIds": [],
    "createdAt": 1762473600000
  }
}
```

#### GET /sessions/{id}/messages — 消息历史查询参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 页码 |
| size | int | 否 | 每页条数 (默认 50, 最大 200) |
| before | string | 否 | 游标: 获取比此 ID 更早的消息 |
| after | string | 否 | 游标: 获取比此 ID 更新的消息 |
| limit | int | 否 | (OpenCode 兼容) 返回数量限制 |

### 9.2 SSE 实时消息推送

会话中的新消息通过 SSE 实时推送。

**端点**: `GET /api/v1/events/stream`

**事件类型**: `session.message`

```text
event: session.message
id: 01ARZ3NDEKTSV4RRFFQ69G5FAV
data: {
  "type": "session.message",
  "sessionId": "01ARZ3NDEKTSV4RRFFQ69G5F01",
  "message": {
    "id": "01ARZ3NDEKTSV4RRFFQ69G5M01",
    "senderId": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
    "content": "新消息",
    "messageType": "text",
    "createdAt": 1762473600000
  }
}
```

### 9.3 消息已读状态

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/sessions/{id}/messages/{messageId}/read-status` | 消息已读状态 | session:read |

**响应**：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "messageId": "01ARZ3NDEKTSV4RRFFQ69G5M01",
    "totalReaders": 3,
    "readBy": [
      { "userId": "01ARZ3NDEKTSV4RRFFQ69G5FAV", "displayName": "张三", "readAt": 1762473600000 }
    ]
  }
}
```

### 9.4 数据模型

#### MessageResponse

```json
{
  "id": "01ARZ3NDEKTSV4RRFFQ69G5M01",
  "sessionId": "01ARZ3NDEKTSV4RRFFQ69G5F01",
  "sender": {
    "id": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
    "displayName": "张三",
    "avatarUrl": "https://example.com/avatar.png"
  },
  "messageType": "text",
  "content": "这是一条测试消息",
  "metadata": {},
  "replyTo": null,
  "mentionUserIds": [],
  "edited": false,
  "createdAt": 1762473600000
}
```

---

## 10. Agent 系统 API

> **融合说明**：该模块融合了 OpenCode 的 Tool API (工具执行与列表) 和 MCP API (模型上下文协议)。格物平台采用自研工具协议替代 MCP，原 MCP 连接/断开接口标记为废弃，由 Agent 工具注册逻辑完全替代。

### 10.1 Agent 配置

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/agents` | 创建 Agent | agent:write |
| GET | `/agents` | Agent 列表 | agent:read |
| GET | `/agents/{id}` | Agent 详情 | agent:read |
| PUT | `/agents/{id}` | 更新 Agent | agent:write |
| DELETE | `/agents/{id}` | 删除 Agent | agent:write |

#### POST /agents — 创建 Agent 请求

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| agentName | string | 是 | Agent 名称 |
| description | string | 否 | 描述 |
| modelProvider | string | 是 | 模型提供商 (alibaba / openai / tencent / baidu) |
| modelName | string | 是 | 模型名称 |
| modelConfig | object | 否 | 模型参数 (temperature, topP, maxTokens) |
| systemPrompt | string | 否 | 系统提示词 |
| status | int | 否 | 状态 (1=启用 2=禁用, 默认 1) |

**响应** (201 Created)：

```json
{
  "code": 200,
  "message": "Agent 创建成功",
  "data": {
    "id": "01ARZ3NDEKTSV4RRFFQ69G5A01",
    "agentName": "代码审查助手",
    "modelProvider": "alibaba",
    "modelName": "qwen-max",
    "status": 1,
    "createdAt": 1762473600000
  }
}
```

### 10.2 工具管理

> **融合说明**：OpenCode 的 `POST /tool/execute` (执行工具) 和 `GET /tool` (工具列表) 与格物的 Agent 工具注册逻辑合并。工具不再作为独立资源暴露，而是归属于 Agent。`POST /tool/execute` 的语义由 `POST /agents/{id}/execute` 替代。原 OpenCode 的 `GET /tool?session_id=...` 路由保留兼容别名。

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/agents/{id}/tools` | 注册工具 | agent:write |
| GET | `/agents/{id}/tools` | 工具列表 | agent:read |
| PUT | `/agents/{id}/tools/{toolId}` | 更新工具 | agent:write |
| DELETE | `/agents/{id}/tools/{toolId}` | 移除工具 | agent:write |
| GET | `/tool` | (OpenCode 兼容) 全局工具列表 | agent:read |

#### POST /agents/{id}/tools — 注册工具请求

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| toolName | string | 是 | 工具名称 (Agent 内唯一) |
| description | string | 是 | 工具描述 (LLM 理解用) |
| toolType | string | 是 | api / function / sandbox / builtin |
| endpoint | string | 否 | API 类工具的调用地址 |
| requestSchema | object | 是 | 请求参数 JSON Schema |
| responseSchema | object | 否 | 响应结构 JSON Schema |
| timeoutMs | int | 否 | 超时时间 (默认 30000ms) |

**响应** (201 Created)：

```json
{
  "code": 200,
  "message": "工具注册成功",
  "data": {
    "id": "01ARZ3NDEKTSV4RRFFQ69G5T01",
    "toolName": "search_code",
    "toolType": "api",
    "description": "搜索项目代码"
  }
}
```

### 10.3 工具执行

> **融合说明**：融合 OpenCode 的 `POST /tool/execute`。统一后通过 Agent 执行接口完成工具的自动编排调用。如需直接调用工具（不经过 Agent 编排），仍保留兼容端点。

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/agents/{id}/execute` | 执行 Agent (异步) | agent:execute |
| POST | `/sessions/{sessionId}/agent/{agentId}` | 会话内调用 Agent (同步) | agent:execute |
| POST | `/tool/execute` | (OpenCode 兼容) 直接执行工具 | agent:execute |

#### POST /agents/{id}/execute — 执行 Agent 请求

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| input | object | 是 | Agent 执行输入 |
| sessionId | string | 否 | 关联会话 ID (可选，用于上下文关联) |

**响应** (202 Accepted)：

```json
{
  "code": 200,
  "message": "Agent 执行已提交",
  "data": {
    "executionId": "01ARZ3NDEKTSV4RRFFQ69G5E01",
    "status": "pending",
    "createdAt": 1762473600000
  }
}
```

#### POST /tool/execute — (OpenCode 兼容) 直接执行工具

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| tool_id | string | 是 | 工具 ID |
| args | object | 是 | 工具参数 |
| session_id | string | 是 | 会话 ID |

**兼容响应**：

```json
{
  "success": true,
  "result": {
    "output": "工具执行结果",
    "metadata": {}
  }
}
```

### 10.4 执行状态与历史

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/agents/executions/{id}` | 查询执行状态 | agent:read |
| GET | `/agents/executions` | 执行历史 | agent:read |

#### GET /agents/executions/{id} — 执行状态响应

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": "01ARZ3NDEKTSV4RRFFQ69G5E01",
    "agentId": "01ARZ3NDEKTSV4RRFFQ69G5A01",
    "agentName": "代码审查助手",
    "status": "completed",
    "input": {},
    "output": {
      "summary": "发现 2 个问题",
      "issues": [
        { "severity": "HIGH", "file": "src/main.java", "line": 42, "message": "SQL 注入风险" }
      ]
    },
    "toolCalls": [
      { "toolName": "search_code", "input": {}, "output": {}, "durationMs": 150 }
    ],
    "tokensUsed": 1520,
    "startedAt": 1762473600000,
    "completedAt": 1762473601000,
    "durationMs": 1000
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| status | string | pending / running / completed / failed / cancelled |
| tokensUsed | int | 总 token 消耗 |
| toolCalls | array | 工具调用链记录 |

#### 执行历史查询参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 页码 |
| size | int | 否 | 每页条数 |
| agentId | string | 否 | Agent ID 筛选 |
| status | string | 否 | 状态筛选 |

### 10.5 Agent 权限

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/agents/{id}/permissions` | 权限列表 | agent:read |
| POST | `/agents/{id}/permissions` | 添加权限 | agent:write |
| DELETE | `/agents/{id}/permissions/{permId}` | 移除权限 | agent:write |

#### POST /agents/{id}/permissions — 添加权限

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| permissionCode | string | 是 | 权限编码 |
| effect | string | 是 | allow / deny |
| condition | object | 否 | 条件约束 (如 scope: "member") |

### 10.6 数据模型

#### AgentResponse

```json
{
  "id": "01ARZ3NDEKTSV4RRFFQ69G5A01",
  "agentName": "代码审查助手",
  "description": "自动审查代码质量和安全漏洞",
  "modelProvider": "alibaba",
  "modelName": "qwen-max",
  "modelConfig": {
    "temperature": 0.3,
    "topP": 0.9,
    "maxTokens": 4096
  },
  "systemPrompt": "你是一个资深的代码审查专家...",
  "status": 1,
  "toolCount": 3,
  "createdAt": 1762473600000,
  "createdBy": "01ARZ3NDEKTSV4RRFFQ69G5FAV"
}
```

#### ExecutionResponse

```json
{
  "id": "01ARZ3NDEKTSV4RRFFQ69G5E01",
  "agentId": "01ARZ3NDEKTSV4RRFFQ69G5A01",
  "agentName": "代码审查助手",
  "status": "completed",
  "input": {},
  "output": {},
  "toolCalls": [],
  "tokensUsed": 1520,
  "startedAt": 1762473600000,
  "completedAt": 1762473601000,
  "durationMs": 1000
}
```

### 10.7 自研工具协议说明

> **融合说明**：OpenCode 使用 MCP (Model Context Protocol) 实现 LLM 工具调用，定义了 `POST /mcp/connect` 和 `POST /mcp/disconnect` 接口。格物平台采用**自研工具协议**替代 MCP，核心区别如下：

| 维度 | OpenCode (MCP) | 格物 (自研工具协议) |
|------|---------------|-------------------|
| 管理方式 | MCP 服务器连接/断开 | Agent 工具注册/管理 |
| 传输层 | 支持多种传输 (stdio/sse) | HTTP REST + WebSocket |
| 协议复杂度 | 全功能协议 | 轻量 JSON Schema 定义 |
| 国密支持 | 不支持 | 原生支持 SM 系列 |
| 超时控制 | 客户端控制 | 服务端控制 (timeoutMs) |

| 方法 | 路径 | 状态 | 替代方案 |
|------|------|------|---------|
| POST | `/mcp/connect` | 废弃 (兼容) | `POST /agents/{id}/tools` |
| POST | `/mcp/disconnect` | 废弃 (兼容) | `DELETE /agents/{id}/tools/{toolId}` |

---

## 11. 工作流 API

> **融合说明**：工作流模块为格物平台核心功能，基于自研状态机引擎（详见 28-workflow-engine-design.md），支持 6-状态工作流状态机和 8-状态节点状态机。OpenCode 无对应模块。详细 API 定义见 30-workflow-api-design.md。

### 11.1 工作流定义管理

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/workflows` | 创建工作流定义 | workflow:write |
| GET | `/workflows` | 工作流定义列表 | workflow:read |
| GET | `/workflows/{id}` | 工作流定义详情 | workflow:read |
| PUT | `/workflows/{id}` | 更新工作流定义 | workflow:write |
| DELETE | `/workflows/{id}` | 删除工作流定义 | workflow:delete |
| POST | `/workflows/{id}/activate` | 激活工作流 | workflow:write |
| POST | `/workflows/{id}/deactivate` | 停用工作流 | workflow:write |

#### POST /workflows — 创建工作流定义请求

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| name | string | 是 | 工作流名称 |
| description | string | 否 | 描述 |
| config | object | 是 | 工作流配置（phases/nodes/transitions） |

### 11.2 工作流实例管理

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/workflow-instances` | 启动工作流实例 | workflow:execute |
| GET | `/workflow-instances` | 实例列表 | workflow:read |
| GET | `/workflow-instances/{id}` | 实例详情 | workflow:read |
| POST | `/workflow-instances/{id}/pause` | 暂停实例 | workflow:execute |
| POST | `/workflow-instances/{id}/resume` | 恢复实例 | workflow:execute |
| POST | `/workflow-instances/{id}/cancel` | 取消实例 | workflow:execute |
| GET | `/workflow-instances/{id}/transitions` | 流转记录 | workflow:read |

#### POST /workflow-instances — 启动实例请求

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| workflowId | Long | 是 | 工作流定义 ID |
| entityType | string | 是 | 实体类型 (PROJECT/SPRINT) |
| entityId | Long | 是 | 实体 ID |
| variables | object | 否 | 流程变量 |

### 11.3 工作流节点操作

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/workflow-instances/{instanceId}/nodes/{nodeId}/complete` | 完成节点 | 节点负责人 |
| POST | `/workflow-instances/{instanceId}/nodes/{nodeId}/review` | 审核节点 | 节点审核人 |
| POST | `/workflow-instances/{instanceId}/nodes/{nodeId}/rollback` | 回退节点 | workflow:execute |
| POST | `/workflow-instances/{instanceId}/nodes/{nodeId}/assign` | 分配节点 | workflow:write |
| GET | `/workflow-instances/{instanceId}/nodes/{nodeId}` | 节点详情 | workflow:read |
| GET | `/workflow-instances/{instanceId}/nodes` | 节点列表 | workflow:read |

### 11.4 工作流通知

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/workflow-notifications` | 通知列表 | workflow:read |
| POST | `/workflow-notifications/{id}/read` | 标记已读 | workflow:read |
| POST | `/workflow-notifications/read-all` | 全部已读 | workflow:read |
| GET | `/workflow-notifications/unread-count` | 未读数量 | workflow:read |

### 11.5 工作流监控

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/workflow-monitor/dashboard` | 监控仪表盘 | workflow:read |
| GET | `/workflow-monitor/statistics` | 流程统计 | workflow:read |
| GET | `/workflow-monitor/timeout-nodes` | 超时节点列表 | workflow:read |

---

## 12. 沙箱 API

> **融合说明**：沙箱模块为格物平台自研的安全代码/工具执行环境，支持三级沙箱架构（Firecracker/gVisor/iSulad）。详见 27-agent-sandbox-design.md。OpenCode 无对应模块。

### 12.1 沙箱配置管理

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/sandbox/configs` | 创建沙箱配置 | system:config |
| GET | `/sandbox/configs` | 沙箱配置列表 | system:config |
| PUT | `/sandbox/configs/{id}` | 更新沙箱配置 | system:config |
| DELETE | `/sandbox/configs/{id}` | 删除沙箱配置 | system:config |
| POST | `/sandbox/configs/{id}/test` | 测试沙箱配置 | system:config |

#### POST /sandbox/configs — 创建沙箱配置请求

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| name | string | 是 | 沙箱名称 |
| sandboxLevel | string | 是 | 沙箱级别: L1(firecracker) / L2(gvisor) / L3(isulad) |
| runtime | string | 是 | 运行环境 (node/python/java/go) |
| resourceLimits | object | 是 | 资源限制 |
| networkAccess | boolean | 否 | 是否允许网络 (默认 false) |
| mountPoints | array | 否 | 挂载点列表 |

#### resourceLimits 说明

| 字段 | 类型 | 说明 |
|------|------|------|
| cpuLimit | string | CPU 限制 (如 "1.0" 表示 1 核) |
| memoryLimit | string | 内存限制 (如 "512Mi") |
| diskLimit | string | 磁盘限制 (如 "1Gi") |
| timeoutMs | int | 超时时间 (毫秒, 默认 30000) |
| maxProcesses | int | 最大进程数 (默认 64) |

### 12.2 沙箱执行

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/sandbox/execute` | 在沙箱中执行代码 | sandbox:execute |
| GET | `/sandbox/executions/{id}` | 查询执行结果 | sandbox:execute |
| POST | `/sandbox/executions/{id}/cancel` | 取消执行 | sandbox:execute |
| GET | `/sandbox/executions` | 执行历史 | sandbox:execute |

#### POST /sandbox/execute — 沙箱执行请求

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| configId | string | 是 | 沙箱配置 ID |
| code | string | 是 | 要执行的代码 |
| input | object | 否 | 输入参数 (JSON) |
| timeoutMs | int | 否 | 超时 (默认取配置值) |
| preferLevel | string | 否 | 优先沙箱级别 (默认 L1) |

**响应** (202 Accepted)：

```json
{
  "code": 200,
  "message": "沙箱执行已提交",
  "data": {
    "executionId": "01ARZ3NDEKTSV4RRFFQ69G5E01",
    "sandboxLevel": "L2",
    "status": "running"
  }
}
```

### 12.3 沙箱资源监控

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/sandbox/stats` | 沙箱资源使用统计 | system:config |
| GET | `/sandbox/configs/{id}/usage` | 指定沙箱的资源使用 | system:config |
| GET | `/sandbox/pools` | 沙箱预热池状态 | system:config |
| POST | `/sandbox/pools/{level}/warmup` | 手动触发预热 | system:config |

### 12.4 沙箱审计

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/sandbox/audit-logs` | 沙箱审计日志 | audit:read |
| GET | `/sandbox/audit-logs/{id}` | 审计日志详情 | audit:read |

#### 监控指标列表

| 指标名称 | 说明 | 单位 |
|----------|------|------|
| sandbox_execution_total | 沙箱执行总次数 | count |
| sandbox_execution_duration_ms | 沙箱执行耗时 | ms |
| sandbox_execution_failed_total | 沙箱执行失败次数 | count |
| sandbox_cpu_usage | 沙箱 CPU 使用率 | percent |
| sandbox_memory_usage | 沙箱内存使用量 | bytes |
| sandbox_pool_available | 预热池可用沙箱数 | count |
| sandbox_ip_pool_available | IP 池可用 IP 数 | count |

---

## 13. 监控 API

> **融合说明**：监控模块由格物平台的 Nightingale 监控系统提供。OpenCode 无对应模块。

### 13.1 系统指标

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/monitor/metrics` | 系统指标 | monitor:read |
| GET | `/monitor/metrics/{metricName}` | 指定指标详情 | monitor:read |

#### GET /monitor/metrics — 查询参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| start | long | 是 | 开始时间戳 (毫秒) |
| end | long | 是 | 结束时间戳 (毫秒) |
| step | string | 否 | 聚合粒度 (1m / 5m / 1h / 1d) |
| metricNames | string | 否 | 指标名称 (逗号分隔，默认全量) |

**响应**：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "metrics": [
      {
        "name": "api_request_total",
        "description": "API 总请求数",
        "dataPoints": [
          { "timestamp": 1762473600000, "value": 1520 },
          { "timestamp": 1762473605000, "value": 1630 }
        ],
        "unit": "count"
      }
    ]
  }
}
```

#### 预定义指标列表

| 指标名称 | 说明 | 单位 |
|----------|------|------|
| api_request_total | API 总请求数 | count |
| api_request_duration_ms | API 请求延迟 | ms |
| api_error_total | API 错误数 | count |
| session_active_count | 活跃会话数 | count |
| agent_execution_total | Agent 执行次数 | count |
| agent_execution_duration_ms | Agent 执行耗时 | ms |
| agent_token_usage | Token 消耗量 | count |
| sandbox_cpu_usage | 沙箱 CPU 使用率 | percent |
| sandbox_memory_usage | 沙箱内存使用量 | bytes |

### 13.2 告警管理

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/monitor/alerts` | 告警列表 | monitor:read |
| PUT | `/monitor/alerts/{id}/ack` | 确认告警 | monitor:read |
| PUT | `/monitor/alerts/{id}/resolve` | 解决告警 | monitor:read |

#### GET /monitor/alerts — 查询参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 页码 |
| size | int | 否 | 每页条数 |
| status | string | 否 | 状态 (firing / acknowledged / resolved) |
| severity | string | 否 | 严重级别 (critical / warning / info) |

---

## 14. 配置管理 API

> **融合说明**：OpenCode 原生提供 `GET /config` 和 `PUT /config` 用于用户级配置读写。统一后配置管理对接 Nacos 配置中心，支持用户级和应用级配置。OpenCode 的扁平 `{key: value}` 配置格式保持兼容。

### 14.1 配置接口

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/config` | 获取用户配置 | 用户登录 |
| PUT | `/config` | 更新用户配置 | 用户登录 |
| GET | `/config/namespaces` | 获取 Nacos 命名空间列表 | system:config |
| GET | `/config/{namespace}` | 获取指定命名空间的配置 | system:config |
| PUT | `/config/{namespace}` | 更新指定命名空间的配置 | system:config |

#### GET /config — 获取用户配置响应

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "theme": "dark",
    "language": "zh-CN",
    "agent": "build",
    "model": "gpt-4",
    "namespace": "DEFAULT"
  }
}
```

#### PUT /config — 更新用户配置请求

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| key | string | 是 | 配置键 |
| value | any | 是 | 配置值 |
| namespace | string | 否 | Nacos 命名空间 (默认 "DEFAULT") |

#### GET /config/{namespace} — 获取命名空间配置

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| group | string | 否 | Nacos 配置组 (默认 "DEFAULT_GROUP") |
| dataId | string | 否 | Nacos 配置 dataId (默认按命名空间聚合) |

### 14.2 Nacos 映射关系

| 配置层级 | 范式 | 说明 |
|----------|------|------|
| 用户级 | `users/{userId}/config` | 用户个人配置 (OpenCode 原语义) |
| 项目级 | `projects/{projectId}/config` | 项目配置 |
| 全局级 | `system/config` | 系统全局配置 |

**Nacos 配置格式**：

```json
{
  "dataId": "gewu-user-config-{userId}",
  "group": "USER",
  "content": "{\"theme\":\"dark\",\"language\":\"zh-CN\"}",
  "type": "json",
  "namespace": "gewu-dev"
}
```

---

## 15. 事件流 API

> **融合说明**：OpenCode 的事件流 (`GET /event/subscribe`) 和格物的 SSE 端点 (`GET /events/stream`) 功能重叠，统一为格物的 `GET /events/stream` 端点，并扩展事件类型覆盖 OpenCode 原有事件。

### 15.1 SSE 端点

```http
GET /api/v1/events/stream
Authorization: Bearer {jwt_token}
Accept: text/event-stream
```

### 15.2 SSE 事件格式

```text
event: message
id: 01ARZ3NDEKTSV4RRFFQ69G5FAV
data: {"type":"session.message","sessionId":"...","content":"...","senderId":"..."}

event: notification
id: 01ARZ3NDEKTSV4RRFFQ69G5FAV
data: {"type":"project.update","projectId":"...","action":"MEMBER_JOINED"}

event: heartbeat
data: {"timestamp":1762473600000}
```

### 15.3 事件类型

| 事件类型 | 说明 | 推送条件 | 来源 |
|----------|------|----------|------|
| `session.message` | 新消息 | 用户是会话成员 | 格物 + OpenCode |
| `session.created` | 会话创建 | 用户是创建者或成员 | OpenCode |
| `session.updated` | 会话更新 | 用户是会话成员 | OpenCode |
| `session.member_joined` | 新成员加入会话 | 用户是会话成员 | 格物 |
| `session.member_left` | 成员离开会话 | 用户是会话成员 | 格物 |
| `project.update` | 项目更新 | 用户是项目成员 | 格物 |
| `project.member_joined` | 新成员加入项目 | 用户是项目成员 | 格物 |
| `project.member_left` | 成员离开项目 | 用户是项目成员 | 格物 |
| `agent.execution_completed` | Agent 执行完成 | 发起人 | 格物 |
| `agent.execution_failed` | Agent 执行失败 | 发起人 | 格物 |
| `tool.executed` | 工具执行 | 参与会话成员 | OpenCode |
| `permission.requested` | 权限请求 | 管理员 | OpenCode |
| `notification` | 系统通知 | 目标用户 | 格物 |
| `heartbeat` | 心跳 (每 30 秒) | 所有连接 | 格物 |

---

## 16. 审计日志 API

> **融合说明**：审计日志为格物平台独立模块，OpenCode 无对应功能。

### 16.1 查询接口

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/audit-logs` | 审计日志查询 | audit:read |

#### GET /audit-logs — 查询参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 页码 |
| size | int | 否 | 每页条数 |
| userId | string | 否 | 按用户筛选 |
| action | string | 否 | 按操作筛选 (CREATE / UPDATE / DELETE / EXECUTE / LOGIN) |
| resourceType | string | 否 | 按资源类型筛选 |
| startTime | long | 否 | 开始时间 (毫秒) |
| endTime | long | 否 | 结束时间 (毫秒) |
| keyword | string | 否 | 关键词搜索 |

**响应**：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "content": [
      {
        "id": "01ARZ3NDEKTSV4RRFFQ69G5L01",
        "userId": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
        "username": "zhangsan",
        "action": "LOGIN",
        "resourceType": "AUTH",
        "resourceId": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
        "detail": "用户登录",
        "ipAddress": "192.168.1.100",
        "userAgent": "Mozilla/5.0...",
        "result": "SUCCESS",
        "createdAt": 1762473600000
      }
    ],
    "page": 1,
    "size": 20,
    "totalElements": 100,
    "totalPages": 5
  }
}
```

---

## 17. OpenCode 兼容说明

### 17.1 API 映射总表

| OpenCode 端点 | 统一后端点 | 状态 | 说明 |
|---------------|-----------|------|------|
| `POST /session` | `POST /sessions` | 已合并 | 参数增强 |
| `GET /session` | `GET /sessions` | 已合并 | 分页格式统一 |
| `GET /session/:id` | `GET /sessions/{id}` | 已合并 | 响应字段扩展 |
| `POST /session/:id/archive` | `POST /sessions/{id}/archive` | 已合并 | — |
| `POST /session/:id/message` | `POST /sessions/{id}/messages` | 已合并 | 参数增强 |
| `GET /session/:id/messages` | `GET /sessions/{id}/messages` | 已合并 | 游标分页 |
| `POST /tool/execute` | `POST /agents/{id}/execute` | 已吸收 | 保留兼容别名 |
| `GET /tool` | `GET /agents/{id}/tools` + `GET /tool` | 已吸收 | 全局工具列表保留 |
| `POST /mcp/connect` | — | 废弃 | 由工具注册替代 |
| `POST /mcp/disconnect` | — | 废弃 | 由工具删除替代 |
| `GET /config` | `GET /config` | 已适配 | 对接 Nacos |
| `PUT /config` | `PUT /config` | 已适配 | 对接 Nacos |
| `GET /project` | `GET /projects` | 已合并 | 分页格式统一 |
| `POST /project` | `POST /projects` | 已合并 | 参数增强 |
| `GET /event/subscribe` | `GET /events/stream` | 已合并 | 事件类型扩展 |

### 17.2 响应格式兼容

OpenCode 原格式 (`{"error":{"code":"ERROR_CODE","message":"错误描述"}}`) 可通过以下方式兼容：

- 请求头 `Accept-Version: v0`
- 网关对旧客户端自动检测并转换
- 兼容期：自废弃公告起 6 个月

### 17.3 限流规则兼容

OpenCode 的静态限流规则在网关层转换为自适应令牌桶的初始配额：

| OpenCode 原规则 | 自适应初始值 | 说明 |
|-----------------|-------------|------|
| Read 100/min | 200/min (自适应基准) | GET 请求集合 |
| Write 20/min | 50/min (自适应基准) | 写操作集合 |
| Tool 10/min | 30/min (自适应基准) | 并入 Agent 执行 |
| MCP 5/min | — | MCP 已废弃 |

### 17.4 迁移建议

1. **响应解析**：从 `response.error.code` 改为 `response.code`，从 `response.error.message` 改为 `response.message`
2. **认证方式**：从 OAuth 2.0 Bearer Token 切换为 JWT (SM3withSM2)，API Key 保持兼容
3. **分页参数**：从 `limit/offset` 改为 `page/size`（`limit/offset` 在兼容期内仍然可用）
4. **工具执行**：将 `POST /tool/execute` 调用迁移到 `POST /agents/{agentId}/execute`
5. **MCP 调用**：迁移到自研工具协议的 Agent 工具注册流程
6. **会话 ID 格式**：从文本 ID 迁移为 ULID 格式

---

## 附录 A：API 路径索引

| # | 方法 | 路径 | 模块 |
|---|------|------|------|
| 1 | POST | `/auth/register` | 用户管理 |
| 2 | POST | `/auth/login` | 用户管理 |
| 3 | POST | `/auth/refresh` | 用户管理 |
| 4 | POST | `/auth/logout` | 用户管理 |
| 5 | POST | `/auth/change-password` | 用户管理 |
| 6 | GET | `/users/me` | 用户管理 |
| 7 | PUT | `/users/me` | 用户管理 |
| 8 | GET | `/users/{id}` | 用户管理 |
| 9 | GET | `/users` | 用户管理 |
| 10 | PUT | `/users/{id}/status` | 用户管理 |
| 11 | PUT | `/users/{id}/roles` | 用户管理 |
| 12 | GET | `/roles` | 用户管理 |
| 13 | POST | `/roles` | 用户管理 |
| 14 | GET | `/roles/{id}` | 用户管理 |
| 15 | PUT | `/roles/{id}` | 用户管理 |
| 16 | DELETE | `/roles/{id}` | 用户管理 |
| 17 | PUT | `/roles/{id}/permissions` | 用户管理 |
| 18 | POST | `/projects` | 项目管理 |
| 19 | GET | `/projects` | 项目管理 |
| 20 | GET | `/projects/{id}` | 项目管理 |
| 21 | PUT | `/projects/{id}` | 项目管理 |
| 22 | DELETE | `/projects/{id}` | 项目管理 |
| 23 | GET | `/projects/{id}/members` | 项目管理 |
| 24 | POST | `/projects/{id}/members` | 项目管理 |
| 25 | PUT | `/projects/{id}/members/{userId}` | 项目管理 |
| 26 | DELETE | `/projects/{id}/members/{userId}` | 项目管理 |
| 27 | GET | `/projects/{id}/stats` | 项目管理 |
| 28 | GET | `/projects/{id}/config` | 项目管理 |
| 29 | PUT | `/projects/{id}/config` | 项目管理 |
| 30 | POST | `/sessions` | 会话管理 |
| 31 | GET | `/sessions` | 会话管理 |
| 32 | GET | `/sessions/{id}` | 会话管理 |
| 33 | PUT | `/sessions/{id}` | 会话管理 |
| 34 | DELETE | `/sessions/{id}` | 会话管理 |
| 35 | POST | `/sessions/{id}/archive` | 会话管理 |
| 36 | GET | `/sessions/{id}/members` | 会话管理 |
| 37 | POST | `/sessions/{id}/members` | 会话管理 |
| 38 | DELETE | `/sessions/{id}/members/{userId}` | 会话管理 |
| 39 | PUT | `/sessions/{id}/read` | 会话管理 |
| 40 | GET | `/sessions/{id}/mentions` | 会话管理 |
| 41 | POST | `/sessions/{id}/messages` | 消息管理 |
| 42 | GET | `/sessions/{id}/messages` | 消息管理 |
| 43 | PUT | `/sessions/{id}/messages/{messageId}` | 消息管理 |
| 44 | DELETE | `/sessions/{id}/messages/{messageId}` | 消息管理 |
| 45 | GET | `/sessions/{id}/messages/{messageId}/read-status` | 消息管理 |
| 46 | POST | `/agents` | Agent 系统 |
| 47 | GET | `/agents` | Agent 系统 |
| 48 | GET | `/agents/{id}` | Agent 系统 |
| 49 | PUT | `/agents/{id}` | Agent 系统 |
| 50 | DELETE | `/agents/{id}` | Agent 系统 |
| 51 | POST | `/agents/{id}/tools` | Agent 系统 |
| 52 | GET | `/agents/{id}/tools` | Agent 系统 |
| 53 | PUT | `/agents/{id}/tools/{toolId}` | Agent 系统 |
| 54 | DELETE | `/agents/{id}/tools/{toolId}` | Agent 系统 |
| 55 | POST | `/agents/{id}/execute` | Agent 系统 |
| 56 | GET | `/agents/executions/{id}` | Agent 系统 |
| 57 | GET | `/agents/executions` | Agent 系统 |
| 58 | POST | `/agents/{id}/permissions` | Agent 系统 |
| 59 | GET | `/agents/{id}/permissions` | Agent 系统 |
| 60 | DELETE | `/agents/{id}/permissions/{permId}` | Agent 系统 |
| 61 | POST | `/sessions/{sessionId}/agent/{agentId}` | Agent 系统 |
| 62 | POST | `/workflows/templates` | 工作流 |
| 63 | GET | `/workflows/templates` | 工作流 |
| 64 | GET | `/workflows/templates/{id}` | 工作流 |
| 65 | PUT | `/workflows/templates/{id}` | 工作流 |
| 66 | DELETE | `/workflows/templates/{id}` | 工作流 |
| 67 | POST | `/workflows/instances` | 工作流 |
| 68 | GET | `/workflows/instances` | 工作流 |
| 69 | GET | `/workflows/instances/{id}` | 工作流 |
| 70 | POST | `/workflows/instances/{id}/cancel` | 工作流 |
| 71 | GET | `/workflows/tasks` | 工作流 |
| 72 | GET | `/workflows/tasks/{id}` | 工作流 |
| 73 | POST | `/workflows/tasks/{id}/complete` | 工作流 |
| 74 | POST | `/workflows/tasks/{id}/assign` | 工作流 |
| 75 | POST | `/sandbox/configs` | 沙箱 |
| 76 | GET | `/sandbox/configs` | 沙箱 |
| 77 | PUT | `/sandbox/configs/{id}` | 沙箱 |
| 78 | DELETE | `/sandbox/configs/{id}` | 沙箱 |
| 79 | POST | `/sandbox/execute` | 沙箱 |
| 80 | GET | `/sandbox/executions/{id}` | 沙箱 |
| 81 | GET | `/sandbox/stats` | 沙箱 |
| 82 | GET | `/sandbox/configs/{id}/usage` | 沙箱 |
| 83 | GET | `/monitor/metrics` | 监控 |
| 84 | GET | `/monitor/metrics/{metricName}` | 监控 |
| 85 | GET | `/monitor/alerts` | 监控 |
| 86 | PUT | `/monitor/alerts/{id}/ack` | 监控 |
| 87 | PUT | `/monitor/alerts/{id}/resolve` | 监控 |
| 88 | GET | `/config` | 配置管理 |
| 89 | PUT | `/config` | 配置管理 |
| 90 | GET | `/config/namespaces` | 配置管理 |
| 91 | GET | `/config/{namespace}` | 配置管理 |
| 92 | PUT | `/config/{namespace}` | 配置管理 |
| 93 | GET | `/events/stream` | 事件流 |
| 94 | GET | `/audit-logs` | 审计日志 |
| 95 | POST | `/tool/execute` | (兼容) Agent 系统 |
| 96 | GET | `/tool` | (兼容) Agent 系统 |

---

## 附录 B：数据模型索引

| 模型名 | 定义章节 | 说明 |
|--------|---------|------|
| UserResponse | 6.2 | 用户信息 |
| RoleResponse | 6.3 | 角色信息 |
| PermissionInfo | 6.4 | 权限定义 |
| ProjectResponse | 7.5 | 项目信息 |
| ProjectMemberResponse | 7.2 | 项目成员 |
| SessionResponse | 8.1 | 会话信息 |
| MessageResponse | 9.4 | 消息信息 |
| AgentResponse | 10.6 | Agent 信息 |
| ExecutionResponse | 10.6 | 执行记录 |
| ToolDefinition | 10.2 | 工具定义 |
| SandboxConfigResponse | 12.1 | 沙箱配置 |
| WorkflowTemplateResponse | 11.1 | 工作流模板 |
| WorkflowInstanceResponse | 11.2 | 工作流实例 |
| MetricDataPoint | 13.1 | 监控指标点 |
| AuditLogEntry | 16.1 | 审计日志条目 |

---

## 附录 C：与 OpenCode 原 API 的差异总结

| 差异项 | OpenCode 原设计方案 | 格物平台统一方案 |
|--------|-------------------|-----------------|
| 认证方式 | OAuth 2.0 + API Key | JWT (SM3withSM2) + API Key |
| 错误格式 | `{"error":{"code":"ERROR_CODE","message":"...","requestId":"..."}}` | `{"code":...,"message":...,"data":...,"requestId":...}` |
| 限流规则 | 静态 (Read 100/min, Write 20/min, Tool 10/min, MCP 5/min) | 自适应令牌桶 (动态基准) |
| 分页方式 | `limit/offset` (非标准) | `page/size` (标准分页，兼容 limit/offset) |
| MCP 协议 | Model Context Protocol (独立 MCP 服务器) | 自研工具协议 (Agent 工具注册) |
| 配置中心 | 本地 JSON 文件配置 | Nacos 配置中心 |
| 工具管理 | 全局工具列表 (独立于 Agent) | 工具归属于 Agent (保留全局兼容接口) |
| 会话模型 | 仅 AI 辅助会话 (单一类型) | 群聊/私聊/AI 辅助 (三种类型) |
| 成员管理 | 无 (会话直接面向用户) | 项目和会话均有完善的成员/角色管理 |
| 版本策略 | URL 路径版本 | URL 路径 + Accept 请求头双模式 |
| 主键格式 | TEXT (自然键) | ULID (全局唯一有序 ID) |

---

**文档结束**
