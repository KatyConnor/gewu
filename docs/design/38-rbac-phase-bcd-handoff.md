# 五维权限体系补建 - 阶段 B/C/D 执行交接

> 文档编号：38 | 日期：2026-08-02 | 用途：新会话执行阶段 B/C/D 的交接指引
> 前置设计：docs/design/37-five-dimension-rbac-design.md（完整设计）
> 前置完成：阶段 A（功能权限扩面）已全部完成并验证通过

---

## 一、当前已完成状态（阶段 A）

阶段 A「功能权限扩面」已全部完成，后端编译+测试（EnumTest/AgentServicePermissionTest/E2E/SecurityConfigTest）全通过，前端 tsc 通过。

已落地的改动：
- **V14__add_permission_seeds.sql**：补 4 个权限码（role:manage / audit:approve / org:manage / menu:manage）
- **JwtAuthenticationFilter.java**：authorities 注册权限码（修复 hasAuthority 死分支），ADMIN 超权
- **10 处 @PreAuthorize 权限码化**：UserController(4)/RoleController/PermissionController/AuditCenterController(类级) + ProjectController + SandboxController(2)，从 hasRole('ADMIN') 改为 hasAuthority('xxx:manage')
- **TokenDTO / AuthService.login / UserDTO / UserService.toDTO**：下发 permissions 字段
- **前端**：auth.ts TokenResponse + token.ts StoredUser + LoginPage(2处 saveUser) + store + types User 加 permissions；新建 usePermission.ts（hook）+ Permission.tsx（组件）

**关键基础已就绪**：权限码注册为 Spring authority，@PreAuthorize hasAuthority 生效，前端可通过 usePermission/Permission 做按钮级控制。

---

## 二、阶段 B：菜单动态权限

### 数据库（V15__add_menu.sql）
```sql
CREATE TABLE IF NOT EXISTS menu (
  id VARCHAR(26) NOT NULL, parent_id VARCHAR(26) DEFAULT NULL,
  menu_name VARCHAR(64) NOT NULL, menu_type TINYINT DEFAULT 2 COMMENT '1目录 2菜单 3按钮',
  path VARCHAR(128) DEFAULT NULL, icon VARCHAR(32) DEFAULT NULL, sort_order INT DEFAULT 0,
  permission_code VARCHAR(128) DEFAULT NULL, visible TINYINT DEFAULT 1,
  deleted TINYINT DEFAULT 0, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
  created_by VARCHAR(26), updated_by VARCHAR(26),
  PRIMARY KEY(id), KEY idx_menu_parent(parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS role_menu (
  id VARCHAR(26) NOT NULL, role_id VARCHAR(26) NOT NULL, menu_id VARCHAR(26) NOT NULL,
  created_at BIGINT NOT NULL, created_by VARCHAR(26),
  PRIMARY KEY(id), KEY idx_role_menu_role(role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```
+ 菜单种子（对应现有 Sidebar.tsx navGroups 的 4 分组 + 子菜单，约 16 条）+ role_menu 种子（ADMIN 全菜单，USER 基础菜单）
+ 测试 schema.sql 补 menu/role_menu 表（H2 兼容，无 ENGINE）

### 后端（6 文件）
1. `gewu-domain/.../menu/Menu.java`（新）：extends BaseEntity，字段 parentId/menuName/menuType/path/icon/sortOrder/permissionCode/visible
2. `gewu-infrastructure/.../mapper/MenuMapper.java`（新）：extends BaseMapper<Menu>
3. `gewu-application/.../menu/dto/MenuDTO.java`（新）：@Data@Builder，含 children（树形）+ permissionCode
4. `gewu-application/.../menu/MenuService.java`（新）：
   - `listMenuTree()`：全量菜单树（管理员）
   - `listCurrentUserMenus()`：按用户角色查 role_menu + 权限码过滤，构建树
   - `createMenu/updateMenu/deleteMenu`
   - `listRoleMenuIds(roleId)` / `assignRoleMenus(roleId, menuIds)`（替换 role_menu）
