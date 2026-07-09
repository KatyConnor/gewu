# 格物 - 工作流 API 设计文档

## 文档信息

| 项目 | 内容 |
|------|------|
| 项目名称 | 格物 (Gewu) |
| 文档版本 | V1.1 |
| 创建日期 | 2026-07-08 |
| 文档状态 | 初稿 |
| 源设计文档 | opencode-1.17.14/docs/design/29-workflow-api-design.md (V1.0) |
| 关联统一文档 | 20-unified-prd.md, 21-unified-architecture.md, 22-unified-db-schema.md, 23-unified-api-spec.md, 28-workflow-engine-design.md |
| 更新说明 | V1.1: 统一时间格式(ISO8601→BIGINT毫秒)、错误码(4位→5位)、主键类型(Long→String ULID)，与 23-unified-api-spec.md 对齐 |

---

## 1. 概述

### 1.1 设计目标

本文档定义了格物平台工作流引擎的 RESTful API 接口，支持工作流定义管理、实例管理、节点操作、通知管理等功能。

**设计目标**：
- 遵循 RESTful 设计规范
- 支持 CRUD 操作
- 支持分页查询
- 支持状态管理
- 完善的错误处理

### 1.2 API 规范

| 规范项 | 说明 |
|--------|------|
| **基础路径** | `/api/v1` |
| **认证方式** | JWT Token |
| **请求格式** | JSON |
| **响应格式** | JSON |
| **分页参数** | `page`, `size`, `sort` |
| **时间格式** | BIGINT 毫秒时间戳（与 23-unified-api-spec.md 统一） |

### 1.3 通用响应格式

```json
{
    "code": 0,
    "message": "success",
    "data": {},
    "timestamp": 1762473600000
}
```

### 1.4 错误响应格式

```json
{
    "code": 40001,
    "message": "请求参数错误",
    "details": [{"field": "workflowId", "reason": "不能为空"}],
    "timestamp": 1762473600000
}
```

---

## 2. 工作流定义 API

### 2.1 创建工作流定义

**请求**

```
POST /api/v1/workflows
```

**请求头**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| Authorization | String | 是 | JWT Token |
| Content-Type | String | 是 | application/json |

**请求体**

```json
{
    "name": "研发测试一体化流程",
    "description": "覆盖需求→设计→开发→测试→安全→部署的全流程",
    "config": {
        "phases": [
            {
                "name": "需求阶段",
                "type": "serial",
                "nodes": [
                    {
                        "nodeId": "requirement_collect",
                        "name": "需求收集",
                        "type": "TASK",
                        "ownerRole": "product_manager",
                        "approverRole": "project_manager",
                        "isRequired": true
                    }
                ]
            }
        ]
    }
}
```

**响应**

```json
{
    "code": 200,
    "message": "success",
    "data": {
        "id": 1,
        "name": "研发测试一体化流程",
        "description": "覆盖需求→设计→开发→测试→安全→部署的全流程",
        "version": 1,
        "status": "DRAFT",
        "createdBy": 10,
        "createdAt": 1762473600000,
        "updatedAt": 1762473600000
    },
    "timestamp": 1762473600000
}
```

**错误码**

| 错误码 | 说明 |
|--------|------|
| 400 | 请求参数错误 |
| 401 | 未认证 |
| 403 | 无权限 |
| 409 | 工作流名称已存在 |

### 2.2 获取工作流定义

**请求**

```
GET /api/v1/workflows/{id}
```

**路径参数**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | String | 是 | 工作流定义ID (ULID) |

**响应**

```json
{
    "code": 200,
    "message": "success",
    "data": {
        "id": 1,
        "name": "研发测试一体化流程",
        "description": "覆盖需求→设计→开发→测试→安全→部署的全流程",
        "version": 1,
        "status": "ACTIVE",
        "config": {
            "phases": [...]
        },
        "nodes": [
            {
                "id": 1,
                "nodeId": "requirement_collect",
                "name": "需求收集",
                "type": "TASK",
                "ownerRole": "product_manager",
                "approverRole": "project_manager",
                "isRequired": true
            }
        ],
        "createdBy": 10,
        "createdAt": 1762473600000,
        "updatedAt": 1762473600000
    },
    "timestamp": 1762473600000
}
```

