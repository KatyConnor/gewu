package com.gewu.application.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gewu.application.agent.dto.AgentDTO;
import com.gewu.application.agent.dto.AgentToolDTO;
import com.gewu.application.agent.dto.CreateAgentCommand;
import com.gewu.application.agent.dto.UpdateAgentCommand;
import com.gewu.common.context.UserContext;
import com.gewu.common.dto.PageQuery;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.PageResult;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.agent.Agent;
import com.gewu.domain.agent.AgentTool;
import com.gewu.infrastructure.mapper.AgentMapper;
import com.gewu.infrastructure.mapper.AgentToolMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final AgentMapper agentMapper;
    private final AgentToolMapper agentToolMapper;

    @Transactional
    public AgentDTO createAgent(CreateAgentCommand command) {
        Agent agent = new Agent();
        agent.setAgentName(command.getAgentName());
        agent.setDescription(command.getDescription());
        agent.setModelProvider(command.getModelProvider());
        agent.setModelName(command.getModelName());
        agent.setModelConfig(command.getModelConfig());
        agent.setSystemPrompt(command.getSystemPrompt());
        agent.setStatus(command.getStatus() != null ? command.getStatus() : 1);
        agent.setVersion(0);
        agentMapper.insert(agent);
        return toDTO(agent);
    }

    public AgentDTO getAgent(String agentId) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw BusinessException.of(ResultCode.AGENT_NOT_FOUND);
        }
        return toDTO(agent);
    }

    public PageResult<AgentDTO> listAgents(PageQuery query) {
        Page<Agent> page = new Page<>(query.getPage(), query.getSize());
        Page<Agent> result = agentMapper.selectPage(page,
                new LambdaQueryWrapper<Agent>().orderByDesc(Agent::getCreatedAt));

        List<AgentDTO> dtos = result.getRecords().stream()
                .map(this::toDTO)
                .toList();
        return PageResult.of(dtos, result.getTotal(), query.getPage(), query.getSize());
    }

    @Transactional
    public AgentDTO updateAgent(String agentId, UpdateAgentCommand command) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw BusinessException.of(ResultCode.AGENT_NOT_FOUND);
        }
        checkOwnership(agent);

        if (command.getAgentName() != null) agent.setAgentName(command.getAgentName());
        if (command.getDescription() != null) agent.setDescription(command.getDescription());
        if (command.getModelProvider() != null) agent.setModelProvider(command.getModelProvider());
        if (command.getModelName() != null) agent.setModelName(command.getModelName());
        if (command.getModelConfig() != null) agent.setModelConfig(command.getModelConfig());
        if (command.getSystemPrompt() != null) agent.setSystemPrompt(command.getSystemPrompt());
        if (command.getStatus() != null) agent.setStatus(command.getStatus());
        agentMapper.updateById(agent);

        return toDTO(agent);
    }

    @Transactional
    public void deleteAgent(String agentId) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw BusinessException.of(ResultCode.AGENT_NOT_FOUND);
        }
        checkOwnership(agent);
        agentMapper.deleteById(agentId);
    }

    public List<AgentToolDTO> getAgentTools(String agentId) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw BusinessException.of(ResultCode.AGENT_NOT_FOUND);
        }
        List<AgentTool> tools = agentToolMapper.selectList(
                new LambdaQueryWrapper<AgentTool>()
                        .eq(AgentTool::getAgentId, agentId)
                        .orderByAsc(AgentTool::getSortOrder));
        return tools.stream().map(AgentService::toToolDTO).toList();
    }

    private void checkOwnership(Agent agent) {
        String currentUserId = UserContext.currentUserId();
        if (currentUserId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        if (!currentUserId.equals(agent.getCreatedBy())) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "无权操作此 Agent");
        }
    }

    private AgentDTO toDTO(Agent agent) {
        return AgentDTO.builder()
                .agentId(agent.getId())
                .agentName(agent.getAgentName())
                .description(agent.getDescription())
                .modelProvider(agent.getModelProvider())
                .modelName(agent.getModelName())
                .modelConfig(agent.getModelConfig())
                .systemPrompt(agent.getSystemPrompt())
                .status(agent.getStatus())
                .statusDesc(resolveStatusDesc(agent.getStatus()))
                .version(agent.getVersion())
                .createdAt(agent.getCreatedAt())
                .createdBy(agent.getCreatedBy())
                .build();
    }

    private static AgentToolDTO toToolDTO(AgentTool tool) {
        return AgentToolDTO.builder()
                .toolId(tool.getId())
                .agentId(tool.getAgentId())
                .toolName(tool.getToolName())
                .description(tool.getDescription())
                .toolType(tool.getToolType())
                .endpoint(tool.getEndpoint())
                .timeoutMs(tool.getTimeoutMs())
                .status(tool.getStatus())
                .sortOrder(tool.getSortOrder())
                .build();
    }

    private String resolveStatusDesc(Integer status) {
        if (status == null) return "未知";
        return switch (status) {
            case 1 -> "启用";
            case 0 -> "禁用";
            default -> "未知";
        };
    }
}
