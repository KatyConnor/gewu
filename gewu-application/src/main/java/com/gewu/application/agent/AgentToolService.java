package com.gewu.application.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.application.agent.dto.AgentToolDTO;
import com.gewu.application.agent.dto.CreateToolCommand;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.agent.AgentTool;
import com.gewu.infrastructure.mapper.AgentToolMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentToolService {

    private final AgentToolMapper agentToolMapper;

    @Transactional
    public AgentToolDTO createTool(CreateToolCommand command) {
        AgentTool tool = new AgentTool();
        tool.setAgentId(command.getAgentId());
        tool.setToolName(command.getToolName());
        tool.setDescription(command.getDescription());
        tool.setToolType(command.getToolType());
        tool.setEndpoint(command.getEndpoint());
        tool.setRequestSchema(command.getRequestSchema());
        tool.setResponseSchema(command.getResponseSchema());
        tool.setAuthConfig(command.getAuthConfig());
        tool.setTimeoutMs(command.getTimeoutMs() != null ? command.getTimeoutMs() : 30000);
        tool.setSortOrder(command.getSortOrder() != null ? command.getSortOrder() : 0);
        tool.setStatus(1);
        agentToolMapper.insert(tool);
        return toDTO(tool);
    }

    public AgentToolDTO getTool(String toolId) {
        AgentTool tool = agentToolMapper.selectById(toolId);
        if (tool == null) {
            throw BusinessException.of(ResultCode.TOOL_NOT_FOUND);
        }
        return toDTO(tool);
    }

    public List<AgentToolDTO> listTools(String agentId) {
        List<AgentTool> tools = agentToolMapper.selectList(
                new LambdaQueryWrapper<AgentTool>()
                        .eq(AgentTool::getAgentId, agentId)
                        .orderByAsc(AgentTool::getSortOrder));
        return tools.stream().map(this::toDTO).toList();
    }

    @Transactional
    public AgentToolDTO updateTool(String toolId, CreateToolCommand command) {
        AgentTool tool = agentToolMapper.selectById(toolId);
        if (tool == null) {
            throw BusinessException.of(ResultCode.TOOL_NOT_FOUND);
        }

        if (command.getAgentId() != null) tool.setAgentId(command.getAgentId());
        if (command.getToolName() != null) tool.setToolName(command.getToolName());
        if (command.getDescription() != null) tool.setDescription(command.getDescription());
        if (command.getToolType() != null) tool.setToolType(command.getToolType());
        if (command.getEndpoint() != null) tool.setEndpoint(command.getEndpoint());
        if (command.getRequestSchema() != null) tool.setRequestSchema(command.getRequestSchema());
        if (command.getResponseSchema() != null) tool.setResponseSchema(command.getResponseSchema());
        if (command.getAuthConfig() != null) tool.setAuthConfig(command.getAuthConfig());
        if (command.getTimeoutMs() != null) tool.setTimeoutMs(command.getTimeoutMs());
        if (command.getSortOrder() != null) tool.setSortOrder(command.getSortOrder());
        agentToolMapper.updateById(tool);

        return toDTO(tool);
    }

    private AgentToolDTO toDTO(AgentTool tool) {
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
}
