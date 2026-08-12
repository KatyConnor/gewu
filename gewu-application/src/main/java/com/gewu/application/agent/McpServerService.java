package com.gewu.application.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gewu.application.agent.dto.CreateMcpServerCommand;
import com.gewu.application.agent.dto.McpServerDTO;
import com.gewu.application.agent.dto.McpToolDTO;
import com.gewu.common.context.UserContext;
import com.gewu.common.dto.PageQuery;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.PageResult;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.agent.McpServer;
import com.gewu.infrastructure.mcp.McpServerManager;
import com.gewu.infrastructure.mcp.McpToolDefinition;
import com.gewu.infrastructure.mapper.McpServerMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class McpServerService {

    private final McpServerMapper mcpServerMapper;
    private final McpServerManager mcpServerManager;
    private final McpCommandValidator mcpCommandValidator;

    @Transactional
    public McpServerDTO createServer(CreateMcpServerCommand command) {
        // MCP 命令安全校验
        if ("stdio".equals(command.getTransport())) {
            List<String> argsList = parseArgs(command.getArgs());
            mcpCommandValidator.validate(command.getCommand(), argsList);
        }

        String userId = UserContext.currentUserId();
        McpServer server = new McpServer();
        server.setName(command.getName());
        server.setDescription(command.getDescription());
        server.setTransport(command.getTransport() != null ? command.getTransport() : "stdio");
        server.setCommand(command.getCommand());
        server.setArgs(command.getArgs());
        server.setUrl(command.getUrl());
        server.setEnv(command.getEnv());
        server.setStatus(0);
        mcpServerMapper.insert(server);

        log.info("创建 MCP Server: id={}, name={}, transport={}", server.getId(), server.getName(), server.getTransport());
        return toDTO(server);
    }

    public McpServerDTO getServer(String serverId) {
        McpServer server = mcpServerMapper.selectById(serverId);
        if (server == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "MCP Server 不存在");
        }
        return toDTO(server);
    }

    public PageResult<McpServerDTO> listServers(PageQuery query) {
        Page<McpServer> page = new Page<>(query.getPage(), query.getSize());
        Page<McpServer> result = mcpServerMapper.selectPage(page,
                new LambdaQueryWrapper<McpServer>().orderByDesc(McpServer::getCreatedAt));
        List<McpServerDTO> dtos = result.getRecords().stream().map(this::toDTO).toList();
        return PageResult.of(dtos, result.getTotal(), query.getPage(), query.getSize());
    }

    @Transactional
    public void deleteServer(String serverId) {
        mcpServerManager.disconnect(serverId);
        mcpServerMapper.deleteById(serverId);
        log.info("删除 MCP Server: id={}", serverId);
    }

    public List<McpToolDTO> listServerTools(String serverId) {
        List<McpToolDefinition> tools = mcpServerManager.listTools(serverId);
        McpServer server = mcpServerMapper.selectById(serverId);
        String serverName = server != null ? server.getName() : null;
        return tools.stream()
                .map(tool -> McpToolDTO.builder()
                        .name(tool.getName())
                        .description(tool.getDescription())
                        .inputSchema(tool.getInputSchema())
                        .serverId(serverId)
                        .serverName(serverName)
                        .build())
                .toList();
    }

    @Transactional
    public McpServerDTO updateServerStatus(String serverId, Integer status) {
        McpServer server = mcpServerMapper.selectById(serverId);
        if (server == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "MCP Server 不存在");
        }
        server.setStatus(status);
        mcpServerMapper.updateById(server);
        if (status == 0) {
            mcpServerManager.disconnect(serverId);
        }
        return toDTO(server);
    }

    private McpServerDTO toDTO(McpServer server) {
        return McpServerDTO.builder()
                .serverId(server.getId())
                .name(server.getName())
                .description(server.getDescription())
                .transport(server.getTransport())
                .command(server.getCommand())
                .args(server.getArgs())
                .url(server.getUrl())
                .status(server.getStatus())
                .createdAt(server.getCreatedAt())
                .build();
    }

    private List<String> parseArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return List.of();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(argsJson, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("解析 MCP args 失败: {}", argsJson, e);
            return List.of();
        }
    }
}
