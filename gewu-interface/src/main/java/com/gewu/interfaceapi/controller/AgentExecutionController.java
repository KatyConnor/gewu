package com.gewu.interfaceapi.controller;

import com.gewu.application.agent.AgentExecutionService;
import com.gewu.application.agent.dto.AgentExecutionDTO;
import com.gewu.common.dto.PageQuery;
import com.gewu.common.result.PageResult;
import com.gewu.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * Agent 执行接口 — Agent 执行记录的创建、完成、失败与查询.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agents/executions")
@RequiredArgsConstructor
@Tag(name = "Agent 执行管理", description = "Agent 执行记录的创建、完成、失败与查询")
public class AgentExecutionController {

    private final AgentExecutionService agentExecutionService;

    @PostMapping
    @Operation(summary = "创建执行记录", description = "为指定 Agent 创建一条执行记录")
    public Result<AgentExecutionDTO> createExecution(@Valid @RequestBody CreateExecutionRequest request) {
        log.info("创建 Agent 执行: agentId={}", request.getAgentId());
        return Result.success(agentExecutionService.createExecution(
                request.getAgentId(), request.getSessionId(), request.getInput()));
    }

    @PutMapping("/{executionId}/complete")
    @Operation(summary = "完成执行", description = "标记执行记录为完成并记录输出")
    public Result<AgentExecutionDTO> completeExecution(@PathVariable String executionId,
                                                       @Valid @RequestBody CompleteExecutionRequest request) {
        log.info("完成 Agent 执行: {}", executionId);
        return Result.success(agentExecutionService.completeExecution(
                executionId, request.getOutput(), request.getTokensUsed()));
    }

    @PutMapping("/{executionId}/fail")
    @Operation(summary = "标记执行失败", description = "标记执行记录为失败并记录错误信息")
    public Result<AgentExecutionDTO> failExecution(@PathVariable String executionId,
                                                   @Valid @RequestBody FailExecutionRequest request) {
        log.info("Agent 执行失败: {}", executionId);
        return Result.success(agentExecutionService.failExecution(executionId, request.getErrorMessage()));
    }

    @GetMapping("/{executionId}")
    @Operation(summary = "获取执行记录", description = "根据执行 ID 获取执行记录详情")
    public Result<AgentExecutionDTO> getExecution(@PathVariable String executionId) {
        return Result.success(agentExecutionService.getExecution(executionId));
    }

    @GetMapping("/agent/{agentId}")
    @Operation(summary = "Agent 执行列表", description = "分页查询指定 Agent 的执行记录")
    public Result<PageResult<AgentExecutionDTO>> listExecutions(@PathVariable String agentId,
                                                                @Valid PageQuery query) {
        return Result.success(agentExecutionService.listExecutions(agentId, query));
    }

    @GetMapping("/my")
    @Operation(summary = "我的执行列表", description = "分页查询当前用户的执行记录")
    public Result<PageResult<AgentExecutionDTO>> listMyExecutions(@Valid PageQuery query) {
        return Result.success(agentExecutionService.listUserExecutions(query));
    }

    @lombok.Data
    public static class CreateExecutionRequest {
        @jakarta.validation.constraints.NotBlank(message = "Agent ID 不能为空")
        private String agentId;
        private String sessionId;
        private String input;
    }

    @lombok.Data
    public static class CompleteExecutionRequest {
        private String output;
        private Integer tokensUsed;
    }

    @lombok.Data
    public static class FailExecutionRequest {
        private String errorMessage;
    }
}
