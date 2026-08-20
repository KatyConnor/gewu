# 五维权限体系补建设计

> 文档编号：37 | 日期：2026-08-02 | 基于 codebase-memory 分析 + 五维权限调研
> 目标：从"用户-角色-功能三维"升级到"用户-机构-角色-菜单-功能五维 + 数据权限"完整体系

---

## 一、现状与差距

| 维度 | 现状 | 状态 |
|------|------|------|
| 用户 | user_account + user_role + 用户管理 CRUD | ✅ |
| 机构/组织 | 无表/实体/归属，tenantId 恒 "default" | ❌ |
| 角色 | role + role_permission + 角色 CRUD + 权限矩阵 | ✅ |
| 菜单 | 无 menu/role_menu，Sidebar 静态硬编码，仅 ADMIN 组过滤 | ❌ |
| 功能 | RBAC 链路通但 @PreAuthorize 全 hasRole(0 处权限码)，前端无权限码 | ⚠️ |
| 数据权限 | 无 data_scope/注解/拦截器，仅 ad-hoc owner 过滤 | ❌ |

**附带缺陷**：JwtAuthenticationFilter 仅注册 ROLE_<role> 为 authority，未注册权限码，导致 hasAuthority 死分支；登录/`/me` 不返回 permissions，前端无法做按钮级控制。

---

## 二、目标体系

```
五维权限体系
├── 用户（已有 + 补机构归属）
├── 机构/组织（新建：树形 org + user.org_id）
├── 角色（已有 + 补 data_scope）
├── 菜单（新建：menu + role_menu + 动态菜单 API + 前端动态加载）
├── 功能（扩面：权限码注册 authority + @PreAuthorize 权限码化 + 前端权限码下发/按钮控制）
└── 数据权限（新建：data_scope + @DataPermission 注解 + MyBatis 拦截器）
```

---

## 三、补建设计

### A. 功能权限扩面（P0 基础）

**A1. 权限码注册为 Spring authority**（修复 hasAuthority 死分支）
`JwtAuthenticationFilter` 构建 authorities 时，除 `ROLE_<role>` 外，把每个权限码也注册为 authority：
```java
List<SimpleGrantedAuthority> authorities = new ArrayList<>();
roleCodes.forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
permissions.forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
```
效果：`@PreAuthorize("hasAuthority('user:manage')")` 生效；ADMIN 因 getPermissionsByUserId 返回全部权限码而拥有全部 authority（超权）。

**A2. @PreAuthorize 权限码化**（10 处改造）
| Controller | 现 | 改为 |
|------------|----|------|
| UserController(4处) | hasRole('ADMIN') | hasAuthority('user:manage') |
| RoleController(类级) | hasRole('ADMIN') | hasAuthority('role:manage') |
| PermissionController(类级) | hasRole('ADMIN') | hasAuthority('role:manage') |
| AuditCenterController(类级) | hasRole('ADMIN') | hasAuthority('audit:approve') |
| ProjectController | hasRole('ADMIN') | hasAuthority('project:manage') |
| SandboxController(2处) | hasRole('ADMIN') or hasAuthority('SANDBOX_EXEC') | hasAuthority('sandbox:manage') |
需新增权限码：`role:manage`、`audit:approve`（V14 补 permission 种子 + ADMIN/角色 role_permission）。

**A3. 前端权限码下发**
- `AuthService.login` TokenDTO 加 `permissions` 字段
- `UserService.getCurrentUser` UserDTO 加 `permissions`
- 前端 `StoredUser`/store 加 `permissions`

**A4. 前端按钮级控制**
- 新建 `usePermission` hook（读 store permissions，提供 `hasPermission(code)`）
- 新建 `Permission` 组件（`<Permission code="user:manage">` 包裹按钮，无权限不渲染）
- 关键操作按钮（创建/删除/管理）用 Permission 包裹

### B. 菜单动态权限（P1）

**B1. 数据模型**（V14）
```sql
CREATE TABLE menu (
  id VARCHAR(26), parent_id VARCHAR(26) DEFAULT NULL, menu_name VARCHAR(64) NOT NULL,
  menu_type TINYINT DEFAULT 1 COMMENT '1目录 2菜单 3按钮',
  path VARCHAR(128), icon VARCHAR(32), sort_order INT DEFAULT 0,
  permission_code VARCHAR(128) DEFAULT NULL COMMENT '关联权限码(按钮/菜单)',
  visible TINYINT DEFAULT 1, -- 0隐藏 1显示
  deleted/created_at/updated_at/created_by/updated_by
);
CREATE TABLE role_menu ( id, role_id, menu_id, created_at, created_by );
```
+ 菜单种子数据（对应现有 Sidebar navGroups）+ role_menu 种子（ADMIN 全菜单，USER 基础菜单）

