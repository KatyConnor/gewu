package com.gewu.application.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gewu.application.agent.dto.AgentExecutionDTO;
import com.gewu.common.context.UserContext;
import com.gewu.common.dto.PageQuery;
import com.gewu.domain.agent.enums.AgentExecutionStatus;
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

/**
 * 智能体执行记录应用服务，负责 Agent 执行生命周期的落库与查询。
 *
 * <p>职责：创建执行记录（支持 A/B 实验分组）、完成/失败状态流转（记录输出、耗时与 token 消耗）、
 * 按 Agent 分页查询（仅创建者或 ADMIN 可见）与按当前用户分页查询。
 * 底层经由 AgentExecutionMapper / AgentMapper（MyBatis-Plus）访问持久化数据。
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-07-09
 */

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentExecutionService {

    private final AgentExecutionMapper agentExecutionMapper;
    private final AgentMapper agentMapper;

    @Transactional
    public AgentExecutionDTO createExecution(String agentId, String sessionId, String input) {
        return createExecution(agentId, sessionId, input, null);
    }

    /**
     * 创建执行记录（带 A/B 实验分组，T3.4）。
     *
     * @param experimentGroup 实验分组（null 表示未参与实验）
     */
    @Transactional
    public AgentExecutionDTO createExecution(String agentId, String sessionId, String input,
                                              String experimentGroup) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw BusinessException.of(ResultCode.AGENT_NOT_FOUND);
        }

        AgentExecution execution = new AgentExecution();
        execution.setAgentId(agentId);
        execution.setSessionId(sessionId);
        execution.setUserId(UserContext.currentUserId());
        execution.setStatus(AgentExecutionStatus.RUNNING.name());
        execution.setInput(input);
        execution.setExperimentGroup(experimentGroup);
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
        execution.setStatus(AgentExecutionStatus.COMPLETED.name());
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
        execution.setStatus(AgentExecutionStatus.FAILED.name());
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
        // 鉴权：仅创建者（或 ADMIN）可查该 Agent 的执行记录（对齐 AgentService.getAgent 权限语义）
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw BusinessException.of(ResultCode.AGENT_NOT_FOUND);
        }
        String currentUserId = UserContext.currentUserId();
        if (currentUserId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        if (!currentUserId.equals(agent.getCreatedBy())) {
            List<String> roles = UserContext.get() != null ? UserContext.get().getRoleCodes() : null;
            if (roles == null || !roles.contains("ADMIN")) {
                throw BusinessException.of(ResultCode.FORBIDDEN, "无权查看此 Agent 的执行记录");
            }
        }

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
