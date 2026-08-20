package com.gewu.application.agent.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.spi.AuditService;
import com.gewu.application.audit.AuditChainService;
import com.gewu.infrastructure.audit.AuditLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * {@link AuditService} 业务适配 - 桥接框架与现有 {@link AuditLogService}，
 * 并将 Agent/工具执行同步追加到 WORM 审计链（{@link AuditChainService}）。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "agent.engine.adapter.enabled", havingValue = "true")
public class DbAuditServiceAdapter implements AuditService {

    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    /** WORM 审计链（可选注入，缺失时仅走普通审计日志） */
    @Autowired(required = false)
    private AuditChainService auditChainService;

    public DbAuditServiceAdapter(AuditLogService auditLogService, ObjectMapper objectMapper) {
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void recordToolExecution(String userId, String agentId, String toolName, boolean success, long durationMs) {
        auditLogService.recordOperation(userId, null,
                "TOOL_EXECUTE", "AGENT_TOOL", toolName, null, success, durationMs);
        appendChain("TOOL_EXECUTE", agentId, userId, "execute", Map.of(
                "toolName", toolName, "success", success, "durationMs", durationMs));
    }

    @Override
    public void recordAgentExecution(String userId, String agentId, String sessionId, boolean success, long durationMs) {
        auditLogService.recordOperation(userId, null,
                "AGENT_EXECUTE", "AGENT", agentId, sessionId, success, durationMs);
        appendChain("AGENT_EXECUTE", agentId, userId, "execute", Map.of(
                "sessionId", sessionId != null ? sessionId : "", "success", success, "durationMs", durationMs));
    }

    /**
     * 追加到 WORM 审计链（链式 SHA-256 哈希，不可篡改）。
     * 失败不阻断主流程（审计链写入失败仅记录日志）。
     */
    private void appendChain(String eventType, String executionId, String actor,
                             String action, Map<String, Object> payload) {
        if (auditChainService == null) return;
        try {
            String decisionTrace = objectMapper.writeValueAsString(payload);
            auditChainService.append(eventType, executionId,
                    actor != null ? actor : "system", action, decisionTrace);
        } catch (Exception e) {
            log.warn("WORM 审计链追加失败（不阻断主流程）: eventType={}, error={}", eventType, e.getMessage());
        }
    }
}