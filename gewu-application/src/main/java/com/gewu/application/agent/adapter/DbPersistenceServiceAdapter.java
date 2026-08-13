package com.gewu.application.agent.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.agent.engine.spi.AgentSpec;
import com.gewu.agent.engine.spi.ExecutionRecord;
import com.gewu.agent.engine.spi.PersistenceService;
import com.gewu.agent.engine.spi.ToolConfig;
import com.gewu.domain.agent.Agent;
import com.gewu.domain.agent.AgentExecution;
import com.gewu.domain.agent.AgentTool;
import com.gewu.infrastructure.mapper.AgentExecutionMapper;
import com.gewu.infrastructure.mapper.AgentMapper;
import com.gewu.infrastructure.mapper.AgentToolMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@link PersistenceService} 的业务侧适配实现 - 桥接框架与格物平台数据库。
 * <p>将框架的 AgentSpec / ToolConfig / ExecutionRecord 读写转换为对 Agent / AgentTool / AgentExecution 表的操作。
 * 默认不启用，通过 {@code agent.engine.adapter.enabled=true} 开启，避免与现有逻辑冲突。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.engine.adapter.enabled", havingValue = "true")
public class DbPersistenceServiceAdapter implements PersistenceService {

    private final AgentMapper agentMapper;
    private final AgentToolMapper agentToolMapper;
    private final AgentExecutionMapper agentExecutionMapper;

    @Override
    public AgentSpec loadAgent(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return null;
        }
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            return null;
        }
        return AgentSpec.builder()
                .id(agent.getId())
                .name(agent.getAgentName())
                .description(agent.getDescription())
                .modelProvider(agent.getModelProvider())
                .modelName(agent.getModelName())
                .modelConfig(agent.getModelConfig())
                .systemPrompt(agent.getSystemPrompt())
                .status(agent.getStatus())
                .build();
    }

    @Override
    public List<ToolConfig> loadAgentTools(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return List.of();
        }
        List<AgentTool> tools = agentToolMapper.selectList(
                new LambdaQueryWrapper<AgentTool>()
                        .eq(AgentTool::getAgentId, agentId)
                        .eq(AgentTool::getStatus, 1)
                        .orderByAsc(AgentTool::getSortOrder));

        return tools.stream()
                .map(tool -> ToolConfig.builder()
                        .toolName(tool.getToolName())
                        .description(tool.getDescription())
                        .requestSchema(tool.getRequestSchema())
                        .toolType(tool.getToolType())
                        .endpoint(tool.getEndpoint())
                        .mcpServerId(tool.getMcpServerId())
                        .timeoutMs(tool.getTimeoutMs())
                        .sortOrder(tool.getSortOrder())
                        .status(tool.getStatus())
                        .build())
                .toList();
    }

    @Override
    public ExecutionRecord createExecution(String agentId, String sessionId, String userId, String input) {
        AgentExecution exec = new AgentExecution();
        exec.setAgentId(agentId);
        exec.setSessionId(sessionId);
        exec.setUserId(userId);
        exec.setStatus("running");
        exec.setInput(input);
        exec.setStartedAt(Instant.now().toEpochMilli());
        agentExecutionMapper.insert(exec);

        return ExecutionRecord.builder()
                .id(exec.getId())
                .agentId(agentId)
                .sessionId(sessionId)
                .userId(userId)
                .status("running")
                .input(input)
                .startedAt(exec.getStartedAt())
                .build();
    }

    @Override
    public void completeExecution(String executionId, String output, Integer tokensUsed) {
        AgentExecution exec = agentExecutionMapper.selectById(executionId);
        if (exec == null) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        exec.setStatus("completed");
        exec.setOutput(output);
        exec.setTokensUsed(tokensUsed);
        exec.setCompletedAt(now);
        exec.setDurationMs(exec.getStartedAt() != null ? (int) (now - exec.getStartedAt()) : null);
        agentExecutionMapper.updateById(exec);
    }

    @Override
    public void failExecution(String executionId, String errorMessage) {
        AgentExecution exec = agentExecutionMapper.selectById(executionId);
        if (exec == null) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        exec.setStatus("failed");
        exec.setErrorMessage(errorMessage);
        exec.setCompletedAt(now);
        exec.setDurationMs(exec.getStartedAt() != null ? (int) (now - exec.getStartedAt()) : null);
        agentExecutionMapper.updateById(exec);
    }

    @Override
    public ExecutionRecord getExecution(String executionId) {
        AgentExecution exec = agentExecutionMapper.selectById(executionId);
        if (exec == null) {
            return null;
        }
        return ExecutionRecord.builder()
                .id(exec.getId())
                .agentId(exec.getAgentId())
                .sessionId(exec.getSessionId())
                .userId(exec.getUserId())
                .status(exec.getStatus())
                .input(exec.getInput())
                .output(exec.getOutput())
                .errorMessage(exec.getErrorMessage())
                .tokensUsed(exec.getTokensUsed())
                .startedAt(exec.getStartedAt())
                .completedAt(exec.getCompletedAt())
                .durationMs(exec.getDurationMs())
                .build();
    }
}