**B2. 后端**
- `Menu` Domain（树形 parent_id）+ `MenuMapper`
- `MenuService`：`listMenuTree()`（管理员全量树）/ `listCurrentUserMenus()`（按用户角色查 role_menu + 权限码过滤，构建树）/ `createMenu/updateMenu/deleteMenu` / `assignRoleMenus(roleId, menuIds)`
- `MenuController`：`GET /api/v1/menus/current`（当前用户菜单树）/ `GET /api/v1/menus`（全量树，ADMIN）/ `POST/PUT/DELETE /api/v1/menus` / `PUT /api/v1/roles/{roleId}/menus`

**B3. 前端**
- `Sidebar.tsx` 改为动态加载：调 `GET /menus/current` 渲染菜单树（替换静态 navGroups）
- `MenuManagePage`（新）：菜单树 CRUD + 角色菜单分配矩阵
- `menu.ts`（新）：API

### C. 机构/组织（P1）

**C1. 数据模型**（V15）
```sql
CREATE TABLE organization (
  id VARCHAR(26), parent_id VARCHAR(26) DEFAULT NULL, org_name VARCHAR(128) NOT NULL,
  org_code VARCHAR(64) NOT NULL, sort_order INT DEFAULT 0,
  deleted/created_at/updated_at/created_by/updated_by
);
ALTER TABLE user_account ADD COLUMN org_id VARCHAR(26) DEFAULT NULL;
```
+ 组织种子（如：总部/研发中心/产品中心/运维中心）

**C2. 后端**
- `Organization` Domain + `OrganizationMapper`
- `OrgService`：`listOrgTree()` / `createOrg/updateOrg/deleteOrg`（删除校验子节点/用户归属）
- `OrgController`：`GET /api/v1/orgs`（树）/ `POST/PUT/DELETE /api/v1/orgs`（@PreAuthority('org:manage')）
- `UserService` 扩展：UserDTO 加 orgId/orgName；`assignOrg(userId, orgId)`；listUsers 支持按 org 过滤
- `UserController` 加 `PUT /{userId}/org`

**C3. 前端**
- `OrgManagePage`（新）：组织树 CRUD
- `UserManagePage` 加组织字段/筛选
- `org.ts`（新）：API

### D. 数据权限（P2，依赖机构）

**D1. 数据模型**（V16）
```sql
ALTER TABLE role ADD COLUMN data_scope TINYINT DEFAULT 4 COMMENT '1全部 2本部门 3本部门及以下 4本人';
```
+ 种子：ADMIN data_scope=1，USER data_scope=4，部门负责人角色 data_scope=2

**D2. @DataPermission 注解 + 拦截器**
```java
@DataPermission(orgField = "created_by", orgAlias = "agent")  // 标注按 created_by 的 org 过滤
```
- `DataPermissionInterceptor`（MyBatis 拦截器）：解析注解，按当前用户角色的 data_scope 自动追加 SQL 条件：
  - 1 全部：不加条件
  - 2 本部门：`WHERE created_by IN (SELECT user_id FROM user_account WHERE org_id = ?)`（当前用户 org_id）
  - 3 本部门及以下：org_id IN (本部门及子部门)
  - 4 本人：`WHERE created_by = ?`
- ADMIN（data_scope=1）不加条件（超权）

**D3. 应用**
- `AgentService.listAgents`：去掉硬编码 createdBy 过滤，改用 @DataPermission
- `SessionService.listMySessions`、`ProjectService`、`RequirementService` 按需标注

---

## 四、数据模型变更汇总

| 迁移 | 内容 |
|------|------|
| V14 | menu + role_menu 表 + 菜单种子 + role_menu 种子 + 补 role:manage/audit:approve 权限码 |
| V15 | organization 表 + user_account.org_id + 组织种子 |
| V16 | role.data_scope + 种子 |

---

## 五、详细实现任务计划

### 阶段A：功能权限扩面（P0，约 12 文件）
**目标**：权限码注册 authority + @PreAuthorize 权限码化 + 前端权限码下发 + 按钮控制

| 步骤 | 文件 | 改动 |
|------|------|------|
| A1 | `JwtAuthenticationFilter.java` | authorities 加权限码注册 |
| A2 | V14 SQL 补 permission 种子 | role:manage/audit:approve + role_permission |
| A3 | 10 处 @PreAuthorize | hasRole -> hasAuthority(权限码) |
| A4 | `AuthService.login`/`TokenDTO`/`UserService.toDTO`/`UserDTO` | 加 permissions 字段 |
| A5 | 前端 `token.ts`/`store/index.ts` | StoredUser 加 permissions |
| A6 | 前端 `usePermission.ts`(新) + `Permission.tsx`(新) | hook + 组件 |
| A7 | 前端关键按钮 | 用 Permission 包裹 |

