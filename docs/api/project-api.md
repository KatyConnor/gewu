# 项目管理 API

**基础路径**: `/api/v1/projects`

---

## 1. 项目接口

### POST /projects — 创建项目

```http
POST /api/v1/projects
Authorization: Bearer {token}
Content-Type: application/json

{
  "projectName": "格物平台开发",
  "description": "AI驱动的智能开发协作平台",
  "visibility": 0,
  "techStack": {
    "language": "Java 21",
    "framework": "Spring Boot 3.2",
    "database": "OceanBase 4.2"
  }
}
```

**响应**: 201 Created

```json
{
  "code": 200,
  "message": "项目创建成功",
  "data": {
    "id": "01ARZ3NDEKTSV4RRFFQ69G5F01",
    "projectName": "格物平台开发",
    "description": "AI驱动的智能开发协作平台",
    "visibility": 0,
    "status": 1,
    "ownerId": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
    "memberCount": 1,
    "createdAt": 1762473600000
  }
}
```

### GET /projects — 项目列表

```http
GET /api/v1/projects?page=1&size=20&status=1&keyword=格物
Authorization: Bearer {token}
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 页码 (默认 1) |
| size | int | 否 | 每页条数 (默认 20, 最大 100) |
| status | int | 否 | 状态筛选 (1=活跃 2=归档 3=关闭) |
| keyword | string | 否 | 搜索关键词 (匹配名称和描述) |
| sort | string | 否 | 排序 (默认 created_at,desc) |

### GET /projects/{id} — 项目详情

```http
GET /api/v1/projects/01ARZ3NDEKTSV4RRFFQ69G5F01
Authorization: Bearer {token}
```

### PUT /projects/{id} — 更新项目

```http
PUT /api/v1/projects/01ARZ3NDEKTSV4RRFFQ69G5F01
Authorization: Bearer {token}
Content-Type: application/json

{
  "projectName": "格物平台 V2",
  "description": "更新描述",
  "visibility": 1
}
```

### DELETE /projects/{id} — 删除项目 (归档)

```http
DELETE /api/v1/projects/01ARZ3NDEKTSV4RRFFQ69G5F01
Authorization: Bearer {token}
```

---

## 2. 项目成员接口

### GET /projects/{id}/members — 成员列表

```http
GET /api/v1/projects/01ARZ3NDEKTSV4RRFFQ69G5F01/members
Authorization: Bearer {token}
```

### POST /projects/{id}/members — 添加成员

```http
POST /api/v1/projects/01ARZ3NDEKTSV4RRFFQ69G5F01/members
Authorization: Bearer {token}
Content-Type: application/json

{
  "userId": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
  "roleCode": "BACKEND_DEV"
}
```

### PUT /projects/{id}/members/{userId} — 修改成员角色

```http
PUT /api/v1/projects/01ARZ3NDEKTSV4RRFFQ69G5F01/members/01ARZ3NDEKTSV4RRFFQ69G5FAV
Authorization: Bearer {token}
Content-Type: application/json

{
  "roleCode": "ARCHITECT"
}
```

### DELETE /projects/{id}/members/{userId} — 移除成员

```http
DELETE /api/v1/projects/01ARZ3NDEKTSV4RRFFQ69G5F01/members/01ARZ3NDEKTSV4RRFFQ69G5FAV
Authorization: Bearer {token}
```

---

## 3. 数据模型

### ProjectResponse

```json
{
  "id": "01ARZ3NDEKTSV4RRFFQ69G5F01",
  "projectName": "格物平台开发",
  "description": "AI驱动的智能开发协作平台",
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

### ProjectMemberResponse

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

---

## 4. 修订历史

| 版本 | 日期 | 修订内容 |
|------|------|---------|
| V1.0 | 2026-07-08 | 初稿 |

---

**文档结束**
