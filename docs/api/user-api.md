# 用户管理 API

**基础路径**: `/api/v1`
**认证**: 除登录/注册接口外，均需 Bearer Token

---

## 1. 认证接口

### POST /auth/register — 用户注册

```http
POST /api/v1/auth/register
Content-Type: application/json

{
  "username": "zhangsan",
  "email": "zhangsan@example.com",
  "password": "Abc123!@#",
  "displayName": "张三"
}
```

**响应**: 201 Created

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

### POST /auth/login — 用户登录

```http
POST /api/v1/auth/login
Content-Type: application/json

{
  "login": "zhangsan@example.com",
  "password": "Abc123!@#"
}
```

**响应**: 200 OK

```json
{
  "code": 200,
  "message": "登录成功",
  "data": {
    "accessToken": "eyJhbGciOiJTTTN3aXRoU00yIiwidHlwIjoiSldUIn0...",
    "refreshToken": "eyJhbGciOiJTTTN3aXRoU00yIiwidHlwIjoiSldUIn0...",
    "expiresIn": 86400000,
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

### POST /auth/refresh — 刷新令牌

```http
POST /api/v1/auth/refresh
Content-Type: application/json

{
  "refreshToken": "eyJhbGciOiJTTTN3aXRoU00yIn0..."
}
```

### POST /auth/logout — 退出登录

```http
POST /api/v1/auth/logout
Authorization: Bearer {token}
```

### POST /auth/change-password — 修改密码

```http
POST /api/v1/auth/change-password
Authorization: Bearer {token}
Content-Type: application/json

{
  "oldPassword": "Abc123!@#",
  "newPassword": "NewPass456!@"
}
```

---

## 2. 用户管理接口

### GET /users/me — 获取当前用户信息

```http
GET /api/v1/users/me
Authorization: Bearer {token}
```

### PUT /users/me — 更新当前用户信息

```http
PUT /api/v1/users/me
Authorization: Bearer {token}
Content-Type: application/json

{
  "displayName": "张三(更新)",
  "avatarUrl": "https://example.com/avatar.png"
}
```

### GET /users/{id} — 获取指定用户信息

```http
GET /api/v1/users/01ARZ3NDEKTSV4RRFFQ69G5FAV
Authorization: Bearer {token}
```

### GET /users — 用户列表（管理用）

```http
GET /api/v1/users?page=1&size=20&status=1&keyword=zhang
Authorization: Bearer {token}
```

### PUT /users/{id}/status — 更新用户状态

```http
PUT /api/v1/users/01ARZ3NDEKTSV4RRFFQ69G5FAV/status
Authorization: Bearer {token}
Content-Type: application/json

{
  "status": 2
}
```

---

## 3. 角色管理接口

### GET /roles — 获取角色列表

```http
GET /api/v1/roles
Authorization: Bearer {token}
```

### POST /roles — 创建角色

```http
POST /api/v1/roles
Authorization: Bearer {token}
Content-Type: application/json

{
  "roleName": "自定义角色",
  "roleCode": "CUSTOM_ROLE",
  "description": "自定义测试角色",
  "permissionIds": ["perm1", "perm2"]
}
```

### PUT /roles/{id} — 更新角色

```http
PUT /api/v1/roles/01ARZ3NDEKTSV4RRFFQ69G5F01
Authorization: Bearer {token}
```

### DELETE /roles/{id} — 删除角色

```http
DELETE /api/v1/roles/01ARZ3NDEKTSV4RRFFQ69G5F01
Authorization: Bearer {token}
```

### PUT /roles/{id}/permissions — 分配角色权限

```http
PUT /api/v1/roles/01ARZ3NDEKTSV4RRFFQ69G5F01/permissions
Authorization: Bearer {token}
Content-Type: application/json

{
  "permissionIds": ["perm1", "perm2", "perm3"]
}
```

### PUT /users/{id}/roles — 分配用户角色

```http
PUT /api/v1/users/01ARZ3NDEKTSV4RRFFQ69G5FAV/roles
Authorization: Bearer {token}
Content-Type: application/json

{
  "roleIds": ["role1", "role2"]
}
```

---

## 4. 数据模型

### UserResponse

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
    {
      "id": "01ARZ3NDEKTSV4RRFFQ69G5F04",
      "roleName": "后端开发",
      "roleCode": "BACKEND_DEV"
    }
  ],
  "lastLoginAt": 1762473600000,
  "createdAt": 1762387200000
}
```

### RoleResponse

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

---

## 5. 权限列表（预定义）

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
| `agent:read` | Agent读取 | AGENT | READ |
| `agent:write` | Agent写入 | AGENT | CREATE/UPDATE |
| `agent:execute` | Agent执行 | AGENT | EXECUTE |
| `workflow:read` | 工作流读取 | WORKFLOW | READ |
| `workflow:write` | 工作流写入 | WORKFLOW | CREATE/UPDATE |
| `audit:read` | 审计日志读取 | AUDIT | READ |
| `system:config` | 系统配置 | SYSTEM | CONFIG |

---

## 6. 修订历史

| 版本 | 日期 | 修订内容 |
|------|------|---------|
| V1.0 | 2026-07-08 | 初稿 |

---

**文档结束**
