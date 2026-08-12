package com.gewu.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据权限注解 - 标注在 Service 方法上，由 DataPermissionAspect 拦截后
 * 经 DataPermissionInnerInterceptor 按当前用户角色的 data_scope 自动追加 SQL 条件.
 *
 * <p>data_scope 取值：
 * <ul>
 *   <li>1 全部 — 不加条件</li>
 *   <li>2 本部门 — created_by IN (SELECT id FROM user_account WHERE org_id = ?)</li>
 *   <li>3 本部门及以下 — created_by IN (本部门 + 子部门用户)</li>
 *   <li>4 本人 — created_by = ?</li>
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DataPermission {

    /** 数据归属字段名（默认 created_by） */
    String orgField() default "created_by";

    /** 表别名，空字符串表示不加别名前缀 */
    String orgAlias() default "";
}
