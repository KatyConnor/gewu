package com.gewu.sandbox.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.common.ulid.Ulid;
import com.gewu.domain.sandbox.SandboxAuditLog;
import com.gewu.infrastructure.mapper.SandboxAuditLogMapper;
import com.gewu.sandbox.dto.SandboxAuditDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 沙箱审计服务.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SandboxAuditService {

    private final SandboxAuditLogMapper auditLogMapper;

    @Async
    public void logSandboxCreate(String sandboxId, String userId, String details) {
        logAction(sandboxId, "CREATE", userId, details);
    }

    @Async
    public void logSandboxStart(String sandboxId, String userId, String details) {
        logAction(sandboxId, "START", userId, details);
    }

    @Async
    public void logSandboxStop(String sandboxId, String userId, String details) {
        logAction(sandboxId, "STOP", userId, details);
    }

    @Async
    public void logSandboxDestroy(String sandboxId, String userId, String details) {
        logAction(sandboxId, "DESTROY", userId, details);
    }

    @Async
    public void logCommandExecution(String sandboxId, String userId, String command) {
        logAction(sandboxId, "COMMAND", userId, command);
    }

    @Async
    public void logFileAccess(String sandboxId, String userId, String filePath, String accessType) {
        logAction(sandboxId, "FILE_ACCESS", userId, accessType + ": " + filePath);
    }

    public List<SandboxAuditDTO> getAuditLogsBySandboxId(String sandboxId) {
        LambdaQueryWrapper<SandboxAuditLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SandboxAuditLog::getSandboxId, sandboxId)
               .orderByDesc(SandboxAuditLog::getTimestamp);
        return auditLogMapper.selectList(wrapper).stream()
            .map(this::toDTO)
            .toList();
    }

    public List<SandboxAuditDTO> getAllAuditLogs() {
        LambdaQueryWrapper<SandboxAuditLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(SandboxAuditLog::getTimestamp);
        return auditLogMapper.selectList(wrapper).stream()
            .map(this::toDTO)
            .toList();
    }

    private void logAction(String sandboxId, String action, String userId, String details) {
        try {
            SandboxAuditLog auditLog = new SandboxAuditLog();
            auditLog.setId(Ulid.next());
            auditLog.setSandboxId(sandboxId);
            auditLog.setAction(action);
            auditLog.setUserId(userId);
            auditLog.setDetails(details);
            auditLog.setTimestamp(Instant.now().toEpochMilli());
            auditLog.setCreatedAt(Instant.now().toEpochMilli());
            auditLogMapper.insert(auditLog);
        } catch (Exception e) {
            log.error("沙箱审计日志写入失败: sandboxId={}, action={}", sandboxId, action, e);
        }
    }

    private SandboxAuditDTO toDTO(SandboxAuditLog entity) {
        return SandboxAuditDTO.builder()
            .id(entity.getId())
            .sandboxId(entity.getSandboxId())
            .action(entity.getAction())
            .userId(entity.getUserId())
            .timestamp(entity.getTimestamp())
            .details(entity.getDetails())
            .build();
    }
}