**错误码**

| 错误码 | 说明 |
|--------|------|
| 401 | 未认证 |
| 404 | 工作流不存在 |

### 2.3 更新工作流定义

**请求**

```
PUT /api/v1/workflows/{id}
```

**路径参数**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | String | 是 | 工作流定义ID (ULID) |

**请求体**

```json
{
    "name": "研发测试一体化流程 V2",
    "description": "优化后的工作流程",
    "config": {
        "phases": [...]
    }
}
```

**响应**

```json
{
    "code": 200,
    "message": "success",
    "data": {
        "id": 1,
        "name": "研发测试一体化流程 V2",
        "description": "优化后的工作流程",
        "version": 2,
        "status": "DRAFT",
        "updatedAt": 1762477200000
    },
    "timestamp": 1762477200000
}
```

**错误码**

| 错误码 | 说明 |
|--------|------|
| 400 | 请求参数错误 |
| 401 | 未认证 |
| 403 | 无权限 |
| 404 | 工作流不存在 |

### 2.4 删除工作流定义

**请求**

```
DELETE /api/v1/workflows/{id}
```

**路径参数**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | String | 是 | 工作流定义ID (ULID) |

**响应**

```json
{
    "code": 200,
    "message": "success",
    "data": null,
    "timestamp": 1762473600000
}
```

**错误码**

| 错误码 | 说明 |
|--------|------|
| 401 | 未认证 |
| 403 | 无权限 |
| 404 | 工作流不存在 |
| 409 | 工作流正在运行中，无法删除 |

### 2.5 查询工作流定义列表

**请求**

```
GET /api/v1/workflows
```

**查询参数**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | Integer | 否 | 页码，默认 1 |
| size | Integer | 否 | 每页数量，默认 20 |
| sort | String | 否 | 排序字段，如 `createdAt,desc` |
| status | String | 否 | 状态过滤：DRAFT, ACTIVE, DEPRECATED |
| name | String | 否 | 名称模糊搜索 |

**响应**

```json
{
    "code": 200,
    "message": "success",
    "data": {
        "content": [
            {
                "id": 1,
                "name": "研发测试一体化流程",
                "description": "覆盖需求→设计→开发→测试→安全→部署的全流程",
                "version": 1,
                "status": "ACTIVE",
                "createdBy": 10,
                "createdAt": 1762473600000
            }
        ],
        "page": 1,
        "size": 20,
        "total": 1,
        "totalPages": 1
    },
    "timestamp": 1762473600000
}
```

### 2.6 激活工作流定义

**请求**

```
POST /api/v1/workflows/{id}/activate
```

**路径参数**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | String | 是 | 工作流定义ID (ULID) |

**响应**

```json
{
    "code": 200,
    "message": "success",
    "data": {
        "id": 1,
        "status": "ACTIVE",
        "updatedAt": 1762473600000
    },
    "timestamp": 1762473600000
}
```

**错误码**

| 错误码 | 说明 |
|--------|------|
| 401 | 未认证 |
| 403 | 无权限 |
| 404 | 工作流不存在 |
| 400 | 工作流配置不完整 |

### 2.7 停用工作流定义

**请求**

```
POST /api/v1/workflows/{id}/deactivate
```

**路径参数**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | String | 是 | 工作流定义ID (ULID) |

**响应**

```json
{
    "code": 200,
    "message": "success",
    "data": {
        "id": 1,
        "status": "DEPRECATED",
        "updatedAt": 1762473600000
    },
    "timestamp": 1762473600000
}
```

---

## 3. 工作流实例 API

### 3.1 启动工作流实例

**请求**

```
POST /api/v1/workflow-instances
```

**请求体**

```json
{
    "workflowId": 1,
    "entityType": "PROJECT",
    "entityId": 100,
    "variables": {
        "projectName": "格物平台",
        "priority": "HIGH"
    }
}
```

