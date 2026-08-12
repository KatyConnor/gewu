package com.gewu.common.util;

import com.gewu.common.context.UserContext;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;

/**
 * 权限检查工具类.
 */
public final class PermissionChecker {

    private PermissionChecker() {}

    /**
     * 检查当前用户是否有指定权限.
     */
    public static void checkPermission(String permission) {
        UserContext ctx = UserContext.get();
        if (ctx == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        if (!ctx.hasPermission(permission)) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "无权限执行此操作：" + permission);
        }
    }

    /**
     * 检查当前用户是否有指定角色.
     */
    public static void checkRole(String roleCode) {
        UserContext ctx = UserContext.get();
        if (ctx == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        if (!ctx.hasRole(roleCode)) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "需要角色：" + roleCode);
        }
    }

    /**
     * 检查当前用户是否有任一指定角色.
     */
    public static void checkAnyRole(String... roleCodes) {
        UserContext ctx = UserContext.get();
        if (ctx == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        for (String roleCode : roleCodes) {
            if (ctx.hasRole(roleCode)) {
                return;
            }
        }
        throw BusinessException.of(ResultCode.FORBIDDEN, "需要以下角色之一：" + String.join(", ", roleCodes));
    }

    /**
     * 检查需求操作权限（创建人或有编辑权限）.
     */
    public static void checkRequirementEditPermission(String createdBy) {
        UserContext ctx = UserContext.get();
        if (ctx == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        // 创建人可以编辑
        if (ctx.getUserId().equals(createdBy)) {
            return;
        }
        // 或有编辑权限
        if (ctx.hasPermission("requirement:edit")) {
            return;
        }
        throw BusinessException.of(ResultCode.FORBIDDEN, "无权限编辑此需求");
    }
}
