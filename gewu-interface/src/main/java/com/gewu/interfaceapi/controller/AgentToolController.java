package com.gewu.interfaceapi.controller;

import com.gewu.application.agent.AgentToolService;
import com.gewu.application.agent.dto.AgentToolDTO;
import com.gewu.application.agent.dto.CreateToolCommand;
import com.gewu.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Agent 工具接口 — Agent 工具的创建、查询与更新.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agents/tools")
@RequiredArgsConstructor
@Tag(name = "Agent 工具管理", description = "Agent 工具的创建、查询与更新")
public class AgentToolController {

    private final AgentToolService agentToolService;

    @PostMapping
    @Operation(summary = "创建工具", description = "为 Agent 创建一个新的工具")
    public Result<AgentToolDTO> createTool(@Valid @RequestBody CreateToolCommand command) {
        log.info("创建工具: {}", command.getToolName());
        return Result.success(agentToolService.createTool(command));
    }

    @GetMapping("/{toolId}")
    @Operation(summary = "获取工具", description = "根据工具 ID 获取工具详情")
    public Result<AgentToolDTO> getTool(@PathVariable String toolId) {
        return Result.success(agentToolService.getTool(toolId));
    }

    @GetMapping("/agent/{agentId}")
    @Operation(summary = "获取 Agent 工具列表", description = "获取指定 Agent 绑定的工具列表")
    public Result<List<AgentToolDTO>> listTools(@PathVariable String agentId) {
        return Result.success(agentToolService.listTools(agentId));
    }

    @PutMapping("/{toolId}")
    @Operation(summary = "更新工具", description = "更新指定工具的信息")
    public Result<AgentToolDTO> updateTool(@PathVariable String toolId,
                                           @Valid @RequestBody CreateToolCommand command) {
        log.info("更新工具: {}", toolId);
        return Result.success(agentToolService.updateTool(toolId, command));
    }
}
