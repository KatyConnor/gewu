package com.veloflow.engine.commons;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;

import java.time.Instant;

/**
 * 引擎默认审计填充（发布套件）：created_at/updated_at/created_by/updated_by/deleted。
 * <p>BaseEntity 各列依赖 MyBatis-Plus 字段填充——宿主已配置自己的
 * {@link MetaObjectHandler} 时本默认实现让位（@ConditionalOnMissingBean 于自动装配注册），
 * 未配置的宿主零配置可用（V64 schedule/webhook 表漏列教训的根治）。
 *
 * @since 1.0.0
 */
public class VeloflowMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        long now = Instant.now().toEpochMilli();
        strictInsertFill(metaObject, "createdAt", Long.class, now);
        strictInsertFill(metaObject, "updatedAt", Long.class, now);
        strictInsertFill(metaObject, "deleted", Integer.class, 0);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updatedAt", Long.class, Instant.now().toEpochMilli());
    }
}
