-- 托管平台协作适配层 P2：项目级托管平台显式配置（自建实例域名不可枚举，需显式指定）
-- platform_type: github/gitlab/gitee/gitea（NULL=按 repoUrl 域名自动推断）
-- api_base: 自建实例 REST API 根地址（NULL=按平台默认推导）
ALTER TABLE project ADD COLUMN platform_type VARCHAR(20) NULL;
ALTER TABLE project ADD COLUMN api_base VARCHAR(200) NULL;
