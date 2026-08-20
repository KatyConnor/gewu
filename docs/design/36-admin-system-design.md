# 后台管理体系设计

> 文档编号：36 | 日期：2026-08-02 | 模块：用户/角色/权限/审批/后台
> 基于 codebase-memory 架构分析 + 后台管理现状调研

---

## 一、现状分析（codebase-memory + 调研结论）

### 1.1 已具备（点状管理能力）
- 权限域 5 表已建（`user_account`/`role`/`permission`/`user_role`/`role_permission`）+ 9 角色 + 8 权限种子
- `AuthService.assignDefaultRole`（注册分配 USER 角色）、`getRoleCodes`（查用户角色）
- `JwtAuthenticationFilter` 加载 `roleCodes` 到 `UserContext`
- ADMIN 硬编码检查：`AgentService`/`SkillService.requireAdmin`/`AgentMarketService`（内联 `roles.contains("ADMIN")`）
- `@PreAuthorize("hasRole('ADMIN')")`：`ProjectController`/`SandboxController`（3 处）
- `SecurityConfig` 路径级 ADMIN：`adminOnlyModelMatchers`/`adminOnlyMcpMatchers`
- 散落审批：技能审核、需求评审、项目文档审核

### 1.2 核心缺陷（codebase-memory 依赖分析确认）
1. **RBAC 运行时未通电**：`JwtAuthenticationFilter:69` 写死 `permissions = Set.of()` → `UserContext.hasPermission()` 恒 false → `RequirementService` 经 `PermissionChecker.checkPermission` 对所有人抛 FORBIDDEN
2. **`role_permission` 表无种子数据**，权限码不统一（V1 种子 `user:manage` vs `RequirementPermission.requirement:create`）
3. **无用户管理**：`UserService` 仅 4 方法（自查/列表/改自己），无禁用/重置密码/分配角色；`UserController` 列表/详情无 ADMIN 守卫（信息泄露）
4. **无角色/权限 CRUD**：无 RoleController/PermissionController，无前端管理页
5. **无统一审批中心**：审批散落各业务
6. **前端无后台导航**：Sidebar 无"系统管理"分组，无角色驱动菜单过滤

---

## 二、设计目标与原则

- **接通 RBAC**：让 `permission`/`role_permission` 表真正生效，权限检查从硬编码 ADMIN 升级为可配置 RBAC
- **向后兼容**：保留 ADMIN 角色超权（ADMIN 隐式拥有全部权限），不破坏现有 `hasRole('ADMIN')` 逻辑
- **分层管理**：用户管理 / 角色权限管理 / 审批中心 / 系统配置 四大模块
- **最小侵入**：复用现有 5 张权限表、UserContext、PermissionChecker，不重建模型

---

## 三、体系架构

```
后台管理体系
├── RBAC 运行时（基础设施层）
│   ├── JwtAuthenticationFilter 加载 permissions（修复恒空）
│   ├── 权限码统一（domain:action 格式）
│   └── role_permission 种子数据
├── 用户管理（UserManage）
│   ├── 用户列表/搜索（ADMIN）
│   ├── 禁用/启用/锁定
│   ├── 重置密码
│   └── 分配角色
├── 角色权限管理（RolePermissionManage）
│   ├── 角色 CRUD + 角色成员查看
│   ├── 权限 CRUD
│   └── 角色-权限分配（role_permission）
├── 统一审批中心（AuditCenter）
│   ├── 聚合待办：技能审核 / 智能体上架 / 需求评审
│   ├── 统一审批操作（通过/拒绝）
│   └── 审批历史
└── 前端后台导航
    ├── Sidebar "系统管理"分组（角色驱动显示）
    └── 菜单/页面准入过滤
```

---

## 四、详细设计

### 4.1 RBAC 运行时修复（基础，必须先做）

**权限码统一**（`domain:action` 格式，对齐 V1 种子 + 补全）：
| 权限码 | 说明 | 默认授予 |
|--------|------|---------|
| `user:manage` | 用户管理 | ADMIN |
| `role:manage` | 角色权限管理 | ADMIN |
| `audit:view` | 审批中心查看 | ADMIN |
| `audit:approve` | 审批操作 | ADMIN |
| `project:create` | 创建项目 | ALL |
| `project:manage` | 项目管理 | ARCHITECT/PM/ADMIN |
| `agent:manage` | 智能体管理 | ALL |
| `workflow:manage` | 工作流管理 | ALL |
| `sandbox:manage` | 沙箱管理 | ADMIN |
| `session:create` | 创建会话 | ALL |

**V13 migration**：`role_permission` 种子数据（ADMIN 拥有全部，USER 拥有 `project:create`/`agent:manage`/`workflow:manage`/`session:create`，其他角色按职责分配）。同步修正 `RequirementPermission` 常量对齐 `domain:action`。

**JwtAuthenticationFilter 修复**：
```java
// 原：Set<String> permissions = Set.of();
// 改：从 role_permission 加载
Set<String> permissions = authService.getPermissionsByUserId(userId);
```
`AuthService` 新增 `getPermissionsByUserId`（JOIN user_role → role_permission → permission 取 permission_code）。加缓存（CacheKeys.user）。