**响应**

```json
{
    "code": 200,
    "message": "success",
    "data": {
        "id": 1001,
        "workflowId": 1,
        "entityType": "PROJECT",
        "entityId": 100,
        "status": "RUNNING",
        "currentNodeId": "requirement_collect",
        "startedAt": 1762473600000,
        "createdAt": 1762473600000
    },
    "timestamp": 1762473600000
}
```

**错误码**

| 错误码 | 说明 |
|--------|------|
| 400 | 请求参数错误 |
| 401 | 未认证 |
| 403 | 无权限 |
| 404 | 工作流定义不存在 |

### 3.2 获取工作流实例

**请求**

```
GET /api/v1/workflow-instances/{id}
```

**路径参数**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | String | 是 | 工作流实例ID (ULID) |

**响应**

```json
{
    "code": 200,
    "message": "success",
    "data": {
        "id": 1001,
        "workflowId": 1,
        "entityType": "PROJECT",
        "entityId": 100,
        "status": "RUNNING",
        "currentNodeId": "requirement_analyze",
        "startedAt": 1762473600000,
        "createdAt": 1762473600000,
        "updatedAt": 1762477200000,
        "nodes": [
            {
                "id": 1,
                "nodeId": "requirement_collect",
                "status": "COMPLETED",
                "assignedTo": 10,
                "completedAt": 1762475400000
            },
            {
                "id": 2,
                "nodeId": "requirement_analyze",
                "status": "IN_PROGRESS",
                "assignedTo": 10,
                "startedAt": 1762475400000
            }
        ]
    },
    "timestamp": 1762477200000
}
```

### 3.3 查询工作流实例列表

**请求**

```
GET /api/v1/workflow-instances
```

**查询参数**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | Integer | 否 | 页码，默认 1 |
| size | Integer | 否 | 每页数量，默认 20 |
| sort | String | 否 | 排序字段 |
| status | String | 否 | 状态过滤 |
| workflowId | String | 否 | 工作流定义ID过滤 (ULID) |
| entityType | String | 否 | 实体类型过滤 |
| entityId | String | 否 | 实体ID过滤 (ULID) |
| createdBy | String | 否 | 创建人过滤 (ULID) |

**响应**

```json
{
    "code": 200,
    "message": "success",
    "data": {
        "content": [
            {
                "id": 1001,
                "workflowId": 1,
                "entityType": "PROJECT",
                "entityId": 100,
                "status": "RUNNING",
                "currentNodeId": "requirement_analyze",
                "startedAt": 1762473600000,
                "createdAt": 1762473600000
            }
        ],
        "page": 1,
        "size": 20,
        "total": 1,
        "totalPages": 1
    },
    "timestamp": 1762473600000
}
```

### 3.4 暂停工作流实例

**请求**

```
POST /api/v1/workflow-instances/{id}/pause
```

**路径参数**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | String | 是 | 工作流实例ID (ULID) |

**响应**

```json
{
    "code": 200,
    "message": "success",
    "data": {
        "id": 1001,
        "status": "PAUSED",
        "updatedAt": 1762477200000
    },
    "timestamp": 1762477200000
}
```

**错误码**

| 错误码 | 说明 |
|--------|------|
| 401 | 未认证 |
| 403 | 无权限 |
| 404 | 工作流实例不存在 |
| 400 | 只有运行中的工作流才能暂停 |

### 3.5 恢复工作流实例

**请求**

```
POST /api/v1/workflow-instances/{id}/resume
```

**路径参数**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | String | 是 | 工作流实例ID (ULID) |

**响应**

```json
{
    "code": 200,
    "message": "success",
    "data": {
        "id": 1001,
        "status": "RUNNING",
        "updatedAt": 1762477200000
    },
    "timestamp": 1762477200000
}
```

**错误码**

| 错误码 | 说明 |
|--------|------|
| 401 | 未认证 |
| 403 | 无权限 |
| 404 | 工作流实例不存在 |
| 400 | 只有暂停的工作流才能恢复 |

