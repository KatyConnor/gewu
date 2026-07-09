package com.gewu.application.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gewu.application.agent.dto.AgentExecutionDTO;
import com.gewu.common.context.UserContext;
import com.gewu.common.dto.PageQuery;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.PageResult;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.agent.Agent;
import com.gewu.domain.agent.AgentExecution;
import com.gewu.infrastructure.mapper.AgentExecutionMapper;
import com.gewu.infrastructure.mapper.AgentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentExecutionService {

    private final AgentExecutionMapper agentExecutionMapper;
    private final AgentMapper agentMapper;

    @Transactional
    public AgentExecutionDTO createExecution(String agentId, String sessionId, String input) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw BusinessException.of(ResultCode.AGENT_NOT_FOUND);
        }

        AgentExecution execution = new AgentExecution();
        execution.setAgentId(agentId);
        execution.setSessionId(sessionId);
        execution.setUserId(UserContext.currentUserId());
        execution.setStatus("running");
        execution.setInput(input);
        execution.setStartedAt(Instant.now().toEpochMilli());
        agentExecutionMapper.insert(execution);
        return toDTO(execution);
    }

    @Transactional
    public AgentExecutionDTO completeExecution(String executionId, String output, Integer tokensUsed) {
        AgentExecution execution = agentExecutionMapper.selectById(executionId);
        if (execution == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "执行记录不存在");
        }

        long now = Instant.now().toEpochMilli();
        execution.setStatus("completed");
        execution.setOutput(output);
        execution.setTokensUsed(tokensUsed);
        execution.setCompletedAt(now);
        execution.setDurationMs(execution.getStartedAt() != null
                ? (int) (now - execution.getStartedAt()) : null);
        agentExecutionMapper.updateById(execution);
        return toDTO(execution);
    }

    @Transactional
    public AgentExecutionDTO failExecution(String executionId, String errorMessage) {
        AgentExecution execution = agentExecutionMapper.selectById(executionId);
        if (execution == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "执行记录不存在");
        }

        long now = Instant.now().toEpochMilli();
        execution.setStatus("failed");
        execution.setErrorMessage(errorMessage);
        execution.setCompletedAt(now);
        execution.setDurationMs(execution.getStartedAt() != null
                ? (int) (now - execution.getStartedAt()) : null);
        agentExecutionMapper.updateById(execution);
        return toDTO(execution);
    }

    public AgentExecutionDTO getExecution(String executionId) {
        AgentExecution execution = agentExecutionMapper.selectById(executionId);
        if (execution == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "执行记录不存在");
        }
        return toDTO(execution);
    }

    public PageResult<AgentExecutionDTO> listExecutions(String agentId, PageQuery query) {
        Page<AgentExecution> page = new Page<>(query.getPage(), query.getSize());
        Page<AgentExecution> result = agentExecutionMapper.selectPage(page,
                new LambdaQueryWrapper<AgentExecution>()
                        .eq(AgentExecution::getAgentId, agentId)
                        .orderByDesc(AgentExecution::getStartedAt));

        List<AgentExecutionDTO> dtos = result.getRecords().stream()
                .map(this::toDTO)
                .toList();
        return PageResult.of(dtos, result.getTotal(), query.getPage(), query.getSize());
    }

    public PageResult<AgentExecutionDTO> listUserExecutions(PageQuery query) {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }

        Page<AgentExecution> page = new Page<>(query.getPage(), query.getSize());
        Page<AgentExecution> result = agentExecutionMapper.selectPage(page,
                new LambdaQueryWrapper<AgentExecution>()
                        .eq(AgentExecution::getUserId, userId)
                        .orderByDesc(AgentExecution::getStartedAt));

        List<AgentExecutionDTO> dtos = result.getRecords().stream()
                .map(this::toDTO)
                .toList();
        return PageResult.of(dtos, result.getTotal(), query.getPage(), query.getSize());
    }

    private AgentExecutionDTO toDTO(AgentExecution execution) {
        return AgentExecutionDTO.builder()
                .executionId(execution.getId())
                .agentId(execution.getAgentId())
                .sessionId(execution.getSessionId())
                .userId(execution.getUserId())
                .status(execution.getStatus())
                .input(execution.getInput())
                .output(execution.getOutput())
                .errorMessage(execution.getErrorMessage())
                .tokensUsed(execution.getTokensUsed())
                .startedAt(execution.getStartedAt())
                .completedAt(execution.getCompletedAt())
                .durationMs(execution.getDurationMs())
                .build();
    }
}
