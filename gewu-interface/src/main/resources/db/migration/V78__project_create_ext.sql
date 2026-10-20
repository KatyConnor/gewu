-- 新建项目表单扩展：项目简称/所属系统/团队成员/技术负责人 + 项目级仓库访问凭证

ALTER TABLE project ADD COLUMN short_name VARCHAR(64) DEFAULT NULL COMMENT '项目简称';
ALTER TABLE project ADD COLUMN system_name VARCHAR(128) DEFAULT NULL COMMENT '所属系统';
ALTER TABLE project ADD COLUMN team_members VARCHAR(512) DEFAULT NULL COMMENT '团队成员(逗号分隔)';
ALTER TABLE project ADD COLUMN tech_lead VARCHAR(64) DEFAULT NULL COMMENT '技术负责人';

-- 项目级仓库凭证：repo_credential 为 SM4 加密内容（访问令牌 / SSH 私钥 / 密码），
-- 类型由 repo_credential_type 区分；password 型配套 repo_username。均不出 DTO。
ALTER TABLE project ADD COLUMN repo_credential_type VARCHAR(16) DEFAULT NULL COMMENT '仓库凭证类型: token/ssh_key/password';
ALTER TABLE project ADD COLUMN repo_username VARCHAR(128) DEFAULT NULL COMMENT '仓库用户名(repo_credential_type=password 时)';
ALTER TABLE project ADD COLUMN repo_credential TEXT DEFAULT NULL COMMENT '仓库访问凭证(SM4加密: 令牌/SSH私钥/密码)';
