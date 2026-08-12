package com.gewu.interfaceapi.controller;

import com.gewu.application.agent.AgentService;
import com.gewu.application.agent.dto.AgentDTO;
import com.gewu.application.agent.dto.AgentToolDTO;
import com.gewu.application.agent.dto.CreateAgentCommand;
import com.gewu.application.agent.dto.UpdateAgentCommand;
import com.gewu.application.skill.dto.SkillDTO;
import com.gewu.common.dto.PageQuery;
import com.gewu.common.result.PageResult;
import com.gewu.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Agent 管理接口 — Agent 的创建、查询、更新与删除.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
@Tag(name = "Agent 管理", description = "Agent 的创建、查询、更新与删除")
public class AgentController {

    private final AgentService agentService;

    @PostMapping
    @Operation(summary = "创建 Agent", description = "创建一个新的 Agent")
    public Result<AgentDTO> createAgent(@Valid @RequestBody CreateAgentCommand command) {
        log.info("创建 Agent: {}", command.getAgentName());
        return Result.success(agentService.createAgent(command));
    }

    @GetMapping("/{agentId}")
    @Operation(summary = "获取 Agent", description = "根据 Agent ID 获取 Agent 详情")
    public Result<AgentDTO> getAgent(@PathVariable String agentId) {
        return Result.success(agentService.getAgent(agentId));
    }

    @GetMapping
    @Operation(summary = "Agent 列表", description = "分页查询 Agent 列表")
    public Result<PageResult<AgentDTO>> listAgents(@Valid PageQuery query) {
        return Result.success(agentService.listAgents(query));
    }

    @PutMapping("/{agentId}")
    @Operation(summary = "更新 Agent", description = "更新指定 Agent 的信息")
    public Result<AgentDTO> updateAgent(@PathVariable String agentId,
                                        @Valid @RequestBody UpdateAgentCommand command) {
        log.info("更新 Agent: {}", agentId);
        return Result.success(agentService.updateAgent(agentId, command));
    }

    @DeleteMapping("/{agentId}")
    @Operation(summary = "删除 Agent", description = "软删除指定 Agent")
    public Result<Void> deleteAgent(@PathVariable String agentId) {
        log.info("删除 Agent: {}", agentId);
        agentService.deleteAgent(agentId);
        return Result.success();
    }

    @GetMapping("/{agentId}/tools")
    @Operation(summary = "获取 Agent 工具列表", description = "获取指定 Agent 绑定的工具列表")
    public Result<List<AgentToolDTO>> getAgentTools(@PathVariable String agentId) {
        return Result.success(agentService.getAgentTools(agentId));
    }

    @GetMapping("/{agentId}/skills")
    @Operation(summary = "获取 Agent 技能列表", description = "获取指定 Agent 已挂载的技能")
    public Result<List<SkillDTO>> listAgentSkills(@PathVariable String agentId) {
        return Result.success(agentService.listAgentSkills(agentId));
    }

    @PostMapping("/{agentId}/skills/{skillId}")
    @Operation(summary = "挂载技能", description = "将技能挂载到指定 Agent")
    public Result<Void> mountSkill(@PathVariable String agentId, @PathVariable String skillId) {
        log.info("挂载技能: agentId={}, skillId={}", agentId, skillId);
        agentService.mountSkill(agentId, skillId);
        return Result.success();
    }

    @DeleteMapping("/{agentId}/skills/{skillId}")
    @Operation(summary = "卸载技能", description = "从 Agent 卸载指定技能")
    public Result<Void> unmountSkill(@PathVariable String agentId, @PathVariable String skillId) {
        log.info("卸载技能: agentId={}, skillId={}", agentId, skillId);
        agentService.unmountSkill(agentId, skillId);
        return Result.success();
    }
}
