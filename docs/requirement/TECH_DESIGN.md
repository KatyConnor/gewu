# 需求管理模块 — 技术设计文档

> 版本：V1.0  
> 日期：2026-07-27  
> 状态：设计完成，待开发

---

## 一、系统架构

### 1.1 模块分层

```
┌──────────────────────────────────────────────────────────────┐
│                        Interface 层                            │
│  RequirementController  |  RequirementTaskController          │
──────────────────────────────────────────────────────────────┤
│                        Application 层                          │
│  RequirementService  |  RequirementTaskService  |  DTOs       │
├──────────────────────────────────────────────────────────────┤
│                        Domain 层                               │
│  Requirement  |  RequirementReview  |  RequirementTask        │
│  RequirementComment                                           │
├──────────────────────────────────────────────────────────────┤
│                      Infrastructure 层                         │
│  RequirementMapper  |  RequirementReviewMapper  |  ...         │
├──────────────────────────────────────────────────────────────┤
│                        Database 层                             │
│  requirement  |  requirement_review  |  requirement_task       │
│  requirement_comment                                          │
└──────────────────────────────────────────────────────────────┘
```

### 1.2 技术栈

| 层级 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3.2.5 + MyBatis-Plus |
| 数据库 | MySQL 8.0 |
| 数据库迁移 | Flyway |
| 前端框架 | Next.js 14 + React 18 + TypeScript |
| UI 组件 | Tailwind CSS + lucide-react |
| 状态管理 | Redux Toolkit |
| 拖拽库 | @dnd-kit/core |

---

## 二、数据模型

### 2.1 需求表 `requirement`

| 字段 | 类型 | 说明 | 是否可空 | 默认值 |
|------|------|------|---------|--------|
| id | VARCHAR(26) | ULID 主键 | NOT NULL | - |
| requirement_code | VARCHAR(64) | 需求编号（REQ-YYYY-NNN） | NULL | - |
| title | VARCHAR(256) | 需求标题 | NOT NULL | - |
| description | CLOB | 需求描述（Markdown） | NULL | - |
| type | VARCHAR(32) | 需求类型：STORY/TASK/BUG/IMPROVEMENT/FEATURE/EPIC | NOT NULL | - |
| priority | TINYINT | 优先级：0=P0 1=P1 2=P2 3=P3 | NULL | 2 |
| status | VARCHAR(32) | 状态编码（见状态流转表） | NULL | DRAFT |
| assignee_id | VARCHAR(26) | 负责人 ID | NULL | - |
| reporter_id | VARCHAR(26) | 提出人 ID | NULL | - |
| designer_id | VARCHAR(26) | 设计负责人 ID | NULL | - |
| developer_id | VARCHAR(26) | 开发负责人 ID | NULL | - |
| tester_id | VARCHAR(26) | 测试负责人 ID | NULL | - |
| parent_id | VARCHAR(26) | 父需求 ID（支持需求拆分） | NULL | - |
| project_id | VARCHAR(26) | 关联项目 ID | NULL | - |
| session_ids | VARCHAR(512) | 关联会话 IDs（JSON 数组） | NULL | - |
| document_ids | VARCHAR(512) | 关联文档 IDs（JSON 数组） | NULL | - |
| design_doc | VARCHAR(1024) | 设计文档 URL | NULL | - |
| plan_doc | VARCHAR(1024) | 计划文档 URL | NULL | - |
| test_doc | VARCHAR(1024) | 测试文档 URL | NULL | - |
| story_point | INT | 故事点（工作量估算） | NULL | - |
| estimated_hours | INT | 预估工时（小时） | NULL | - |
| actual_hours | INT | 实际工时（小时） | NULL | - |
| due_date | BIGINT | 截止日期 | NULL | - |
| started_at | BIGINT | 开始时间 | NULL | - |
| completed_at | BIGINT | 完成时间 | NULL | - |
| released_at | BIGINT | 上线时间 | NULL | - |
| cancelled_at | BIGINT | 取消时间 | NULL | - |
| cancel_reason | TEXT | 取消原因 | NULL | - |
| deleted | TINYINT | 逻辑删除标志 | NULL | 0 |
| created_at | BIGINT | 创建时间 | NOT NULL | - |
| updated_at | BIGINT | 更新时间 | NOT NULL | - |
| created_by | VARCHAR(26) | 创建人 | NULL | - |
| updated_by | VARCHAR(26) | 更新人 | NULL | - |

