package com.gewu.common.constant;

/**
 * 平台通用常量.
 */
public final class CommonConstants {

    private CommonConstants() {}

    public static final String PLATFORM_NAME = "格物平台";

    public static final String API_PREFIX = "/api/v1";
    public static final String AUTH_HEADER = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";
    public static final String REFRESH_TOKEN_HEADER = "X-Refresh-Token";

    public static final int MAX_LOGIN_FAIL_COUNT = 5;
    public static final long LOCK_DURATION_MS = 30 * 60 * 1000L;

    public static final long ACCESS_TOKEN_EXPIRATION_MS = 30 * 60 * 1000L;
    public static final long REFRESH_TOKEN_EXPIRATION_MS = 7 * 24 * 60 * 60 * 1000L;

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    public static final String CACHE_USER_PREFIX = "gewu:user:";
    public static final String CACHE_PERMISSION_PREFIX = "gewu:perm:";
    public static final String CACHE_ROLE_PREFIX = "gewu:role:";
    public static final String CACHE_LOCK_PREFIX = "gewu:lock:";

    public static final String MQ_TOPIC_SESSION = "gewu-session";
    public static final String MQ_TOPIC_AGENT = "gewu-agent";
    public static final String MQ_TOPIC_WORKFLOW = "gewu-workflow";
    public static final String MQ_TOPIC_AUDIT = "gewu-audit";

    public static final String SANDBOX_AUDIT_RETENTION_DAYS = "365";
    public static final String SECURITY_AUDIT_RETENTION_DAYS = "1095";

    public static final String SALT = "gewu-salt-2026";
}