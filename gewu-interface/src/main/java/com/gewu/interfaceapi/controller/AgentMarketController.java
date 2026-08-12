package com.gewu.interfaceapi.controller;

import com.gewu.application.agent.AgentMarketService;
import com.gewu.application.agent.dto.AgentMarketDTO;
import com.gewu.application.agent.dto.PublishAgentCommand;
import com.gewu.application.skill.dto.AuditSkillCommand;
import com.gewu.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 智能体广场接口 - 上架、浏览与安装.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agents/market")
@RequiredArgsConstructor
@Tag(name = "智能体广场", description = "智能体广场上架、浏览与安装")
public class AgentMarketController {

    private final AgentMarketService agentMarketService;

    @GetMapping
    @Operation(summary = "广场列表", description = "获取上架的智能体列表，可选分类过滤")
    public Result<List<AgentMarketDTO>> list(@RequestParam(required = false) String category) {
        return Result.success(agentMarketService.listMarket(category));
    }

    @PostMapping
    @Operation(summary = "发布到广场", description = "将指定 Agent 上架到广场")
    public Result<AgentMarketDTO> publish(@Valid @RequestBody PublishAgentCommand command) {
        log.info("发布 Agent 到广场: agentId={}", command.getAgentId());
        return Result.success(agentMarketService.publish(command));
    }

    @PostMapping("/{marketId}/install")
    @Operation(summary = "安装广场智能体", description = "复制广场智能体到当前用户名下")
    public Result<Void> install(@PathVariable String marketId) {
        log.info("安装广场 Agent: marketId={}", marketId);
        agentMarketService.install(marketId);
        return Result.success();
    }

    @DeleteMapping("/{marketId}")
    @Operation(summary = "下架广场智能体", description = "从广场下架指定智能体")
    public Result<Void> unpublish(@PathVariable String marketId) {
        log.info("下架广场 Agent: marketId={}", marketId);
        agentMarketService.unpublish(marketId);
        return Result.success();
    }

    @PostMapping("/{marketId}/audit")
    @Operation(summary = "审核广场智能体上架", description = "管理员审核待上架的智能体")
    public Result<Void> audit(@PathVariable String marketId, @Valid @RequestBody AuditSkillCommand command) {
        log.info("审核广场 Agent: marketId={}, approved={}", marketId, command.getApproved());
        agentMarketService.auditMarketPublish(marketId, command.getApproved());
        return Result.success();
    }
}
