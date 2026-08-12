package com.gewu.infrastructure.interceptor;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 数据权限运行时配置 - 由 DataPermissionAspect 写入 ThreadLocal，供 DataPermissionInnerInterceptor 读取.
 */
@Data
@AllArgsConstructor
public class DataPermissionConfig {

    /** 数据归属字段名（如 created_by） */
    private String orgField;

    /** 表别名，空字符串表示不加别名前缀 */
    private String orgAlias;
}
