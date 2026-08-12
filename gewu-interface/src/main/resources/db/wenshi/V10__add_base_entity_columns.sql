-- ============================================================================
-- Wenshi 知识层 DDL V10 - 补齐 BaseEntity 审计字段
-- 目标数据库: PostgreSQL 16 + pgvector
-- 创建日期: 2026-08-01
-- ============================================================================
-- 说明: 所有 wenshi 领域实体均继承 BaseEntity，BaseEntity 包含
--       deleted (TableLogic)、createdAt、updatedAt、createdBy、updatedBy 五个字段。
--       V8 迁移创建表时遗漏了 deleted、created_by、updated_by 列，
--       导致 MyBatis-Plus 查询（自动追加 WHERE deleted = 0）和
--       PgvectorAdapter.upsert()（SQL 引用 created_by/updated_by）失败。
--       本迁移为 6 张 wenshi 表统一补齐缺失列。
-- ============================================================================

-- ============================================================================
-- 1. wenshi_semantic_fragment - 缺 deleted, created_by, updated_by
-- ============================================================================
ALTER TABLE wenshi_semantic_fragment ADD COLUMN IF NOT EXISTS deleted SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE wenshi_semantic_fragment ADD COLUMN IF NOT EXISTS created_by VARCHAR(26);
ALTER TABLE wenshi_semantic_fragment ADD COLUMN IF NOT EXISTS updated_by VARCHAR(26);
CREATE INDEX IF NOT EXISTS idx_wsf_deleted ON wenshi_semantic_fragment(deleted);

-- ============================================================================
-- 2. wenshi_episodic_event - 缺 deleted, updated_at, created_by, updated_by
-- ============================================================================
ALTER TABLE wenshi_episodic_event ADD COLUMN IF NOT EXISTS deleted SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE wenshi_episodic_event ADD COLUMN IF NOT EXISTS updated_at BIGINT;
ALTER TABLE wenshi_episodic_event ADD COLUMN IF NOT EXISTS created_by VARCHAR(26);
ALTER TABLE wenshi_episodic_event ADD COLUMN IF NOT EXISTS updated_by VARCHAR(26);
CREATE INDEX IF NOT EXISTS idx_wee_deleted ON wenshi_episodic_event(deleted);

-- ============================================================================
-- 3. wenshi_procedural_memory - 缺 deleted, created_by, updated_by
-- ============================================================================
ALTER TABLE wenshi_procedural_memory ADD COLUMN IF NOT EXISTS deleted SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE wenshi_procedural_memory ADD COLUMN IF NOT EXISTS created_by VARCHAR(26);
ALTER TABLE wenshi_procedural_memory ADD COLUMN IF NOT EXISTS updated_by VARCHAR(26);
CREATE INDEX IF NOT EXISTS idx_wpm_deleted ON wenshi_procedural_memory(deleted);

-- ============================================================================
-- 4. wenshi_experience - 缺 deleted, created_by, updated_by
-- ============================================================================
ALTER TABLE wenshi_experience ADD COLUMN IF NOT EXISTS deleted SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE wenshi_experience ADD COLUMN IF NOT EXISTS created_by VARCHAR(26);
ALTER TABLE wenshi_experience ADD COLUMN IF NOT EXISTS updated_by VARCHAR(26);
CREATE INDEX IF NOT EXISTS idx_we_deleted ON wenshi_experience(deleted);

-- ============================================================================
-- 5. wenshi_user_profile - 缺 deleted, created_at, created_by, updated_by
-- ============================================================================
ALTER TABLE wenshi_user_profile ADD COLUMN IF NOT EXISTS deleted SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE wenshi_user_profile ADD COLUMN IF NOT EXISTS created_at BIGINT;
ALTER TABLE wenshi_user_profile ADD COLUMN IF NOT EXISTS created_by VARCHAR(26);
ALTER TABLE wenshi_user_profile ADD COLUMN IF NOT EXISTS updated_by VARCHAR(26);
CREATE INDEX IF NOT EXISTS idx_wup_deleted ON wenshi_user_profile(deleted);

-- ============================================================================
-- 6. wenshi_reasoning_trace - 缺 deleted, updated_at, created_by, updated_by
-- ============================================================================
ALTER TABLE wenshi_reasoning_trace ADD COLUMN IF NOT EXISTS deleted SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE wenshi_reasoning_trace ADD COLUMN IF NOT EXISTS updated_at BIGINT;
ALTER TABLE wenshi_reasoning_trace ADD COLUMN IF NOT EXISTS created_by VARCHAR(26);
ALTER TABLE wenshi_reasoning_trace ADD COLUMN IF NOT EXISTS updated_by VARCHAR(26);
CREATE INDEX IF NOT EXISTS idx_wrt_deleted ON wenshi_reasoning_trace(deleted);
