package com.gewu.application.agent.adapter;

import com.gewu.agent.engine.spi.ToolConfig;
import com.gewu.agent.engine.tool.ToolConfigSource;
import com.gewu.domain.agent.AgentTool;
import com.gewu.infrastructure.mapper.AgentToolMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * {@link ToolConfigSource} 业务适配 - 从 agent_tool 表加载工具配置。
 * <p>加载 status=1 的启用工具，转换为框架 {@link ToolConfig}。
 * 代码注册的工具（@ToolProvider）优先于此配置。
 *
 * @since 1.0.0
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.engine.adapter.enabled", havingValue = "true")
public class DbToolConfigSourceAdapter implements ToolConfigSource {

    private final AgentToolMapper agentToolMapper;

    @Override
    public List<ToolConfig> loadTools() {
        return agentToolMapper.selectList(
                        new LambdaQueryWrapper<AgentTool>().eq(AgentTool::getStatus, 1))
                .stream()
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
    public List<ToolConfig> loadToolsByAgent(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return List.of();
        }
        return agentToolMapper.selectList(
                        new LambdaQueryWrapper<AgentTool>()
                                .eq(AgentTool::getAgentId, agentId)
                                .eq(AgentTool::getStatus, 1)
                                .orderByAsc(AgentTool::getSortOrder))
                .stream()
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
}