# 格物平台 API 文档

> RESTful API 接口规范与使用说明

## 1. API 概览

### 1.1 基础信息

- **Base URL**: https://api.gewu.com/v1
- **认证方式**: Bearer Token (JWT)
- **内容类型**: application/json
- **字符编码**: UTF-8

### 1.2 通用响应格式

```json
{
  "success": true,
  "code": 200,
  "message": "操作成功",
  "data": {},
  "timestamp": 1688888888888
}
```

### 1.3 错误响应格式

```json
{
  "success": false,
  "code": 400,
  "message": "参数错误",
  "error": "Invalid request parameters",
  "timestamp": 1688888888888
}
```

## 2. 认证接口

### 2.1 用户注册

```
POST /auth/register
```

**请求参数**:
```json
{
  "username": "string",
  "email": "string",
  "password": "string",
  "displayName": "string"
}
```

**响应**:
```json
{
  "success": true,
  "data": {
    "accessToken": "string",
    "refreshToken": "string",
    "expiresIn": 1800000
  }
}
```

### 2.2 用户登录

```
POST /auth/login
```

**请求参数**:
```json
{
  "username": "string",
  "password": "string"
}
```

**响应**:
```json
{
  "success": true,
  "data": {
    "accessToken": "string",
    "refreshToken": "string",
    "expiresIn": 1800000
  }
}
```

### 2.3 刷新 Token

```
POST /auth/refresh
```

**请求头**:
```
Authorization: Bearer {refreshToken}
```

**响应**:
```json
{
  "success": true,
  "data": {
    "accessToken": "string",
    "expiresIn": 1800000
  }
}
```

## 3. 用户接口

### 3.1 获取当前用户

```
GET /users/me
```

**响应**:
```json
{
  "success": true,
  "data": {
    "id": "string",
    "username": "string",
    "email": "string",
    "displayName": "string",
    "createdAt": "2024-01-01T00:00:00Z"
  }
}
```

### 3.2 更新用户信息

```
PUT /users/me
```

**请求参数**:
```json
{
  "displayName": "string",
  "email": "string"
}
```

## 4. 项目接口

### 4.1 创建项目

```
POST /projects
```

**请求参数**:
```json
{
  "name": "string",
  "description": "string",
  "visibility": "private|public"
}
```

### 4.2 获取项目列表

```
GET /projects
```

**查询参数**:
- `page`: 页码 (默认 1)
- `size`: 每页大小 (默认 20)
- `keyword`: 搜索关键词

### 4.3 获取项目详情

```
GET /projects/{projectId}
```

### 4.4 更新项目

```
PUT /projects/{projectId}
```

### 4.5 删除项目

```
DELETE /projects/{projectId}
```

## 5. 会话接口

### 5.1 创建会话

```
POST /sessions
```

**请求参数**:
```json
{
  "title": "string",
  "projectId": "string",
  "agentId": "string"
}
```

### 5.2 获取会话列表

```
GET /sessions
```

**查询参数**:
- `projectId`: 项目 ID
- `page`: 页码
- `size`: 每页大小

### 5.3 获取会话详情

```
GET /sessions/{sessionId}
```

### 5.4 发送消息

```
POST /sessions/{sessionId}/messages
```

**请求参数**:
```json
{
  "content": "string",
  "role": "user"
}
```

### 5.5 获取消息历史

```
GET /sessions/{sessionId}/messages
```

## 6. Agent 接口

### 6.1 创建 Agent

```
POST /agents
```

**请求参数**:
```json
{
  "name": "string",
  "description": "string",
  "model": "string",
  "tools": ["string"]
}
```

### 6.2 获取 Agent 列表

```
GET /agents
```

### 6.3 获取 Agent 详情

```
GET /agents/{agentId}
```

### 6.4 执行 Agent

```
POST /agents/{agentId}/execute
```

**请求参数**:
```json
{
  "input": "string",
  "context": {}
}
```

## 7. 工作流接口

### 7.1 创建工作流

```
POST /workflows
```

**请求参数**:
```json
{
  "name": "string",
  "description": "string",
  "nodes": [],
  "edges": []
}
```

### 7.2 获取工作流列表

```
GET /workflows
```

### 7.3 执行工作流

```
POST /workflows/{workflowId}/execute
```

### 7.4 获取执行实例

```
GET /workflows/{workflowId}/instances
```

## 8. 沙箱接口

### 8.1 创建沙箱

```
POST /sandboxes
```

**请求参数**:
```json
{
  "name": "string",
  "image": "string",
  "cpu": 2,
  "memory": 2048
}
```

### 8.2 获取沙箱列表

```
GET /sandboxes
```

### 8.3 启动沙箱

```
POST /sandboxes/{sandboxId}/start
```

### 8.4 停止沙箱

```
POST /sandboxes/{sandboxId}/stop
```

### 8.5 销毁沙箱

```
DELETE /sandboxes/{sandboxId}
```

## 9. AI 对话接口

### 9.1 发送对话（流式）

```
POST /ai/chat/stream
```

**请求参数**:
```json
{
  "messages": [
    {"role": "user", "content": "string"}
  ],
  "model": "string",
  "temperature": 0.7
}
```

**响应**: Server-Sent Events (SSE)

### 9.2 获取模型列表

```
GET /ai/models
```

## 10. 错误码

| 错误码 | 说明 |
|--------|------|
| 200 | 成功 |
| 400 | 请求参数错误 |
| 401 | 未认证 |
| 403 | 无权限 |
| 404 | 资源不存在 |
| 429 | 请求过于频繁 |
| 500 | 服务器内部错误 |

## 11. 限流策略

- 默认限流: 100 次/分钟
- AI 对话: 10 次/分钟
- 文件上传: 5 次/分钟

## 12. SDK 与工具

### 12.1 JavaScript SDK

```javascript
import { GewuClient } from '@gewu/sdk';

const client = new GewuClient({
  baseUrl: 'https://api.gewu.com/v1',
  token: 'your-access-token'
});

// 创建会话
const session = await client.sessions.create({
  title: 'My Session'
});

// 发送消息
const message = await client.sessions.sendMessage(session.id, {
  content: 'Hello World'
});
```

### 12.2 Python SDK

```python
from gewu import GewuClient

client = GewuClient(
    base_url="https://api.gewu.com/v1",
    token="your-access-token"
)

# 创建会话
session = client.sessions.create(title="My Session")

# 发送消息
message = client.sessions.send_message(session.id, content="Hello World")
```
