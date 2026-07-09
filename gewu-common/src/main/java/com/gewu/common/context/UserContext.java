package com.gewu.common.context;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Set;

/**
 * 当前登录用户上下文 — 通过 ThreadLocal 在请求生命周期内传递.
 */
@Data
@Builder
public class UserContext {

    private String userId;
    private String username;
    private String displayName;
    private String avatarUrl;
    private List<String> roleCodes;
    private Set<String> permissions;
    private String ipAddress;
    private String userAgent;
    private String token;

    private static final ThreadLocal<UserContext> HOLDER = new ThreadLocal<>();

    public static void set(UserContext context) {
        HOLDER.set(context);
    }

    public static UserContext get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static boolean isLogin() {
        return HOLDER.get() != null;
    }

    public static String currentUserId() {
        UserContext ctx = HOLDER.get();
        return ctx != null ? ctx.getUserId() : null;
    }

    public static String currentUsername() {
        UserContext ctx = HOLDER.get();
        return ctx != null ? ctx.getUsername() : null;
    }

    public boolean hasPermission(String permissionCode) {
        return permissions != null && permissions.contains(permissionCode);
    }

    public boolean hasRole(String roleCode) {
        return roleCodes != null && roleCodes.contains(roleCode);
    }
}