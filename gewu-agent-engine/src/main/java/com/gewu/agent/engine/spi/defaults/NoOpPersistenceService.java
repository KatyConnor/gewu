package com.gewu.agent.engine.spi.defaults;

import com.gewu.agent.engine.spi.AgentSpec;
import com.gewu.agent.engine.spi.ExecutionRecord;
import com.gewu.agent.engine.spi.PersistenceService;
import com.gewu.agent.engine.spi.ToolConfig;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link PersistenceService} 的 NoOp 默认实现 - 内存存储。
 * <p>仅用于框架开箱即用与测试。生产环境应由使用方提供数据库实现。
 *
 * @since 1.0.0
 */
@Slf4j
public class NoOpPersistenceService implements PersistenceService {

    private final Map<String, AgentSpec> agents = new ConcurrentHashMap<>();
    private final Map<String, ExecutionRecord> executions = new ConcurrentHashMap<>();

    @Override
    public AgentSpec loadAgent(String agentId) {
        return agents.get(agentId);
    }

    @Override
    public List<ToolConfig> loadAgentTools(String agentId) {
        return List.of();
    }

    @Override
    public ExecutionRecord createExecution(String agentId, String sessionId, String userId, String input) {
        String id = UUID.randomUUID().toString();
        ExecutionRecord record = ExecutionRecord.builder()
                .id(id)
                .agentId(agentId)
                .sessionId(sessionId)
                .userId(userId)
                .status("running")
                .input(input)
                .startedAt(Instant.now().toEpochMilli())
                .build();
        executions.put(id, record);
        log.debug("[NoOp] 创建执行记录: id={}, agentId={}", id, agentId);
        return record;
    }

    @Override
    public void completeExecution(String executionId, String output, Integer tokensUsed) {
        ExecutionRecord record = executions.get(executionId);
        if (record != null) {
            long now = Instant.now().toEpochMilli();
            record.setStatus("completed");
            record.setOutput(output);
            record.setTokensUsed(tokensUsed);
            record.setCompletedAt(now);
            record.setDurationMs(record.getStartedAt() != null ? (int) (now - record.getStartedAt()) : null);
        }
    }

    @Override
    public void failExecution(String executionId, String errorMessage) {
        ExecutionRecord record = executions.get(executionId);
        if (record != null) {
            long now = Instant.now().toEpochMilli();
            record.setStatus("failed");
            record.setErrorMessage(errorMessage);
            record.setCompletedAt(now);
            record.setDurationMs(record.getStartedAt() != null ? (int) (now - record.getStartedAt()) : null);
        }
    }

    @Override
    public ExecutionRecord getExecution(String executionId) {
        return executions.get(executionId);
    }

    /** 测试辅助：注册一个内存 Agent */
    public void registerAgent(AgentSpec spec) {
        agents.put(spec.getId(), spec);
    }
}
