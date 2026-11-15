-- V76: 实例乐观锁（P4-B 多副本前置——跨副本并发完成/暂停/终止的版本 CAS 守护）
-- 依赖：主库 MybatisPlusInterceptor 已注册 OptimisticLockerInnerInterceptor；
-- veloflow 独立部署形态（mapper-scan.enabled=true）为单实例部署，版本列存在但 CAS 不生效（无拦截器），行为不变。
ALTER TABLE VLF_WORKFLOW_INSTANCE
    ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本（MP @Version，多副本并发守护）';
