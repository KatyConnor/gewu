-- =====================================================
-- V8: 需求管理模块 — 需求/评审/任务/评论
-- =====================================================

-- 1. 需求表
CREATE TABLE IF NOT EXISTS requirement (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键，全局唯一标识',
    requirement_code VARCHAR(64) DEFAULT NULL COMMENT '需求编号，格式 REQ-YYYY-NNN（如 REQ-2026-001）',
    title VARCHAR(256) NOT NULL COMMENT '需求标题，最长 256 个字符',
    description CLOB DEFAULT NULL COMMENT '需求描述，支持 Markdown 格式',
    type VARCHAR(32) NOT NULL COMMENT '需求类型：STORY(用户故事)/TASK(任务)/BUG(缺陷)/IMPROVEMENT(改进)/FEATURE(功能需求)/EPIC(史诗)',
    priority TINYINT DEFAULT 2 COMMENT '优先级：0=P0(紧急) 1=P1(高) 2=P2(中) 3=P3(低)',
    status VARCHAR(32) DEFAULT 'DRAFT' COMMENT '状态编码：DRAFT(草稿)/PENDING_REVIEW(待需求评审)/IN_REVIEW(评审中)/APPROVED(已通过)/DESIGN(设计)/PENDING_DESIGN_REVIEW(待设计评审)/DESIGN_IN_REVIEW(设计评审中)/PLANNING(计划制定)/TASK_ASSIGNED(任务分配)/PENDING_DEV(待开发)/IN_DEV(开发中)/DEV_COMPLETED(开发完成)/SMOKE_TEST(冒烟测试)/SIT_TEST(SIT测试)/UAT_TEST(UAT测试)/RELEASED(已上线)/ARCHIVED(已归档)/PROTOTYPE(原型)/CANCELLED(已取消)',
    assignee_id VARCHAR(26) DEFAULT NULL COMMENT '负责人 ID，关联 user_account 表',
    reporter_id VARCHAR(26) DEFAULT NULL COMMENT '提出人 ID，关联 user_account 表',
    designer_id VARCHAR(26) DEFAULT NULL COMMENT '设计负责人 ID，关联 user_account 表',
    developer_id VARCHAR(26) DEFAULT NULL COMMENT '开发负责人 ID，关联 user_account 表',
    tester_id VARCHAR(26) DEFAULT NULL COMMENT '测试负责人 ID，关联 user_account 表',
    parent_id VARCHAR(26) DEFAULT NULL COMMENT '父需求 ID，支持需求拆分（EPIC→STORY→TASK），自关联',
    project_id VARCHAR(26) DEFAULT NULL COMMENT '关联项目 ID，关联 project 表',
    session_ids VARCHAR(512) DEFAULT NULL COMMENT '关联会话 IDs，JSON 数组格式（如 ["session1","session2"]）',
    document_ids VARCHAR(512) DEFAULT NULL COMMENT '关联文档 IDs，JSON 数组格式（如 ["doc1","doc2"]）',
    design_doc VARCHAR(1024) DEFAULT NULL COMMENT '设计文档 URL，存储 MinIO 文件地址',
    plan_doc VARCHAR(1024) DEFAULT NULL COMMENT '计划文档 URL，存储 MinIO 文件地址',
    test_doc VARCHAR(1024) DEFAULT NULL COMMENT '测试文档 URL，存储 MinIO 文件地址',
    story_point INT DEFAULT NULL COMMENT '故事点，用于敏捷开发工作量估算',
    estimated_hours INT DEFAULT NULL COMMENT '预估工时，单位：小时',
    actual_hours INT DEFAULT NULL COMMENT '实际工时，单位：小时',
    due_date BIGINT DEFAULT NULL COMMENT '截止日期，Unix 时间戳（毫秒）',
    started_at BIGINT DEFAULT NULL COMMENT '开始时间，Unix 时间戳（毫秒），状态变为 IN_DEV 时记录',
    completed_at BIGINT DEFAULT NULL COMMENT '完成时间，Unix 时间戳（毫秒），状态变为 RELEASED 时记录',
    released_at BIGINT DEFAULT NULL COMMENT '上线时间，Unix 时间戳（毫秒）',
    cancelled_at BIGINT DEFAULT NULL COMMENT '取消时间，Unix 时间戳（毫秒）',
    cancel_reason TEXT DEFAULT NULL COMMENT '取消原因，状态变为 CANCELLED 时填写',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标志：0=未删除 1=已删除',
    created_at BIGINT NOT NULL COMMENT '创建时间，Unix 时间戳（毫秒）',
    updated_at BIGINT NOT NULL COMMENT '更新时间，Unix 时间戳（毫秒）',
    created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人 ID，关联 user_account 表',
    updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人 ID，关联 user_account 表',
    PRIMARY KEY (id),
    KEY idx_requirement_type (type) COMMENT '按需求类型查询索引',
    KEY idx_requirement_status (status) COMMENT '按状态查询索引',
    KEY idx_requirement_priority (priority) COMMENT '按优先级查询索引',
    KEY idx_requirement_parent (parent_id) COMMENT '按父需求查询子需求索引',
    KEY idx_requirement_project (project_id) COMMENT '按关联项目查询索引',
    KEY idx_requirement_assignee (assignee_id) COMMENT '按负责人查询索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='需求表 — 管理需求全生命周期';

-- 2. 需求评审记录表
CREATE TABLE IF NOT EXISTS requirement_review (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键，全局唯一标识',
    requirement_id VARCHAR(26) NOT NULL COMMENT '需求 ID，关联 requirement 表',
    review_type VARCHAR(32) NOT NULL COMMENT '评审类型：REQUIREMENT(需求评审)/DESIGN(设计评审)/TEST(测试案例评审)',
    reviewer_id VARCHAR(26) NOT NULL COMMENT '评审人 ID，关联 user_account 表',
    review_result VARCHAR(32) DEFAULT NULL COMMENT '评审结果：PENDING(待评审)/APPROVED(通过)/REJECTED(驳回)',
    review_comment TEXT DEFAULT NULL COMMENT '评审意见，评审人填写的详细意见',
    review_attachments VARCHAR(1024) DEFAULT NULL COMMENT '评审附件，JSON 数组格式（如 ["url1","url2"]），存储 MinIO 文件地址',
    review_order INT DEFAULT 0 COMMENT '评审顺序，用于多级评审排序（数字越小越先评审）',
    completed_at BIGINT DEFAULT NULL COMMENT '评审完成时间，Unix 时间戳（毫秒）',
    created_at BIGINT NOT NULL COMMENT '创建时间，Unix 时间戳（毫秒）',
    created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人 ID，关联 user_account 表',
    PRIMARY KEY (id),
    KEY idx_review_requirement (requirement_id) COMMENT '按需求 ID 查询评审记录索引',
    KEY idx_review_type (review_type) COMMENT '按评审类型查询索引',
    KEY idx_review_reviewer (reviewer_id) COMMENT '按评审人查询索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='需求评审记录表 — 记录多级评审流程';

-- 3. 需求任务表
CREATE TABLE IF NOT EXISTS requirement_task (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键，全局唯一标识',
    requirement_id VARCHAR(26) NOT NULL COMMENT '需求 ID，关联 requirement 表',
    task_code VARCHAR(64) DEFAULT NULL COMMENT '任务编号，格式 TASK-YYYY-NNN（如 TASK-2026-001）',
    title VARCHAR(256) NOT NULL COMMENT '任务标题，最长 256 个字符',
    description CLOB DEFAULT NULL COMMENT '任务描述，支持 Markdown 格式',
    assignee_id VARCHAR(26) DEFAULT NULL COMMENT '负责人 ID，关联 user_account 表',
    status VARCHAR(32) DEFAULT 'PENDING' COMMENT '任务状态：PENDING(待处理)/IN_PROGRESS(进行中)/COMPLETED(已完成)',
    estimated_hours INT DEFAULT NULL COMMENT '预估工时，单位：小时',
    actual_hours INT DEFAULT NULL COMMENT '实际工时，单位：小时',
    started_at BIGINT DEFAULT NULL COMMENT '开始时间，Unix 时间戳（毫秒）',
    completed_at BIGINT DEFAULT NULL COMMENT '完成时间，Unix 时间戳（毫秒）',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标志：0=未删除 1=已删除',
    created_at BIGINT NOT NULL COMMENT '创建时间，Unix 时间戳（毫秒）',
    updated_at BIGINT NOT NULL COMMENT '更新时间，Unix 时间戳（毫秒）',
    created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人 ID，关联 user_account 表',
    updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人 ID，关联 user_account 表',
    PRIMARY KEY (id),
    KEY idx_task_requirement (requirement_id) COMMENT '按需求 ID 查询任务列表索引',
    KEY idx_task_assignee (assignee_id) COMMENT '按负责人查询任务索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='需求任务表 — 需求拆分为具体开发任务';

-- 4. 需求评论表
CREATE TABLE IF NOT EXISTS requirement_comment (
    id VARCHAR(26) NOT NULL COMMENT 'ULID 主键，全局唯一标识',
    requirement_id VARCHAR(26) NOT NULL COMMENT '需求 ID，关联 requirement 表',
    content TEXT NOT NULL COMMENT '评论内容，支持 Markdown 格式',
    parent_id VARCHAR(26) DEFAULT NULL COMMENT '父评论 ID，支持嵌套回复，自关联',
    attachments VARCHAR(1024) DEFAULT NULL COMMENT '附件，JSON 数组格式（如 ["url1","url2"]），存储 MinIO 文件地址',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标志：0=未删除 1=已删除',
    created_at BIGINT NOT NULL COMMENT '创建时间，Unix 时间戳（毫秒）',
    updated_at BIGINT NOT NULL COMMENT '更新时间，Unix 时间戳（毫秒）',
    created_by VARCHAR(26) DEFAULT NULL COMMENT '创建人 ID，关联 user_account 表',
    updated_by VARCHAR(26) DEFAULT NULL COMMENT '更新人 ID，关联 user_account 表',
    PRIMARY KEY (id),
    KEY idx_comment_requirement (requirement_id) COMMENT '按需求 ID 查询评论列表索引',
    KEY idx_comment_parent (parent_id) COMMENT '按父评论查询回复索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='需求评论表 — 需求讨论和沟通记录';
