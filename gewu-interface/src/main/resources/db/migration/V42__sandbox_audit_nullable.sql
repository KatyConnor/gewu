-- S9：sandbox_audit_log 实体与表漂移对齐（第二层）。
-- 实体仅写入 id/sandbox_id/user_id/action/details/timestamp/created_at，
-- 而 resource/result 为 NOT NULL 无默认值 → 每条审计插入必败（1364）。
-- 审计表以可追溯为先，放宽未采集字段；user_id 允许空（沙箱内部线程无 UserContext）。
ALTER TABLE sandbox_audit_log MODIFY resource VARCHAR(64) DEFAULT NULL COMMENT '操作资源';
ALTER TABLE sandbox_audit_log MODIFY result VARCHAR(16) DEFAULT NULL COMMENT '执行结果';
ALTER TABLE sandbox_audit_log MODIFY user_id VARCHAR(26) DEFAULT NULL COMMENT '操作用户';