### 3.6 取消工作流实例

**请求**

```
POST /api/v1/workflow-instances/{id}/cancel
```

**路径参数**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | String | 是 | 工作流实例ID (ULID) |

**请求体**

```json
{
    "reason": "项目已取消"
}
```

**响应**

```json
{
    "code": 200,
    "message": "success",
    "data": {
        "id": 1001,
        "status": "CANCELLED",
        "completedAt": 1762477200000
    },
    "timestamp": 1762477200000
}
```

### 3.7 获取工作流流转记录

**请求**

```
GET /api/v1/workflow-instances/{id}/transitions
```

**路径参数**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | String | 是 | 工作流实例ID (ULID) |

**查询参数**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | Integer | 否 | 页码，默认 1 |
| size | Integer | 否 | 每页数量，默认 20 |

**响应**

```json
{
    "code": 200,
    "message": "success",
    "data": {
        "content": [
            {
                "id": 1,
                "instanceId": 1001,
                "fromNodeId": null,
                "toNodeId": "requirement_collect",
                "action": "START",
                "operatorId": 10,
                "comment": null,
                "createdAt": 1762473600000
            },
            {
                "id": 2,
                "instanceId": 1001,
                "fromNodeId": "requirement_collect",
                "toNodeId": "requirement_analyze",
                "action": "COMPLETE",
                "operatorId": 10,
                "comment": null,
                "createdAt": 1762475400000
            }
        ],
        "page": 1,
        "size": 20,
        "total": 2,
        "totalPages": 1
    },
    "timestamp": 1762477200000
}
```

---

## 4. 工作流节点 API

### 4.1 完成节点任务

**请求**

```
POST /api/v1/workflow-instances/{instanceId}/nodes/{nodeId}/complete
```

**路径参数**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| instanceId | String | 是 | 工作流实例ID (ULID) |
| nodeId | String | 是 | 节点ID |

**请求体**

```json
{
    "output": {
        "requirementList": ["REQ-001", "REQ-002"],
        "documentUrl": "https://docs.example.com/req-001"
    }
}
```

**响应**

```json
{
    "code": 200,
    "message": "success",
    "data": {
        "instanceId": 1001,
        "nodeId": "requirement_collect",
        "status": "COMPLETED",
        "nextNodeId": "requirement_analyze",
        "completedAt": 1762477200000
    },
    "timestamp": 1762477200000
}
```

**错误码**

| 错误码 | 说明 |
|--------|------|
| 400 | 请求参数错误 |
| 401 | 未认证 |
| 403 | 无权限 |
| 404 | 工作流实例或节点不存在 |
| 409 | 节点状态不允许完成 |

### 4.2 审核节点任务

**请求**

```
POST /api/v1/workflow-instances/{instanceId}/nodes/{nodeId}/review
```

**路径参数**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| instanceId | String | 是 | 工作流实例ID (ULID) |
| nodeId | String | 是 | 节点ID |

**请求体**

```json
{
    "approved": true,
    "comment": "需求评审通过，可以进入设计阶段"
}
```

**响应**

```json
{
    "code": 200,
    "message": "success",
    "data": {
        "instanceId": 1001,
        "nodeId": "requirement_review",
        "status": "COMPLETED",
        "approved": true,
        "nextNodeId": "requirement_confirm",
        "completedAt": 1762477200000
    },
    "timestamp": 1762477200000
}
```

**错误码**

| 错误码 | 说明 |
|--------|------|
| 400 | 请求参数错误 |
| 401 | 未认证 |
| 403 | 无审核权限 |
| 404 | 工作流实例或节点不存在 |
| 409 | 节点状态不允许审核 |

### 4.3 回退节点任务

**请求**

```
POST /api/v1/workflow-instances/{instanceId}/nodes/{nodeId}/rollback
```

**路径参数**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| instanceId | String | 是 | 工作流实例ID (ULID) |
| nodeId | String | 是 | 节点ID |

**请求体**

