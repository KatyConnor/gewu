package com.gewu.infrastructure.audit;

import com.gewu.common.context.AuditContextHelper;
import com.gewu.common.context.UserContext;
import com.gewu.domain.audit.AuditLog;
import com.gewu.infrastructure.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * 审计日志切面 — 拦截 @AuditLog 标注的方法，记录操作人、操作类型、资源、IP、耗时与成败结果.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditLogAspect {

    private final AuditLogMapper auditLogMapper;
    private final AuditContextHelper auditContextHelper;

    @Around("@annotation(auditLogAnnotation)")
    public Object aroundAuditLog(ProceedingJoinPoint joinPoint,
                                 com.gewu.common.annotation.AuditLog auditLogAnnotation) throws Throwable {
        long start = System.currentTimeMillis();
        String errorMessage = null;
        boolean success = true;
        try {
            return joinPoint.proceed();
        } catch (Throwable e) {
            success = false;
            errorMessage = e.getMessage();
            throw e;
        } finally {
            long duration = System.currentTimeMillis() - start;
            try {
                record(joinPoint, auditLogAnnotation, duration, success, errorMessage);
            } catch (Exception e) {
                log.error("审计日志记录异常: action={}", auditLogAnnotation.actionType(), e);
            }
        }
    }

    public void record(ProceedingJoinPoint joinPoint, com.gewu.common.annotation.AuditLog annotation,
                       long duration, boolean success, String errorMessage) {
        UserContext userContext = UserContext.get();
        AuditLog entity = new AuditLog();
        entity.setActionType(annotation.actionType());
        entity.setResourceType(annotation.resourceType());
        entity.setIpAddress(auditContextHelper.getClientIp());
        entity.setUserAgent(auditContextHelper.getUserAgent());
        entity.setDurationMs((int) duration);
        entity.setResult(success ? 1 : 0);
        if (errorMessage != null) {
            entity.setDetail(annotation.description() + " | 错误: " + errorMessage);
        } else {
            entity.setDetail(annotation.description());
        }
        entity.setResourceId(extractResourceId(joinPoint, annotation));
        if (userContext != null) {
            entity.setUserId(userContext.getUserId());
            entity.setUsername(userContext.getUsername());
        }
        auditLogMapper.insert(entity);
    }

    private String extractResourceId(ProceedingJoinPoint joinPoint, com.gewu.common.annotation.AuditLog annotation) {
        if (annotation.resourceType().isEmpty()) return null;
        Object[] args = joinPoint.getArgs();
        for (Object arg : args) {
            if (arg instanceof String str && !str.isEmpty()) {
                return str;
            }
        }
        return null;
    }
}