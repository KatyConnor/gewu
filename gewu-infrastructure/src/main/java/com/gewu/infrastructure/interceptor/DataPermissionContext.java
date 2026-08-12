package com.gewu.infrastructure.interceptor;

/**
 * 数据权限 ThreadLocal 上下文 - 在 DataPermissionAspect 进入方法时设置，方法返回后清除.
 * <p>DataPermissionInnerInterceptor 在 beforeQuery 时读取此上下文，决定是否追加 SQL 条件.
 */
public final class DataPermissionContext {

    private static final ThreadLocal<DataPermissionConfig> HOLDER = new ThreadLocal<>();

    public static void set(DataPermissionConfig config) {
        HOLDER.set(config);
    }

    public static DataPermissionConfig get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    private DataPermissionContext() {}
}
