-- 技能发布审核状态：0=私有 1=待审核 2=已发布 3=已拒绝
ALTER TABLE skill ADD COLUMN publish_status TINYINT DEFAULT 0 COMMENT '0=私有 1=待审核 2=已发布 3=已拒绝';
