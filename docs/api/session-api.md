# 会话消息 API

**基础路径**: `/api/v1/sessions`

---

## 1. 会话接口

### POST /sessions — 创建会话

```http
POST /api/v1/sessions
Authorization: Bearer {token}
Content-Type: application/json

{
  "title": "架构设计讨论",
  "type": 1,
  "projectId": "01ARZ3NDEKTSV4RRFFQ69G5F01",
  "isPublic": false,
  "memberIds": ["user1", "user2", "user3"]
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| title | string | 否 | 会话标题 (私聊可不填) |
| type | int | 是 | 1=群聊 2=私聊 3=AI辅助 |
| projectId | string | 否 | 关联项目ID |
| isPublic | boolean | 否 | 是否公开 (默认 false) |
| memberIds | string[] | 否 | 初始成员ID列表 (创建者自动加入) |

### GET /sessions — 会话列表

```http
GET /api/v1/sessions?page=1&size=20&type=1&status=1
Authorization: Bearer {token}
```

| 参数 | 类型 | 说明 |
|------|------|------|
| page | int | 页码 |
| size | int | 每页条数 |
| type | int | 类型筛选 (1=群聊 2=私聊 3=AI辅助) |
| status | int | 状态筛选 (1=活跃 2=归档) |
| projectId | string | 项目筛选 |

### GET /sessions/{id} — 会话详情

```http
GET /api/v1/sessions/01ARZ3NDEKTSV4RRFFQ69G5F01
Authorization: Bearer {token}
```

### PUT /sessions/{id} — 更新会话

```http
PUT /api/v1/sessions/01ARZ3NDEKTSV4RRFFQ69G5F01
Authorization: Bearer {token}
Content-Type: application/json

{
  "title": "更新后的标题",
  "isPublic": true
}
```

### DELETE /sessions/{id} — 删除会话

```http
DELETE /api/v1/sessions/01ARZ3NDEKTSV4RRFFQ69G5F01
Authorization: Bearer {token}
```

---

## 2. 会话成员接口

### GET /sessions/{id}/members — 成员列表

```http
GET /api/v1/sessions/01ARZ3NDEKTSV4RRFFQ69G5F01/members
Authorization: Bearer {token}
```

### POST /sessions/{id}/members — 添加成员

```http
POST /api/v1/sessions/01ARZ3NDEKTSV4RRFFQ69G5F01/members
Authorization: Bearer {token}
Content-Type: application/json

{
  "userId": "01ARZ3NDEKTSV4RRFFQ69G5FAV"
}
```

### DELETE /sessions/{id}/members/{userId} — 移除成员

```http
DELETE /api/v1/sessions/01ARZ3NDEKTSV4RRFFQ69G5F01/members/01ARZ3NDEKTSV4RRFFQ69G5FAV
Authorization: Bearer {token}
```

### PUT /sessions/{id}/read — 标记已读

```http
PUT /api/v1/sessions/01ARZ3NDEKTSV4RRFFQ69G5F01/read
Authorization: Bearer {token}
```

---

## 3. 消息接口

### POST /sessions/{id}/messages — 发送消息

```http
POST /api/v1/sessions/01ARZ3NDEKTSV4RRFFQ69G5F01/messages
Authorization: Bearer {token}
Content-Type: application/json

{
  "content": "这是一条测试消息",
  "messageType": "text",
  "replyTo": null,
  "mentionUserIds": [],
  "clientId": "client-msg-001"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| content | string | 是 | 消息内容 (Markdown 格式) |
| messageType | string | 是 | text/code/image/file/system/ai |
| replyTo | string | 否 | 回复目标消息ID |
| mentionUserIds | string[] | 否 | @提及用户ID列表 |
| clientId | string | 否 | 客户端消息ID (幂等, 重发检测) |

**响应**: 201 Created

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

### GET /sessions/{id}/messages — 消息历史

```http
GET /api/v1/sessions/01ARZ3NDEKTSV4RRFFQ69G5F01/messages?page=1&size=50&before=msg_id
Authorization: Bearer {token}
```

| 参数 | 类型 | 说明 |
|------|------|------|
| page | int | 页码 |
| size | int | 每页条数 (默认 50, 最大 200) |
| before | string | 游标: 获取比此ID更早的消息 |
| after | string | 游标: 获取比此ID更新的消息 |

### PUT /sessions/{id}/messages/{messageId} — 编辑消息

```http
PUT /api/v1/sessions/01ARZ3NDEKTSV4RRFFQ69G5F01/messages/01ARZ3NDEKTSV4RRFFQ69G5M01
Authorization: Bearer {token}
Content-Type: application/json

{
  "content": "编辑后的内容"
}
```

### DELETE /sessions/{id}/messages/{messageId} — 删除消息

```http
DELETE /api/v1/sessions/01ARZ3NDEKTSV4RRFFQ69G5F01/messages/01ARZ3NDEKTSV4RRFFQ69G5M01
Authorization: Bearer {token}
```

---

## 4. 数据模型

### SessionResponse

```json
{
  "id": "01ARZ3NDEKTSV4RRFFQ69G5F01",
  "title": "架构设计讨论",
  "type": 1,
  "typeName": "群聊",
  "projectId": "01ARZ3NDEKTSV4RRFFQ69G5F01",
  "status": 1,
  "isPublic": false,
  "memberCount": 5,
  "lastMessage": {
    "id": "01ARZ3NDEKTSV4RRFFQ69G5M01",
    "content": "最后一条消息",
    "senderName": "张三",
    "createdAt": 1762473600000
  },
  "unreadCount": 3,
  "createdAt": 1762473600000,
  "createdBy": "01ARZ3NDEKTSV4RRFFQ69G5FAV"
}
```

### MessageResponse

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

## 5. SSE 实时事件

消息通过 SSE 实时推送，事件类型：`session.message`

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

SSE 端点: `GET /api/v1/events/stream`

---

## 6. 修订历史

| 版本 | 日期 | 修订内容 |
|------|------|---------|
| V1.0 | 2026-07-08 | 初稿 |

---

**文档结束**
