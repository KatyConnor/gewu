package com.gewu.agent.engine.spi.defaults;

import com.gewu.agent.engine.spi.AuditService;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link AuditService} 的 NoOp 默认实现 - 仅日志输出。
 *
 * @since 1.0.0
 */
@Slf4j
public class NoOpAuditService implements AuditService {

    @Override
    public void recordToolExecution(String userId, String agentId, String toolName, boolean success, long durationMs) {
        log.debug("[NoOp Audit] 工具执行: user={}, agent={}, tool={}, success={}, duration={}ms",
                userId, agentId, toolName, success, durationMs);
    }

    @Override
    public void recordAgentExecution(String userId, String agentId, String sessionId, boolean success, long durationMs) {
        log.debug("[NoOp Audit] Agent 执行: user={}, agent={}, session={}, success={}, duration={}ms",
                userId, agentId, sessionId, success, durationMs);
    }
}
