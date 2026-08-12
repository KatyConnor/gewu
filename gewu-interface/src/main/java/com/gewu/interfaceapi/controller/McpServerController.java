package com.gewu.interfaceapi.controller;

import com.gewu.application.agent.McpServerService;
import com.gewu.application.agent.dto.CreateMcpServerCommand;
import com.gewu.application.agent.dto.McpServerDTO;
import com.gewu.application.agent.dto.McpToolDTO;
import com.gewu.common.dto.PageQuery;
import com.gewu.common.result.PageResult;
import com.gewu.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/mcp-servers")
@RequiredArgsConstructor
@Tag(name = "MCP Server 管理", description = "MCP Server 配置、工具发现与管理")
public class McpServerController {

    private final McpServerService mcpServerService;

    @PostMapping
    @Operation(summary = "创建 MCP Server", description = "创建新的 MCP Server 配置")
    public Result<McpServerDTO> create(@Valid @RequestBody CreateMcpServerCommand command) {
        log.info("创建 MCP Server: name={}, transport={}", command.getName(), command.getTransport());
        return Result.success(mcpServerService.createServer(command));
    }

    @GetMapping
    @Operation(summary = "MCP Server 列表", description = "分页查询 MCP Server 列表")
    public Result<PageResult<McpServerDTO>> list(@Valid PageQuery query) {
        return Result.success(mcpServerService.listServers(query));
    }

    @GetMapping("/{serverId}")
    @Operation(summary = "获取 MCP Server", description = "根据 ID 获取 MCP Server 详情")
    public Result<McpServerDTO> get(@PathVariable String serverId) {
        return Result.success(mcpServerService.getServer(serverId));
    }

    @DeleteMapping("/{serverId}")
    @Operation(summary = "删除 MCP Server", description = "删除 MCP Server 并断开连接")
    public Result<Void> delete(@PathVariable String serverId) {
        log.info("删除 MCP Server: serverId={}", serverId);
        mcpServerService.deleteServer(serverId);
        return Result.success();
    }

    @GetMapping("/{serverId}/tools")
    @Operation(summary = "发现 MCP 工具", description = "从 MCP Server 动态获取可用工具列表")
    public Result<List<McpToolDTO>> listTools(@PathVariable String serverId) {
        log.info("发现 MCP 工具: serverId={}", serverId);
        return Result.success(mcpServerService.listServerTools(serverId));
    }

    @PostMapping("/{serverId}/activate")
    @Operation(summary = "激活 MCP Server", description = "激活 MCP Server 并建立连接")
    public Result<McpServerDTO> activate(@PathVariable String serverId) {
        log.info("激活 MCP Server: serverId={}", serverId);
        return Result.success(mcpServerService.updateServerStatus(serverId, 1));
    }

    @PostMapping("/{serverId}/deactivate")
    @Operation(summary = "停用 MCP Server", description = "停用 MCP Server 并断开连接")
    public Result<McpServerDTO> deactivate(@PathVariable String serverId) {
        log.info("停用 MCP Server: serverId={}", serverId);
        return Result.success(mcpServerService.updateServerStatus(serverId, 0));
    }
}