```json
{
    "reason": "需求描述不清晰，需要重新分析"
}
```

**响应**

```json
{
    "code": 200,
    "message": "success",
    "data": {
        "instanceId": 1001,
        "nodeId": "requirement_review",
        "status": "REJECTED",
        "previousNodeId": "requirement_analyze",
        "updatedAt": 1762477200000
    },
    "timestamp": 1762477200000
}
```

**错误码**

| 错误码 | 说明 |
|--------|------|
| 400 | 请求参数错误 |
| 401 | 未认证 |
| 403 | 无回退权限 |
| 404 | 工作流实例或节点不存在 |
| 409 | 节点状态不允许回退 |

### 4.4 分配节点任务

**请求**

```
POST /api/v1/workflow-instances/{instanceId}/nodes/{nodeId}/assign
```

**路径参数**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| instanceId | String | 是 | 工作流实例ID (ULID) |
| nodeId | String | 是 | 节点ID |

**请求体**

```json
{
    "assignedTo": 11,
    "comment": "请尽快完成需求分析"
}
```

**响应**

```json
{
    "code": 200,
    "message": "success",
    "data": {
        "instanceId": 1001,
        "nodeId": "requirement_analyze",
        "assignedTo": 11,
        "assignedAt": 1762477200000
    },
    "timestamp": 1762477200000
}
```

### 4.5 获取节点详情

**请求**

```
GET /api/v1/workflow-instances/{instanceId}/nodes/{nodeId}
```

**路径参数**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| instanceId | String | 是 | 工作流实例ID (ULID) |
| nodeId | String | 是 | 节点ID |

**响应**

```json
{
    "code": 200,
    "message": "success",
    "data": {
        "id": 1,
        "instanceId": 1001,
        "nodeId": "requirement_collect",
        "status": "COMPLETED",
        "assignedTo": 10,
        "assignedAt": 1762473600000,
        "startedAt": 1762473600000,
        "completedAt": 1762475400000,
        "output": {
            "requirementList": ["REQ-001", "REQ-002"]
        },
        "reviewComment": null,
        "createdAt": 1762473600000,
        "updatedAt": 1762475400000
    },
    "timestamp": 1762477200000
}
```

### 4.6 查询节点列表

**请求**

```
GET /api/v1/workflow-instances/{instanceId}/nodes
```

**路径参数**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| instanceId | String | 是 | 工作流实例ID (ULID) |

**查询参数**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| status | String | 否 | 状态过滤 |

**响应**

```json
{
    "code": 200,
    "message": "success",
    "data": [
        {
            "id": 1,
            "nodeId": "requirement_collect",
            "status": "COMPLETED",
            "assignedTo": 10,
            "completedAt": 1762475400000
        },
        {
            "id": 2,
            "nodeId": "requirement_analyze",
            "status": "IN_PROGRESS",
            "assignedTo": 10,
            "startedAt": 1762475400000
        }
    ],
    "timestamp": 1762477200000
}
```

---

## 5. 工作流通知 API

### 5.1 查询通知列表

**请求**

```
GET /api/v1/workflow-notifications
```

**查询参数**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | Integer | 否 | 页码，默认 1 |
| size | Integer | 否 | 每页数量，默认 20 |
| isRead | Boolean | 否 | 是否已读过滤 |
| type | String | 否 | 通知类型过滤 |

**响应**

```json
{
    "code": 200,
    "message": "success",
    "data": {
        "content": [
            {
                "id": 1,
                "instanceId": 1001,
                "nodeInstanceId": 1,
                "type": "TASK_ASSIGNED",
                "recipientId": 10,
                "title": "任务已分配",
                "content": "您有一个新的任务：需求分析",
                "isRead": false,
                "sentAt": 1762477200000
            }
        ],
        "page": 1,
        "size": 20,
        "total": 1,
        "totalPages": 1
    },
    "timestamp": 1762477200000
}
```

### 5.2 标记通知已读

**请求**

```
POST /api/v1/workflow-notifications/{id}/read
```

**路径参数**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | String | 是 | 通知ID (ULID) |

