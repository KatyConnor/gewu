package com.gewu.infrastructure.audit;

import com.gewu.common.ulid.Ulid;
import com.gewu.domain.audit.AuditLog;
import com.gewu.infrastructure.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 审计日志服务 — 记录登录、登出、操作及安全事件，落库留存以满足等保2.0 三级可追溯要求.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private static final Integer RESULT_SUCCESS = 1;
    private static final Integer RESULT_FAILURE = 0;

    private final AuditLogMapper auditLogMapper;

    @Async
    public void recordLogin(String userId, String username, String ip, boolean success) {
        AuditLog auditLog = baseAuditLog(userId, username, ip);
        auditLog.setActionType("LOGIN");
        auditLog.setResourceType("USER");
        auditLog.setResourceId(userId);
        auditLog.setResult(success ? RESULT_SUCCESS : RESULT_FAILURE);
        auditLog.setDetail(success ? "登录成功" : "登录失败");
        save(auditLog);
    }

    @Async
    public void recordLogout(String userId, String username, String ip) {
        AuditLog auditLog = baseAuditLog(userId, username, ip);
        auditLog.setActionType("LOGOUT");
        auditLog.setResourceType("USER");
        auditLog.setResourceId(userId);
        auditLog.setDetail("用户登出");
        save(auditLog);
    }

    @Async
    public void recordOperation(String userId, String username, String actionType, String resourceType,
                                String resourceId, String ip, boolean success, Long duration) {
        AuditLog auditLog = baseAuditLog(userId, username, ip);
        auditLog.setActionType(actionType);
        auditLog.setResourceType(resourceType);
        auditLog.setResourceId(resourceId);
        auditLog.setResult(success ? RESULT_SUCCESS : RESULT_FAILURE);
        if (duration != null) {
            auditLog.setDurationMs(duration.intValue());
        }
        save(auditLog);
    }

    @Async
    public void recordSecurityEvent(String eventType, String userId, String description, String ip) {
        AuditLog auditLog = baseAuditLog(userId, null, ip);
        auditLog.setActionType(eventType);
        auditLog.setResourceType("SECURITY");
        auditLog.setResult(RESULT_SUCCESS);
        auditLog.setDetail(description);
        save(auditLog);
    }

    private AuditLog baseAuditLog(String userId, String username, String ip) {
        AuditLog auditLog = new AuditLog();
        auditLog.setId(Ulid.next());
        auditLog.setUserId(userId);
        auditLog.setUsername(username);
        auditLog.setIpAddress(ip);
        return auditLog;
    }

    private void save(AuditLog auditLog) {
        try {
            auditLogMapper.insert(auditLog);
        } catch (Exception e) {
            log.error("审计日志写入失败: action={}, user={}", auditLog.getActionType(), auditLog.getUserId(), e);
        }
    }
}