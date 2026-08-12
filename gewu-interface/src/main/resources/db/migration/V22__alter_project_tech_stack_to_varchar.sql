-- 将 tech_stack 从 JSON 类型改为 VARCHAR，匹配实际使用方式（逗号分隔纯文本）
ALTER TABLE project MODIFY COLUMN tech_stack VARCHAR(512) DEFAULT NULL COMMENT '技术栈配置';
