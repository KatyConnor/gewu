-- 阶段 D：数据权限 - role 表增加 data_scope 字段
-- data_scope: 1=全部 2=本部门 3=本部门及以下 4=本人

ALTER TABLE role ADD COLUMN data_scope TINYINT DEFAULT 4 COMMENT '数据范围 1全部 2本部门 3本部门及以下 4本人';

-- 种子：ADMIN 全部数据，USER 本人数据
UPDATE role SET data_scope = 1 WHERE role_code = 'ADMIN';
UPDATE role SET data_scope = 4 WHERE role_code = 'USER';
