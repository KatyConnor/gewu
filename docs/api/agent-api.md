# Agent 系统 API

**基础路径**: `/api/v1/agents`

---

## 1. Agent 配置接口

### POST /agents — 创建 Agent

```http
POST /api/v1/agents
Authorization: Bearer {token}
Content-Type: application/json

{
  "agentName": "代码审查助手",
  "description": "自动审查代码质量和安全漏洞",
  "modelProvider": "alibaba",
  "modelName": "qwen-max",
  "modelConfig": {
    "temperature": 0.3,
    "topP": 0.9,
    "maxTokens": 4096
  },
  "systemPrompt": "你是一个资深的代码审查专家..."
}
```

### GET /agents — Agent 列表

```http
GET /api/v1/agents?page=1&size=20&status=1
Authorization: Bearer {token}
```

### GET /agents/{id} — Agent 详情

```http
GET /api/v1/agents/01ARZ3NDEKTSV4RRFFQ69G5A01
Authorization: Bearer {token}
```

### PUT /agents/{id} — 更新 Agent

```http
PUT /api/v1/agents/01ARZ3NDEKTSV4RRFFQ69G5A01
Authorization: Bearer {token}
Content-Type: application/json

{
  "agentName": "更新名称",
  "modelConfig": {
    "temperature": 0.5
  }
}
```

### DELETE /agents/{id} — 删除 Agent

```http
DELETE /api/v1/agents/01ARZ3NDEKTSV4RRFFQ69G5A01
Authorization: Bearer {token}
```

---

## 2. 工具注册接口

### POST /agents/{id}/tools — 注册工具

```http
POST /api/v1/agents/01ARZ3NDEKTSV4RRFFQ69G5A01/tools
Authorization: Bearer {token}
Content-Type: application/json

{
  "toolName": "search_code",
  "description": "搜索项目代码",
  "toolType": "api",
  "endpoint": "http://localhost:8080/api/v1/search/code",
  "requestSchema": {
    "type": "object",
    "properties": {
      "query": { "type": "string" },
      "language": { "type": "string" }
    },
    "required": ["query"]
  },
  "responseSchema": {
    "type": "array",
    "items": {
      "type": "object",
      "properties": {
        "file": { "type": "string" },
        "line": { "type": "integer" },
        "content": { "type": "string" }
      }
    }
  },
  "timeoutMs": 30000
}
```

### GET /agents/{id}/tools — 工具列表

```http
GET /api/v1/agents/01ARZ3NDEKTSV4RRFFQ69G5A01/tools
Authorization: Bearer {token}
```

### DELETE /agents/{id}/tools/{toolId} — 移除工具

```http
DELETE /api/v1/agents/01ARZ3NDEKTSV4RRFFQ69G5A01/tools/01ARZ3NDEKTSV4RRFFQ69G5T01
Authorization: Bearer {token}
```

---

## 3. Agent 权限接口

### GET /agents/{id}/permissions — 权限列表

```http
GET /api/v1/agents/01ARZ3NDEKTSV4RRFFQ69G5A01/permissions
Authorization: Bearer {token}
```

### POST /agents/{id}/permissions — 添加权限

```http
POST /api/v1/agents/01ARZ3NDEKTSV4RRFFQ69G5A01/permissions
Authorization: Bearer {token}
Content-Type: application/json

{
  "permissionCode": "project:read",
  "effect": "allow",
  "condition": {
    "scope": "member"
  }
}
```

---

## 4. Agent 执行接口

### POST /agents/{id}/execute — 执行 Agent

```http
POST /api/v1/agents/01ARZ3NDEKTSV4RRFFQ69G5A01/execute
Authorization: Bearer {token}
Content-Type: application/json

{
  "input": {
    "task": "审查代码提交 #abc123",
    "context": {
      "repo": "gewu-platform",
      "branch": "feature/xxx",
      "commitId": "abc123"
    }
  },
  "sessionId": "01ARZ3NDEKTSV4RRFFQ69G5F01"
}
```

**响应**: 202 Accepted

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

### GET /agents/executions/{id} — 查询执行状态

```http
GET /api/v1/agents/executions/01ARZ3NDEKTSV4RRFFQ69G5E01
Authorization: Bearer {token}
```

**响应**:

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
        { "severity": "HIGH", "file": "src/main.java", "line": 42, "message": "SQL注入风险" }
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

### GET /agents/executions — 执行历史

```http
GET /api/v1/agents/executions?page=1&size=20&agentId=agent_id&status=completed
Authorization: Bearer {token}
```

---

## 5. 会话内 Agent 调用

### POST /sessions/{id}/agent/{agentId} — 在会话中调用 Agent

```http
POST /api/v1/sessions/01ARZ3NDEKTSV4RRFFQ69G5F01/agent/01ARZ3NDEKTSV4RRFFQ69G5A01
Authorization: Bearer {token}
Content-Type: application/json

{
  "input": {
    "task": "帮我分析这段代码",
    "code": "public class Test { ... }"
  }
}
```

**注意**: 此接口会同步等待 Agent 执行完成，最长超时 120 秒。
对于长时间任务，使用 `POST /agents/{id}/execute` 异步接口。

---

## 6. 数据模型

### AgentResponse

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

### ExecutionResponse

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

---

## 7. 修订历史

| 版本 | 日期 | 修订内容 |
|------|------|---------|
| V1.0 | 2026-07-08 | 初稿 |

---

**文档结束**