**响应**

```json
{
    "code": 200,
    "message": "success",
    "data": {
        "id": 1,
        "isRead": true,
        "readAt": 1762477200000
    },
    "timestamp": 1762477200000
}
```

### 5.3 全部标记已读

**请求**

```
POST /api/v1/workflow-notifications/read-all
```

**响应**

```json
{
    "code": 200,
    "message": "success",
    "data": {
        "updatedCount": 5
    },
    "timestamp": 1762477200000
}
```

### 5.4 获取未读通知数量

**请求**

```
GET /api/v1/workflow-notifications/unread-count
```

**响应**

```json
{
    "code": 200,
    "message": "success",
    "data": {
        "count": 3
    },
    "timestamp": 1762477200000
}
```

---

## 6. 工作流监控 API

### 6.1 获取监控仪表盘数据

**请求**

```
GET /api/v1/workflow-monitor/dashboard
```

**响应**

```json
{
    "code": 200,
    "message": "success",
    "data": {
        "summary": {
            "totalInstances": 100,
            "runningInstances": 25,
            "completedInstances": 70,
            "failedInstances": 5
        },
        "byStatus": {
            "PENDING": 10,
            "RUNNING": 25,
            "PAUSED": 2,
            "COMPLETED": 70,
            "FAILED": 5,
            "CANCELLED": 8
        },
        "recentActivity": [
            {
                "instanceId": 1001,
                "action": "NODE_COMPLETED",
                "nodeId": "requirement_collect",
                "timestamp": 1762477200000
            }
        ]
    },
    "timestamp": 1762477200000
}
```

### 6.2 获取流程统计信息

**请求**

```
GET /api/v1/workflow-monitor/statistics
```

**查询参数**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| startDate | String | 否 | 开始日期（ISO 8601） |
| endDate | String | 否 | 结束日期（ISO 8601） |
| workflowId | String | 否 | 工作流定义ID (ULID) |

**响应**

```json
{
    "code": 200,
    "message": "success",
    "data": {
        "totalInstances": 100,
        "completedInstances": 70,
        "failedInstances": 5,
        "averageCompletionTime": 43200,
        "completionRate": 0.7,
        "byWorkflow": [
            {
                "workflowId": 1,
                "workflowName": "研发测试一体化流程",
                "instanceCount": 50,
                "completionRate": 0.8
            }
        ],
        "byNode": [
            {
                "nodeId": "requirement_collect",
                "nodeName": "需求收集",
                "averageTime": 3600,
                "completionRate": 0.95
            }
        ]
    },
    "timestamp": 1762477200000
}
```

### 6.3 获取超时节点列表

**请求**

```
GET /api/v1/workflow-monitor/timeout-nodes
```

**响应**

```json
{
    "code": 200,
    "message": "success",
    "data": [
        {
            "instanceId": 1001,
            "nodeId": "requirement_review",
            "assignedTo": 11,
            "assignedAt": "2026-07-06T10:00:00Z",
            "timeoutAt": 1762473600000,
            "status": "BLOCKED"
        }
    ],
    "timestamp": 1762477200000
}
```

---

## 7. 错误码汇总

| 错误码 | 说明 |
|--------|------|
| 200 | 成功 |
| 400 | 请求参数错误 |
| 401 | 未认证 |
| 403 | 无权限 |
| 404 | 资源不存在 |
| 409 | 资源冲突 |
| 500 | 服务器内部错误 |

### 7.1 业务错误码

| 错误码 | 说明 |
|--------|------|
| 1001 | 工作流定义不存在 |
| 1002 | 工作流实例不存在 |
| 1003 | 节点不存在 |
| 1004 | 状态转换不允许 |
| 1005 | 权限不足 |
| 1006 | 工作流正在运行中 |
| 1007 | 工作流配置不完整 |
| 1008 | 并行分支未全部完成 |
| 1009 | 节点已超时 |
| 1010 | 依赖未满足 |

---

## 8. 认证与授权

### 8.1 认证方式

