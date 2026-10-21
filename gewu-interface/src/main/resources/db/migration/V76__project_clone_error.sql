-- V76: 项目 clone 失败原因回传（IDEA 式工作台改造，用户实报 clone failed 无详情）
-- 两条克隆链路同修：project（ProjectRepoService）与 workspace_project（开发空间页面）

ALTER TABLE project
    ADD COLUMN clone_error VARCHAR(500) DEFAULT NULL COMMENT '最近一次clone失败的stderr摘要（成功时清空）';

ALTER TABLE workspace_project
    ADD COLUMN clone_error VARCHAR(500) DEFAULT NULL COMMENT '最近一次clone失败的stderr摘要（成功时清空）';