**PermissionChecker 复用**：现有 `checkPermission(code)` 接通后即生效。需求模块的 `requirement:create` 等改为 `project:create` 或新增 `requirement:*` 权限码并种入 role_permission。

### 4.2 用户管理

**UserService 新增方法**：
- `listUsersForAdmin(query, keyword)`：ADMIN 分页查询（含角色信息）
- `updateUserStatus(userId, status)`：禁用(2)/启用(1)/锁定(3)，ADMIN
- `resetPassword(userId, newPassword)`：ADMIN 重置密码（走密码策略）
- `assignRoles(userId, roleCodes)`：ADMIN 分配角色（替换 user_role）
- `getUserRoles(userId)`：查询用户角色

**UserController 新增端点**（全部 `@PreAuthorize("hasRole('ADMIN')")`）：
- `GET /api/v1/users?page=&keyword=`（列表，加 ADMIN 守卫）
- `PUT /api/v1/users/{userId}/status`（禁用/启用）
- `POST /api/v1/users/{userId}/reset-password`
- `PUT /api/v1/users/{userId}/roles`（分配角色）

**UserStatus 扩展**：保留 ENABLED(1)/DISABLED(2)/LOCKED(3)；DISABLED 登录拦截（AuthService.login 检查 status）。

### 4.3 角色权限管理

**RoleService / PermissionService**（新建）：
- `listRoles()` / `getRole(id)` / `createRole` / `updateRole` / `deleteRole`（预置角色不可删）
- `listPermissions()` / `getPermission` / `createPermission` / `deletePermission`
- `listRolePermissions(roleId)` / `assignPermissions(roleId, permissionCodes)`（替换 role_permission）
- `listRoleUsers(roleId)`（角色成员）

**RoleController / PermissionController**（新建，`@PreAuthorize("hasRole('ADMIN')")`）：
- `GET/POST/PUT/DELETE /api/v1/roles`
- `GET/POST/PUT/DELETE /api/v1/permissions`
- `GET /api/v1/roles/{roleId}/permissions` + `PUT /api/v1/roles/{roleId}/permissions`
- `GET /api/v1/roles/{roleId}/users`

### 4.4 统一审批中心

**AuditCenterService**（新建，聚合查询，不改动各业务审批逻辑）：
- `listPendingAudits()`：聚合待办（技能 publish_status=1 + 智能体广场可加上架审核 + 需求 PENDING_REVIEW）
- `approve(auditType, targetId)` / `reject(auditType, targetId, reason)`：按 type 分发到各业务 Service（SkillService.auditSkill / AgentMarketService 审核方法 / RequirementService.submitReviewOpinion）

**智能体广场补审核门**：`AgentMarketService.publish` 改为 status=1（待审核），新增 `auditMarketPublish(marketId, approved)`；新增 `AgentMarket` 的 status 语义 1=待审核 2=已上架 3=已拒绝。

**AuditCenterController**（`@PreAuthorize("hasPermission('audit:view')")`）：
- `GET /api/v1/admin/audits/pending`
- `POST /api/v1/admin/audits/{type}/{id}/approve`
- `POST /api/v1/admin/audits/{type}/{id}/reject`

**AuditType 枚举**：`SKILL_PUBLISH` / `AGENT_MARKET` / `REQUIREMENT_REVIEW`

### 4.5 前端后台导航

**Sidebar 新增"系统管理"分组**（仅 ADMIN 角色可见）：
```ts
{ label: '系统管理', items: [
  { id: 'user-manage', label: '用户管理', icon: Users },
  { id: 'role-manage', label: '角色权限', icon: KeyRound },
  { id: 'audit-center', label: '审批中心', icon: ClipboardCheck },
]}
```
**菜单过滤**：Sidebar 渲染时按 `user.roles` 过滤（store 已存 roles，ADMIN 显示系统管理组）。

**新增页面**：
- `UserManagePage`：用户列表表格 + 禁用/启用/重置密码/分配角色操作
- `RoleManagePage`：角色列表 + 权限分配矩阵（角色×权限勾选）
- `AuditCenterPage`：待办列表（按类型 tab）+ 通过/拒绝操作

---

## 五、数据模型变更

| 变更 | 文件 | 说明 |
|------|------|------|
| V13 role_permission 种子 | `V13__seed_role_permission.sql` | ADMIN 全权限 + USER 基础权限 + 各角色权限 |
| agent_market status 语义 | 复用现有 status | 1=待审核 2=已上架 3=已拒绝（publish 改为置1） |
| 测试 schema.sql | 补 role_permission 种子 | E2E 兼容 |

无需新建表（5 张权限表已存在），仅补种子数据。

---

## 六、API 设计汇总

| 模块 | 端点 | 权限 |
|------|------|------|
| 用户管理 | `GET/PUT /users`（管理）| `hasRole('ADMIN')` |
| 用户管理 | `PUT /users/{id}/status` `POST /users/{id}/reset-password` `PUT /users/{id}/roles` | ADMIN |
| 角色管理 | `CRUD /roles` + `/roles/{id}/permissions` + `/roles/{id}/users` | ADMIN |
| 权限管理 | `CRUD /permissions` | ADMIN |
| 审批中心 | `GET /admin/audits/pending` `POST /admin/audits/{type}/{id}/approve\|reject` | `hasPermission('audit:view')` |

