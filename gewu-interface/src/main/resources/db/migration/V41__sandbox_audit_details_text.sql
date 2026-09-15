-- S9：sandbox_audit_log.details 列类型纠正（JSON → TEXT）。
-- 该列由 logAudit 写入自由文本（如「创建沙箱 source=dev」「执行命令: ls -1 …」），
-- JSON 类型导致每条审计插入必败（Invalid JSON text），审计功能形同虚设。
ALTER TABLE sandbox_audit_log MODIFY details TEXT DEFAULT NULL COMMENT '操作详情 (命令内容/文件路径等)';
