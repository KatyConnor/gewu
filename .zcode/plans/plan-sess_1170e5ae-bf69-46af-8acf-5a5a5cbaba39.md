# 技能库公共/私有分离与发布审核功能

## 问题根因
`skill` 表无"公共/私有/审核状态"区分，`listLibrary()` 显示全部技能（含用户私有），`installSkill()` 复制的副本也在公共库显示 → 私有技能泄露到公共库、安装后出现重复。

## 设计：publish_status 状态机

skill 表新增 `publish_status TINYINT DEFAULT 0`：
| 值 | 状态 | 含义 | 可见性 |
|----|------|------|--------|
| 0 | 私有 | 用户创建/安装的副本（默认） | 仅创建者在"我的技能"可见 |
| 1 | 待审核 | 用户点"发布"提交，待管理员审核 | 仅创建者+管理员可见 |
| 2 | 已发布 | 审核通过，进入公共技能库 | 全部用户在"技能库"可见 |
| 3 | 已拒绝 | 审核未通过 | 仅创建者可见，可改后重新发布 |

**状态流转**：createSkill→0；publishSkill(0或3→1)；audit通过(1→2)；audit拒绝(1→3)；installSkill复制源(2)→副本(0)

**核心规则**：
- `listLibrary()`（公共库）只查 `publish_status=2`
- `listMySkills()` 查 `created_by=当前用户`（所有状态，我的全部技能）
- `createSkill()` 默认 `publish_status=0`（私有，不同步公共库）
- `installSkill()` 副本 `publish_status=0`（私有，不在公共库重复显示）

## 一、数据库
`V12__add_skill_publish_status.sql`：`ALTER TABLE skill ADD COLUMN publish_status TINYINT DEFAULT 0 COMMENT '0=私有 1=待审核 2=已发布 3=已拒绝'`；测试 schema.sql 的 skill 表补同列

## 二、后端

### Skill domain / DTO
- `Skill.java` 加 `private Integer publishStatus;`
- `SkillDTO.java` 加 `publishStatus` + `publishStatusDesc`
- `SkillService.toDTO` 填充 `resolvePublishStatusDesc`

### SkillService 改造
- `listLibrary()`：加 `.eq(Skill::getPublishStatus, 2)` 只返回已发布
- `createSkill()`：`skill.setPublishStatus(0)`（私有）
- `installSkill()`：副本 `copy.setPublishStatus(0)`（私有，不重复入公共库）
- **新增 `publishSkill(skillId)`**：校验创建者所有权 + 状态为0或3 → 置1（待审核）
- **新增 `auditSkill(skillId, approved, reason)`**：校验 ADMIN → 1置2(通过)或3(拒绝)
- **新增 `listPendingSkills()`**：校验 ADMIN → 查 publish_status=1

### SkillController 新增端点
- `POST /api/v1/skills/{skillId}/publish`（用户发布自己的技能）
- `POST /api/v1/skills/{skillId}/audit`（管理员审核，body: `{approved, reason}`）
- `GET /api/v1/skills/pending`（管理员待审核列表）

### 权限
- `publishSkill`：创建者所有权检查（复用 AgentService 的 checkOwnership 模式）
- `auditSkill`/`listPendingSkills`：`UserContext.get().getRoleCodes().contains("ADMIN")`，非管理员抛 FORBIDDEN

## 三、前端

### skill.ts
- `SkillDTO` 加 `publishStatus?: number` + `publishStatusDesc?: string`
- 新增 `publishSkill(id)` / `auditSkill(id, approved, reason)` / `listPendingSkills()`

### MySkillsPage.tsx
- 技能卡片加状态徽章（私有/待审核/已发布/已拒绝，颜色区分）
- "私有"或"已拒绝"的技能显示"发布"按钮 → `publishSkill`
- 发布后 toast 提示"已提交审核"

### 新增 SkillAuditPage.tsx（管理员审核页）
- 调 `listPendingSkills()` 展示待审核技能列表（名称/描述/内容/分类/创建者）
- 每项"通过"/"拒绝"按钮 → `auditSkill(id, approved, reason)`，拒绝需填原因
- 审核后刷新列表 + toast

### 路由与入口
- `types/index.ts` PageType 加 `'skill-audit'`
- `app/page.tsx` pages 映射加 `'skill-audit': SkillAuditPage`
- `Sidebar.tsx` 智能体组加"技能审核"项（`skill-audit`）

## 四、验证
- 后端：`mvn compile` + EnumTest/AgentServicePermissionTest/E2E（listLibrary 改动不影响 E2E，E2E 不测 skill）
- 前端：`tsc --noEmit`
- 验证点：listLibrary 只返回已发布；install 副本不在公共库；publish/audit 状态流转正确；非管理员 audit 返回 FORBIDDEN

## 文件清单（约 12 文件）
后端：V12 SQL、Skill.java、SkillDTO.java、SkillService.java、SkillController.java、测试 schema.sql
前端：skill.ts、MySkillsPage.tsx、SkillAuditPage.tsx(新)、types/index.ts、app/page.tsx、Sidebar.tsx