5. `gewu-interface/.../controller/MenuController.java`（新）：
   - `GET /api/v1/menus/current`（当前用户菜单树，无需 ADMIN）
   - `GET /api/v1/menus`（全量树，@PreAuthorize hasAuthority('menu:manage')）
   - `POST/PUT/DELETE /api/v1/menus`（@PreAuthorize hasAuthority('menu:manage')）
   - `GET /api/v1/roles/{roleId}/menus` + `PUT /api/v1/roles/{roleId}/menus`（角色菜单分配）
6. `RoleController.java` + `RoleService.java`：加 listRoleMenuIds/assignRoleMenus（或放 MenuController）

### 前端（4 文件）
1. `gewu-web/src/lib/menu.ts`（新）：MenuDTO + listCurrentMenus/listMenus/createMenu/updateMenu/deleteMenu/listRoleMenus/assignRoleMenus
2. `Sidebar.tsx` 改造：挂载时调 `GET /menus/current` 动态渲染菜单树（替换静态 navGroups），**fallback 静态 navGroups**（API 失败时保留现有菜单，避免导航丢失）
3. `MenuManagePage.tsx`（新）：菜单树展示 + CRUD + 角色菜单分配矩阵
4. `types/index.ts` + `app/page.tsx` + `Sidebar.tsx`：PageType 加 'menu-manage' + 路由 + 系统管理组加"菜单管理"

### 验证
- 后端 mvn compile + EnumTest/E2E/SecurityConfigTest
- 前端 tsc
- 验证点：GET /menus/current 返回当前用户可见菜单树；ADMIN 全菜单，USER 基础菜单；Sidebar 动态渲染

---

## 三、阶段 C：机构/组织

### 数据库（V16__add_organization.sql）
```sql
CREATE TABLE IF NOT EXISTS organization (
  id VARCHAR(26) NOT NULL, parent_id VARCHAR(26) DEFAULT NULL,
  org_name VARCHAR(128) NOT NULL, org_code VARCHAR(64) NOT NULL, sort_order INT DEFAULT 0,
  deleted TINYINT DEFAULT 0, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
  created_by VARCHAR(26), updated_by VARCHAR(26),
  PRIMARY KEY(id), KEY idx_org_parent(parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE user_account ADD COLUMN org_id VARCHAR(26) DEFAULT NULL COMMENT '所属机构ID';
```
+ 组织种子（如：总部/研发中心/产品中心/运维中心）+ 测试 schema.sql 补 organization 表 + user_account.org_id

### 后端（6 文件）
1. `gewu-domain/.../org/Organization.java`（新）：extends BaseEntity，parentId/orgName/orgCode/sortOrder
2. `gewu-infrastructure/.../mapper/OrganizationMapper.java`（新）
3. `gewu-application/.../org/dto/OrgDTO.java`（新）：含 children（树）+ userCount
4. `gewu-application/.../org/OrgService.java`（新）：listOrgTree/createOrg/updateOrg/deleteOrg（校验子节点/用户归属）
5. `gewu-interface/.../controller/OrgController.java`（新）：`GET /api/v1/orgs`（树）+ CRUD，@PreAuthorize hasAuthority('org:manage')
6. `UserService.java` + `UserDTO.java` + `UserController.java`：UserDTO 加 orgId/orgName；UserService 加 assignOrg(userId, orgId)；listUsers 支持按 org 过滤；UserController 加 `PUT /{userId}/org`

### 前端（4 文件）
1. `gewu-web/src/lib/org.ts`（新）：OrgDTO + listOrgTree/createOrg/updateOrg/deleteOrg/assignOrg
2. `OrgManagePage.tsx`（新）：组织树 CRUD
3. `UserManagePage.tsx`：加组织列 + 组织筛选 + 分配组织
4. `types/index.ts` + `app/page.tsx` + `Sidebar.tsx`：PageType 加 'org-manage' + 路由 + 系统管理组加"机构管理"

