package com.gewu.application.agent.adapter;

import com.gewu.agent.engine.spi.AuditService;
import com.gewu.infrastructure.audit.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * {@link AuditService} 业务适配 - 桥接框架与现有 {@link AuditLogService}。
 *
 * @since 1.0.0
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.engine.adapter.enabled", havingValue = "true")
public class DbAuditServiceAdapter implements AuditService {

    private final AuditLogService auditLogService;

    @Override
    public void recordToolExecution(String userId, String agentId, String toolName, boolean success, long durationMs) {
        auditLogService.recordOperation(userId, null,
                "TOOL_EXECUTE", "AGENT_TOOL", toolName, null, success, durationMs);
    }

    @Override
    public void recordAgentExecution(String userId, String agentId, String sessionId, boolean success, long durationMs) {
        auditLogService.recordOperation(userId, null,
                "AGENT_EXECUTE", "AGENT", agentId, sessionId, success, durationMs);
    }
}