### 2.2 需求评审记录表 `requirement_review`

| 字段 | 类型 | 说明 | 是否可空 | 默认值 |
|------|------|------|---------|--------|
| id | VARCHAR(26) | ULID 主键 | NOT NULL | - |
| requirement_id | VARCHAR(26) | 需求 ID | NOT NULL | - |
| review_type | VARCHAR(32) | 评审类型：REQUIREMENT/DESIGN/TEST | NOT NULL | - |
| reviewer_id | VARCHAR(26) | 评审人 ID | NOT NULL | - |
| review_result | VARCHAR(32) | 评审结果：PENDING/APPROVED/REJECTED | NULL | - |
| review_comment | TEXT | 评审意见 | NULL | - |
| review_attachments | VARCHAR(1024) | 评审附件（JSON 数组） | NULL | - |
| review_order | INT | 评审顺序 | NULL | 0 |
| completed_at | BIGINT | 评审完成时间 | NULL | - |
| created_at | BIGINT | 创建时间 | NOT NULL | - |
| created_by | VARCHAR(26) | 创建人 | NULL | - |

### 2.3 需求任务表 `requirement_task`

| 字段 | 类型 | 说明 | 是否可空 | 默认值 |
|------|------|------|---------|--------|
| id | VARCHAR(26) | ULID 主键 | NOT NULL | - |
| requirement_id | VARCHAR(26) | 需求 ID | NOT NULL | - |
| task_code | VARCHAR(64) | 任务编号（TASK-YYYY-NNN） | NULL | - |
| title | VARCHAR(256) | 任务标题 | NOT NULL | - |
| description | CLOB | 任务描述 | NULL | - |
| assignee_id | VARCHAR(26) | 负责人 ID | NULL | - |
| status | VARCHAR(32) | 状态：PENDING/IN_PROGRESS/COMPLETED | NULL | PENDING |
| estimated_hours | INT | 预估工时 | NULL | - |
| actual_hours | INT | 实际工时 | NULL | - |
| started_at | BIGINT | 开始时间 | NULL | - |
| completed_at | BIGINT | 完成时间 | NULL | - |
| deleted | TINYINT | 逻辑删除标志 | NULL | 0 |
| created_at | BIGINT | 创建时间 | NOT NULL | - |
| updated_at | BIGINT | 更新时间 | NOT NULL | - |
| created_by | VARCHAR(26) | 创建人 | NULL | - |
| updated_by | VARCHAR(26) | 更新人 | NULL | - |

### 2.4 需求评论表 `requirement_comment`

| 字段 | 类型 | 说明 | 是否可空 | 默认值 |
|------|------|------|---------|--------|
| id | VARCHAR(26) | ULID 主键 | NOT NULL | - |
| requirement_id | VARCHAR(26) | 需求 ID | NOT NULL | - |
| content | TEXT | 评论内容 | NOT NULL | - |
| parent_id | VARCHAR(26) | 父评论 ID（支持回复） | NULL | - |
| attachments | VARCHAR(1024) | 附件（JSON 数组） | NULL | - |
| deleted | TINYINT | 逻辑删除标志 | NULL | 0 |
| created_at | BIGINT | 创建时间 | NOT NULL | - |
| updated_at | BIGINT | 更新时间 | NOT NULL | - |
| created_by | VARCHAR(26) | 创建人 | NULL | - |
| updated_by | VARCHAR(26) | 更新人 | NULL | - |

---

## 三、API 设计

### 3.1 需求管理 API

#### 分页查询需求列表

```
GET /api/v1/requirements?page=1&size=20&type=STORY&priority=1&status=IN_DEV&keyword=xxx&assigneeId=xxx
```

**响应**：
```json
{
  "code": 10000,
  "message": "success",
  "data": {
    "records": [...],
    "total": 100,
    "page": 1,
    "size": 20
  }
}
```

#### 获取需求详情

```
GET /api/v1/requirements/{id}
```

#### 创建需求

```
POST /api/v1/requirements
{
  "title": "用户画像分析功能",
  "type": "FEATURE",
  "priority": 1,
  "description": "作为运营人员...",
  "projectId": "xxx",
  "dueDate": 1721500800000
}
```

#### 更新需求

```
PUT /api/v1/requirements/{id}
{
  "title": "用户画像分析功能",
  "description": "更新后的描述...",
  "assigneeId": "xxx"
}
```

