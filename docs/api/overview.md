# 格物平台 - API 文档总览

## 文档信息

| 项目 | 内容 |
|------|------|
| 文档名称 | API 文档总览 |
| 版本 | V1.0 |
| 创建日期 | 2026-07-08 |
| 基地址 | `http://localhost:8080/api/v1` |
| 认证方式 | JWT (SM3withSM2 签名) |

---

## 1. API 规范

### 1.1 通用约定

| 项 | 规范 |
|---|------|
| 协议 | HTTP/HTTPS |
| 格式 | JSON (Content-Type: application/json) |
| 编码 | UTF-8 |
| 版本 | URL 路径前缀 `/api/v1/` |
| 认证 | Bearer Token (JWT) |
| 分页 | `?page=1&size=20` (page 从 1 开始) |
| 排序 | `?sort=created_at,desc` |

### 1.2 通用请求头

| 头 | 必填 | 说明 |
|----|------|------|
| `Authorization` | 是 | `Bearer {jwt_token}` |
| `Content-Type` | POST/PUT | `application/json` |
| `X-Request-Id` | 否 | 请求追踪 ID (ULID) |
| `Accept-Language` | 否 | `zh-CN`, `en-US` |

### 1.3 通用响应格式

```json
{
  "code": 200,
  "message": "success",
  "data": {},
  "requestId": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
  "timestamp": 1762473600000
}
```

#### 错误响应格式

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

### 1.4 错误码定义

| 错误码 | HTTP 状态码 | 说明 |
|--------|------------|------|
| **通用错误** | | |
| 200 | 200 | 成功 |
| 40000 | 400 | 请求参数错误 |
| 40001 | 400 | 参数验证失败 |
| 40002 | 400 | 请求体格式错误 |
| 40100 | 401 | 未认证 |
| 40101 | 401 | 令牌已过期 |
| 40102 | 401 | 令牌无效 |
| 40300 | 403 | 无权限 |
| 40301 | 403 | 资源被锁定 |
| 40400 | 404 | 资源不存在 |
| 40500 | 405 | 请求方法不支持 |
| 40900 | 409 | 资源冲突 (已存在) |
| 42900 | 429 | 请求过于频繁 |
| **业务错误** | | |
| 50000 | 500 | 服务器内部错误 |
| 50001 | 500 | 数据库操作失败 |
| 50002 | 500 | 第三方服务调用失败 |
| 50003 | 500 | 缓存操作失败 |
| 50004 | 500 | 消息队列操作失败 |

### 1.5 通用分页响应

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
  }
}
```

---

## 2. API 模块列表

| 模块 | 基础路径 | 文档 | 优先级 |
|------|---------|------|--------|
| 用户管理 | `/api/v1/users` | [user-api.md](user-api.md) | P0 |
| 项目管理 | `/api/v1/projects` | [project-api.md](project-api.md) | P0 |
| 会话消息 | `/api/v1/sessions` | [session-api.md](session-api.md) | P0 |
| Agent 系统 | `/api/v1/agents` | [agent-api.md](agent-api.md) | P0 |
| 工作流引擎 | `/api/v1/workflows` |（Phase 2 补充） | P1 |
| 审计日志 | `/api/v1/audit-logs` |（Phase 1 补充） | P1 |
| 监控管理 | `/api/v1/monitor` |（由 Nightingale 提供） | P1 |
| 认证鉴权 | `/api/v1/auth` | [user-api.md](user-api.md) | P0 |

---

## 3. API 调用流程

```
客户端                              API 网关                           后端服务
  │                                   │                                  │
  │  1. POST /api/v1/auth/login       │                                  │
  │  {email, password}                │                                  │
  │ ────────────────────────────────> │                                  │
  │                                   │  2. 转发到认证服务                │
  │                                   │ ─────────────────────────────>   │
  │                                   │                                  │
  │                                   │  3. 验证凭证 (SM3密码哈希)       │
  │                                   │ <─────────────────────────────   │
  │                                   │                                  │
  │  4. 返回 JWT (SM2签名)            │                                  │
  │ <──────────────────────────────── │                                  │
  │                                   │                                  │
  │  5. GET /api/v1/projects          │                                  │
  │  Authorization: Bearer {JWT}      │                                  │
  │ ────────────────────────────────> │                                  │
  │                                   │  6. 验证 JWT (SM2验签)           │
  │                                   │  7. 解析权限                     │
  │                                   │  8. 转发（带用户上下文）          │
  │                                   │ ─────────────────────────────>   │
  │                                   │                                  │
  │  9. 返回项目列表                   │                                  │
  │ <──────────────────────────────── │ <─────────────────────────────   │
```

---

## 4. SSE 实时推送

### 4.1 SSE 端点

```http
GET /api/v1/events/stream
Authorization: Bearer {jwt_token}
Accept: text/event-stream
```

### 4.2 SSE 事件格式

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

### 4.3 SSE 事件类型

| 事件类型 | 说明 | 推送条件 |
|----------|------|----------|
| `session.message` | 新消息 | 用户是会话成员 |
| `session.member_joined` | 新成员加入会话 | 用户是会话成员 |
| `project.update` | 项目更新 | 用户是项目成员 |
| `project.member_joined` | 新成员加入项目 | 用户是项目成员 |
| `agent.execution_completed` | Agent 执行完成 | 发起人 |
| `notification` | 系统通知 | 目标用户 |
| `heartbeat` | 心跳 (每30秒) | 所有连接 |

---

## 5. 版本控制

### 5.1 URL 版本策略

```
/api/v1/users      # 当前版本
/api/v2/users      # 下一版本 (兼容期同时存在)
```

### 5.2 版本兼容性保证

| 变更类型 | 兼容性 | 版本策略 |
|----------|--------|----------|
| 新增字段 | 向后兼容 | 小版本更新 |
| 新增端点 | 向后兼容 | 小版本更新 |
| 修改字段 | 不兼容 | 新版本 (v2) |
| 删除字段 | 不兼容 | 新版本 (v2) |
| 删除端点 | 不兼容 | 新版本 (v2) |

---

## 6. 相关文档

| 文档 | 说明 |
|------|------|
| [user-api.md](user-api.md) | 用户管理 API |
| [project-api.md](project-api.md) | 项目管理 API |
| [session-api.md](session-api.md) | 会话消息 API |
| [agent-api.md](agent-api.md) | Agent 系统 API |
| 03-database-design.md | 数据库设计 |
| 07-security-compliance.md | 安全合规设计 |

---

## 附录：修订历史

| 版本 | 日期 | 修订内容 | 修订人 |
|------|------|---------|--------|
| V1.0 | 2026-07-08 | 初稿：API 总览、规范、错误码 | - |

---

**文档结束**