### 验证
- 后端 mvn compile + 测试
- 前端 tsc

---

## 四、阶段 D：数据权限

### 数据库（V17__add_data_scope.sql）
```sql
ALTER TABLE role ADD COLUMN data_scope TINYINT DEFAULT 4 COMMENT '1全部 2本部门 3本部门及以下 4本人';
```
+ 种子：ADMIN data_scope=1，USER data_scope=4 + 测试 schema.sql 补 role.data_scope

### 后端（6 文件）
1. `gewu-common/.../annotation/DataPermission.java`（新）：注解 @Target(METHOD)，属性 orgField（如 "created_by"）/orgAlias（表别名）
2. `gewu-infrastructure/.../interceptor/DataPermissionInterceptor.java`（新）：MyBatis 拦截器，解析 @DataPermission，按当前用户角色的 data_scope 自动追加 SQL：
   - 1 全部：不加条件
   - 2 本部门：`WHERE created_by IN (SELECT id FROM user_account WHERE org_id = ?)`（当前用户 org_id，参数化）
   - 3 本部门及以下：org_id IN (本部门及子部门)
   - 4 本人：`WHERE created_by = ?`
3. `gewu-infrastructure/.../config/MyBatisPlusConfig.java`：注册 DataPermissionInterceptor
4. `RoleDTO.java` + `RoleService.java`：加 dataScope 字段 + toDTO
5. `RoleManagePage.tsx`：data_scope 配置 UI（下拉：全部/本部门/本部门及以下/本人）
6. `AgentService.listAgents`：去掉硬编码 `wrapper.eq(Agent::getCreatedBy, userId)`，改用 @DataPermission 注解；ProjectService/RequirementService 按需标注

### 验证
- DataPermissionInterceptorTest（各 scope 场景：ADMIN 全部/USER 本人/部门负责人本部门）
- E2E（USER data_scope=4 只看自己的 Agent）

---

## 五、关键注意事项

1. **迁移版本号**：阶段 A 已用 V14，阶段 B 用 V15、C 用 V16、D 用 V17（递增，勿冲突）
2. **测试 schema.sql**：每个新表需在 `gewu-interface/src/test/resources/schema.sql` 补 H2 兼容 DDL（无 ENGINE，JSON 用 CLOB）；E2E 用 H2 不走 Flyway
3. **Sidebar 动态加载 fallback**：阶段 B 改 Sidebar 时，API 失败必须 fallback 到静态 navGroups，避免导航丢失
4. **menu 种子对应 navGroups**：阶段 B 菜单种子必须与现有 Sidebar.tsx navGroups 一致（4 分组 + 子菜单 + PageType），否则动态加载后菜单不匹配
5. **权限码一致性**：menu 的 permission_code 需与 permission 表权限码对应；新权限码（menu:manage/org:manage）已在 V14 种子
6. **E2E 兼容**：E2E 注册用户为 USER 角色，需确保 USER 有基础菜单（role_menu 种子）+ 基础权限（V13 role_permission 种子）；E2E 不调管理端点故 @PreAuthorize 不影响
7. **ADMIN 超权**：所有新功能 @PreAuthorize hasAuthority 对 ADMIN 生效（ADMIN 通过 getPermissionsByUserId 返回全部权限码）；data_scope=1 对 ADMIN 不加条件
8. **数据权限拦截器参数化**：DataPermissionInterceptor 拼 SQL 必须参数化（防 SQL 注入），org_id 子查询用预编译
9. **循环依赖**：UserService 已注入 AuthService（阶段 A），新增注入注意避免循环

---

## 六、新会话执行建议

1. 新会话首条消息可说明：**"继续执行 docs/design/38-rbac-phase-bcd-handoff.md 中的阶段 B"**
2. 按阶段 B -> C -> D 顺序实施，每阶段完成后验证（mvn test + tsc）
3. 每阶段验证通过后再进入下一阶段
4. 完整设计参考 docs/design/37-five-dimension-rbac-design.md