#### 删除需求

```
DELETE /api/v1/requirements/{id}
```

#### 更新需求状态

```
PUT /api/v1/requirements/{id}/status
{
  "status": "IN_DEV",
  "reason": "开发已开始"
}
```

#### 提交评审

```
POST /api/v1/requirements/{id}/submit-review
{
  "reviewType": "REQUIREMENT"
}
```

#### 需求统计

```
GET /api/v1/requirements/stats
```

### 3.2 评审 API

#### 获取评审记录

```
GET /api/v1/requirements/{id}/reviews
```

#### 提交评审意见

```
POST /api/v1/requirements/{id}/reviews
{
  "reviewType": "REQUIREMENT",
  "reviewResult": "APPROVED",
  "reviewComment": "需求合理，建议优先级调整为 P1"
}
```

### 3.3 任务 API

#### 获取需求任务列表

```
GET /api/v1/requirements/{id}/tasks
```

#### 创建任务

```
POST /api/v1/requirements/{id}/tasks
{
  "title": "前端页面开发",
  "assigneeId": "xxx",
  "estimatedHours": 8
}
```

#### 更新任务

```
PUT /api/v1/requirements/tasks/{id}
{
  "status": "COMPLETED",
  "actualHours": 6
}
```

#### 删除任务

```
DELETE /api/v1/requirements/tasks/{id}
```

### 3.4 评论 API

#### 获取评论列表

```
GET /api/v1/requirements/{id}/comments
```

#### 添加评论

```
POST /api/v1/requirements/{id}/comments
{
  "content": "这个需求需要和设计团队确认一下交互细节",
  "parentId": null
}
```

#### 删除评论

```
DELETE /api/v1/requirements/comments/{id}
```

---

## 四、前端设计

### 4.1 页面路由

| 路径 | 页面 | 说明 |
|------|------|------|
| `/requirements` | RequirementsPage | 需求列表页 |
| `/requirements/{id}` | RequirementDetailPage | 需求详情页 |

### 4.2 组件结构

```
components/pages/
├── RequirementsPage.tsx          # 需求列表页（表格+看板）
── RequirementDetailPage.tsx     # 需求详情页
├── RequirementEditor.tsx         # 需求编辑器（弹窗）
── RequirementBoard.tsx          # 需求看板视图
├── ReviewTimeline.tsx            # 评审时间线组件
└── TaskList.tsx                  # 任务列表组件

components/ui/
└── StatusBadge.tsx               # 状态标签组件（复用）

lib/
└── requirement.ts                # 需求 API 封装
```

### 4.3 状态管理

需求模块的状态独立于 Redux store，使用组件内部 useState 管理。

### 4.4 关键交互

1. **看板拖拽**：使用 @dnd-kit/core 实现状态拖拽变更
2. **Markdown 编辑**：使用 react-markdown + remark-gfm
3. **评审流程**：时间线组件展示多级评审进度

---

## 五、安全设计

### 5.1 认证

- 所有 API 需要 JWT Token 认证
- 未登录用户返回 401

### 5.2 授权

| API | 权限要求 |
|-----|---------|
| 创建需求 | 产品经理/项目经理 |
| 编辑需求 | 产品经理/项目经理/创建人 |
| 删除需求 | 产品经理/项目经理/创建人 |
| 提交评审 | 产品经理 |
| 评审操作 | 项目经理/技术负责人/测试负责人 |
| 查看需求 | 所有登录用户 |

### 5.3 数据安全

- 软删除，数据保留
- 操作日志记录（created_by/updated_by）
- 敏感字段（如评审意见）仅评审人可见

---

## 六、性能设计

### 6.1 数据库优化

- 关键字段建立索引：status, type, priority, project_id
- 分页查询避免全表扫描
- 软删除字段使用 TINYINT

### 6.2 缓存策略

- 需求统计数据缓存（5 分钟）
- 需求列表查询不缓存（实时性要求高）

### 6.3 前端优化

- 列表分页加载，默认 20 条/页
- 图片懒加载
- 组件按需加载

---

## 七、测试策略

### 7.1 单元测试

- Service 层业务逻辑测试
- 状态流转逻辑测试
- 权限校验测试

### 7.2 集成测试

- API 接口测试
- 数据库操作测试

### 7.3 E2E 测试

- 需求创建→评审→开发→测试→上线完整流程