**验证**：SecurityConfigTest + 新增 AuthorityTest + E2E

### 阶段B：菜单动态权限（P1，约 10 文件）
**目标**：menu 表 + 动态菜单 API + 前端动态加载 + 菜单管理

| 步骤 | 文件 | 改动 |
|------|------|------|
| B1 | V14 menu/role_menu + 种子 | 表 + 种子 |
| B2 | `Menu.java`/`MenuMapper`/`MenuDTO`/`MenuService`/`MenuController`(新) | 全链路 |
| B3 | `RoleController` 加 `PUT /{id}/menus` | 角色菜单分配 |
| B4 | 前端 `menu.ts`(新) | API |
| B5 | `Sidebar.tsx` 改动态加载 | 替换静态 navGroups |
| B6 | `MenuManagePage.tsx`(新) + 路由 | 菜单树管理 |

**验证**：MenuServiceTest + E2E（菜单加载）+ tsc

### 阶段C：机构/组织（P1，约 10 文件）
**目标**：organization 表 + 组织树 CRUD + 用户归属

| 步骤 | 文件 | 改动 |
|------|------|------|
| C1 | V15 organization + user_account.org_id | 表 |
| C2 | `Organization.java`/`OrgMapper`/`OrgDTO`/`OrgService`/`OrgController`(新) | 全链路 |
| C3 | `UserService`/`UserDTO`/`UserController` | 加 orgId + assignOrg |
| C4 | 前端 `org.ts`(新) + `OrgManagePage.tsx`(新) + 路由 | 组织管理 |
| C5 | `UserManagePage` 加组织字段 | 用户归属 |

**验证**：OrgServiceTest + E2E + tsc

### 阶段D：数据权限（P2，约 8 文件）
**目标**：data_scope + @DataPermission 注解 + 拦截器 + 应用

| 步骤 | 文件 | 改动 |
|------|------|------|
| D1 | V16 role.data_scope | 字段 + 种子 |
| D2 | `DataPermission.java`(注解) + `DataPermissionInterceptor.java`(新) | 注解 + MyBatis 拦截器 |
| D3 | `RoleDTO`/`RoleService` | 加 dataScope |
| D4 | `RoleManagePage` | data_scope 配置 UI |
| D5 | `AgentService.listAgents` 等 | 去 hardcoded，用 @DataPermission |

**验证**：DataPermissionInterceptorTest（各 scope 场景）+ E2E

---

## 六、优先级与里程碑

| 阶段 | 优先级 | 价值 | 依赖 |
|------|--------|------|------|
| A 功能扩面 | P0 | 修复 hasAuthority 死分支 + 前端按钮控制基础 | 无 |
| B 菜单动态 | P1 | 菜单按角色/权限动态显示 | A（权限码） |
| C 机构组织 | P1 | 用户归属 + 数据权限基础 | 无 |
| D 数据权限 | P2 | 按部门/本人数据隔离 | C（机构） |

**建议顺序**：A -> B -> C -> D（A 是 B 的权限码基础；D 依赖 C 的机构维度）

---

## 七、风险

1. **@PreAuthorize 权限码化需确保 ADMIN 超权**：ADMIN 通过 getPermissionsByUserId 返回全部权限码注册为 authority，必须确保新权限码（role:manage 等）也在 ADMIN 全集中（permission 表全量）
2. **菜单动态化破坏现有导航**：Sidebar 改动态加载需保留菜单种子与现有 navGroups 一致，避免菜单丢失；过渡期可 fallback 静态
3. **数据权限拦截器 SQL 注入风险**：DataPermissionInterceptor 拼 SQL 需参数化（org_id 子查询用预编译）
4. **E2E 影响**：E2E USER 角色需有足够权限码/菜单种子，否则功能不可用
5. **向后兼容**：现有 hasRole('ADMIN') 保留（ADMIN 仍有 ROLE_ADMIN），权限码化是叠加（hasAuthority + 保留 hasRole 兜底）

---

## 八、不包含（Out of Scope）

- 多租户隔离（tenant_id 真实化）：当前单租户，机构维度满足部门需求
- 字段级权限（列级脱敏）：本期不做
- 工作流/会话的细粒度数据权限：仅做 Agent/Project/Requirement 示例
- 菜单按钮级（type=3）的完整前端控制：B 阶段做菜单/目录，按钮级由 A 阶段 usePermission 覆盖
