package com.gewu.infrastructure.cache;

import org.springframework.stereotype.Component;

/**
 * 缓存键构建器 — 统一缓存键命名空间.
 */
@Component
public class CacheKeys {

    public static String user(String userId) {
        return "gewu:user:info:" + userId;
    }

    public static String userPermissions(String userId) {
        return "gewu:user:perms:" + userId;
    }

    public static String userRoles(String userId) {
        return "gewu:user:roles:" + userId;
    }

    public static String project(String projectId) {
        return "gewu:project:info:" + projectId;
    }

    public static String session(String sessionId) {
        return "gewu:session:info:" + sessionId;
    }

    public static String agent(String agentId) {
        return "gewu:agent:info:" + agentId;
    }

    public static String workflow(String workflowId) {
        return "gewu:workflow:info:" + workflowId;
    }

    public static String messages(String sessionId) {
        return "gewu:session:messages:" + sessionId;
    }

    public static String onlineUsers() {
        return "gewu:online:users";
    }
}