所有 API 请求需要在请求头中携带 JWT Token：

```
Authorization: Bearer <token>
```

### 8.2 权限矩阵

| API | 角色权限 |
|-----|----------|
| 创建工作流定义 | admin, project_manager |
| 更新工作流定义 | admin, project_manager |
| 删除工作流定义 | admin |
| 激活工作流定义 | admin, project_manager |
| 启动工作流实例 | admin, project_manager, product_manager |
| 完成节点任务 | 节点负责人 |
| 审核节点任务 | 节点审核人 |
| 回退节点任务 | admin, project_manager |
| 暂停/恢复工作流 | admin, project_manager |
| 取消工作流 | admin, project_manager |
| 查看监控数据 | admin, project_manager |

---

## 9. 版本管理

### 9.1 API 版本

当前 API 版本为 `v1`，基础路径为 `/api/v1`。

### 9.2 版本兼容性

- 新版本 API 发布后，旧版本 API 将保留 6 个月
- 重大变更将通过新的版本号发布
- 废弃的 API 将在响应头中标记 `Deprecation: true`

---

## 10. 示例代码

### 10.1 Java 客户端示例

```java
// 创建工作流实例
WorkflowInstanceRequest request = WorkflowInstanceRequest.builder()
    .workflowId(1L)
    .entityType("PROJECT")
    .entityId(100L)
    .build();

WorkflowInstance instance = workflowClient.createInstance(request);

// 完成节点任务
CompleteNodeRequest completeRequest = CompleteNodeRequest.builder()
    .output(Map.of("requirements", List.of("REQ-001", "REQ-002")))
    .build();

workflowClient.completeNode(instance.getId(), "requirement_collect", completeRequest);

// 审核节点任务
ReviewNodeRequest reviewRequest = ReviewNodeRequest.builder()
    .approved(true)
    .comment("需求评审通过")
    .build();

workflowClient.reviewNode(instance.getId(), "requirement_review", reviewRequest);
```

### 10.2 JavaScript 客户端示例

```javascript
// 创建工作流实例
const instance = await workflowClient.createInstance({
    workflowId: 1,
    entityType: 'PROJECT',
    entityId: 100
});

// 完成节点任务
await workflowClient.completeNode(instance.id, 'requirement_collect', {
    output: {
        requirements: ['REQ-001', 'REQ-002']
    }
});

// 审核节点任务
await workflowClient.reviewNode(instance.id, 'requirement_review', {
    approved: true,
    comment: '需求评审通过'
});
```

### 10.3 cURL 示例

```bash
# 创建工作流实例
curl -X POST http://localhost:8080/api/v1/workflow-instances \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "workflowId": 1,
    "entityType": "PROJECT",
    "entityId": 100
  }'

# 完成节点任务
curl -X POST http://localhost:8080/api/v1/workflow-instances/1001/nodes/requirement_collect/complete \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "output": {
      "requirements": ["REQ-001", "REQ-002"]
    }
  }'
```

---

## 11. 融合说明

### 11.1 国密认证

JWT Token 使用 SM3withSM2 算法签名，符合国密密码算法标准，满足金融、政务等行业的安全合规要求。

### 11.2 信创适配

API 网关层自动检测信创环境并切换国密 TLS，确保在信创基础设施上的安全通信。

### 11.3 工作流引擎

基于自研状态机（非 Activiti/Flowable），详见 28-workflow-engine-design.md。

### 11.4 数据库

OceanBase 4.2+ 为主选，达梦 DM8 为备选。

### 11.5 消息队列

RocketMQ 5.1+ 用于异步通知。

---

## 12. 交叉引用

| 文档 | 章节 | 说明 |
|------|------|------|
| 28-workflow-engine-design.md | §3 状态机 | 工作流状态机定义 |
| 22-unified-db-schema.md | §4.5 工作流数据表 | 数据库表结构 |
| 23-unified-api-spec.md | §11 工作流 API | 统一 API 接口规范 |
| 24-unified-security.md | §12 工作流安全 | 安全控制要求 |