---

## 七、分阶段执行计划

### 阶段1：RBAC 运行时接通（基础，P0）
**目标**：修复 permissions 恒空，让 RBAC 真正生效

| 步骤 | 文件 | 改动 |
|------|------|------|
| 1 | `V13__seed_role_permission.sql` | role_permission 种子数据 |
| 2 | `AuthService.getPermissionsByUserId` | JOIN 查权限码集合 + 缓存 |
| 3 | `JwtAuthenticationFilter:69` | `Set.of()` → `authService.getPermissionsByUserId(userId)` |
| 4 | `RequirementPermission` | 权限码对齐 `domain:action` |
| 5 | 测试 schema.sql | 补 role_permission 种子 |

**验证**：E2E + SecurityConfigTest + 新增 PermissionLoadingTest

### 阶段2：用户管理（P0）
**目标**：管理员可管理用户状态与角色

| 步骤 | 文件 | 改动 |
|------|------|------|
| 1 | `UserService` | +listUsersForAdmin/updateUserStatus/resetPassword/assignRoles/getUserRoles |
| 2 | `UserController` | +管理端点（@PreAuthorize ADMIN）+ 列表加守卫 |
| 3 | `AuthService.login` | 检查 DISABLED 拒绝登录 |
| 4 | `user.ts`（前端） | +管理 API |
| 5 | `UserManagePage.tsx`（新） | 用户表格+操作 |
| 6 | 路由+Sidebar | PageType + pages 映射 + 系统管理组 |

### 阶段3：角色权限管理（P1）
**目标**：可视化配置角色-权限

| 步骤 | 文件 | 改动 |
|------|------|------|
| 1 | `RoleService`/`PermissionService`（新） | CRUD + 角色权限分配 |
| 2 | `RoleController`/`PermissionController`（新） | 端点 |
| 3 | `role.ts`（前端） | API |
| 4 | `RoleManagePage.tsx`（新） | 角色×权限矩阵 |

### 阶段4：统一审批中心（P1）
**目标**：聚合审批待办，补智能体上架审核门

| 步骤 | 文件 | 改动 |
|------|------|------|
| 1 | `AgentMarketService.publish` | 改为待审核(status=1) + 新增 auditMarketPublish |
| 2 | `AuditCenterService`（新） | 聚合 listPendingAudits + approve/reject 分发 |
| 3 | `AuditCenterController`（新） | 端点 |
| 4 | `AuditCenterPage.tsx`（新） | 待办列表+操作 |
| 5 | `SkillAuditPage` | 可保留或并入审批中心 |

### 阶段5：前端后台导航与菜单过滤（P1）
**目标**：角色驱动的后台入口

| 步骤 | 文件 | 改动 |
|------|------|------|
| 1 | `Sidebar.tsx` | +系统管理分组 + roles 过滤 |
| 2 | `store/index.ts` | roles 用于菜单准入（已存） |
| 3 | `page.tsx`/`types/index.ts` | 新页面路由 |

---

## 八、文件清单预估

| 阶段 | 后端新增/改 | 前端新增/改 |
|------|------------|------------|
| 1 RBAC | V13 SQL、AuthService、JwtAuthenticationFilter、RequirementPermission、测试 | - |
| 2 用户管理 | UserService、UserController、AuthService | user.ts、UserManagePage(新)、路由 |
| 3 角色权限 | RoleService/Controller、PermissionService/Controller(新) | role.ts、RoleManagePage(新) |
| 4 审批中心 | AuditCenterService/Controller(新)、AgentMarketService改 | AuditCenterPage(新) |
| 5 导航 | - | Sidebar、store、page.tsx |

**合计约 25 文件**（10 后端新/改 + 8 前端新/改 + 2 SQL + 5 测试/配置）

---

## 九、风险与约束

1. **RBAC 接通影响面大**：`RequirementService` 当前因 permissions 空对所有人 FORBIDDEN，接通后需确保 USER 角色有所需权限，否则需求功能不可用。阶段1 必须充分种子数据 + 测试。
2. **ADMIN 超权保留**：所有 `hasRole('ADMIN')` 保留，ADMIN 隐式全权限，避免接通后 ADMIN 丢失能力。
3. **预置角色不可删**：9 个种子角色标记为系统预置，deleteRole 拒绝删除。
4. **密码策略**：resetPassword 走 `PasswordPolicyValidator`（已存在）。
5. **审批中心不重写业务审批**：仅聚合查询+分发操作，各业务审批逻辑保留在原 Service。

---

## 十、不包含（Out of Scope）

- 功能开关系统（feature flag）：本期不做，模型/MCP 的 toggle 已够用
- 多租户隔离：当前单租户（UserContext.currentTenantId 默认 "default"）
- 操作审计日志扩展：现有 audit_log 表暂不接后台 UI
- 组织架构/部门管理：当前无组织模型
