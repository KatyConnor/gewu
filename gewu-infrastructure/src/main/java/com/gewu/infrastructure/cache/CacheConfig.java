package com.gewu.infrastructure.cache;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 缓存配置 — 集中管理缓存名称与 TTL.
 */
@Configuration
public class CacheConfig {

    public static final String CACHE_USER = "userCache";
    public static final String CACHE_PROJECT = "projectCache";
    public static final String CACHE_SESSION = "sessionCache";
    public static final String CACHE_AGENT = "agentCache";
    public static final String CACHE_WORKFLOW = "workflowCache";
    public static final String CACHE_PERMISSION = "permissionCache";

    @Value("${gewu.performance.cache.user-ttl:1800000}")
    private long userTtl;

    @Value("${gewu.performance.cache.project-ttl:1800000}")
    private long projectTtl;

    @Value("${gewu.performance.cache.session-ttl:600000}")
    private long sessionTtl;

    @Value("${gewu.performance.cache.agent-ttl:3600000}")
    private long agentTtl;

    @Value("${gewu.performance.cache.workflow-ttl:3600000}")
    private long workflowTtl;

    @Value("${gewu.performance.cache.permission-ttl:7200000}")
    private long permissionTtl;

    public long ttlFor(String cacheName) {
        return switch (cacheName) {
            case CACHE_USER -> userTtl;
            case CACHE_PROJECT -> projectTtl;
            case CACHE_SESSION -> sessionTtl;
            case CACHE_AGENT -> agentTtl;
            case CACHE_WORKFLOW -> workflowTtl;
            case CACHE_PERMISSION -> permissionTtl;
            default -> 300000L;
        };
    }
}