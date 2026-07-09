package com.gewu.interfaceapi.controller;

import com.gewu.application.workflow.WorkflowService;
import com.gewu.application.workflow.dto.*;
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
 * 工作流接口 — 流程定义的创建、查询、发布、归档及图编排.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/workflows")
@RequiredArgsConstructor
@Tag(name = "工作流管理", description = "工作流定义的创建、发布、归档及图编排")
public class WorkflowController {

    private final WorkflowService workflowService;

    @PostMapping
    @Operation(summary = "创建工作流", description = "创建草稿状态的工作流，版本初始化为 1")
    public Result<WorkflowDTO> createWorkflow(@Valid @RequestBody CreateWorkflowCommand command) {
        return Result.success(workflowService.createWorkflow(command));
    }

    @GetMapping("/{workflowId}")
    @Operation(summary = "获取工作流详情", description = "根据工作流ID获取工作流详细信息")
    public Result<WorkflowDTO> getWorkflow(@PathVariable String workflowId) {
        return Result.success(workflowService.getWorkflow(workflowId));
    }

    @GetMapping
    @Operation(summary = "工作流列表", description = "分页查询工作流列表")
    public Result<PageResult<WorkflowDTO>> listWorkflows(@Valid PageQuery query) {
        return Result.success(workflowService.listWorkflows(query));
    }

    @PutMapping("/{workflowId}")
    @Operation(summary = "更新工作流", description = "更新工作流信息，仅草稿状态可编辑")
    public Result<WorkflowDTO> updateWorkflow(@PathVariable String workflowId,
                                              @Valid @RequestBody UpdateWorkflowCommand command) {
        return Result.success(workflowService.updateWorkflow(workflowId, command));
    }

    @DeleteMapping("/{workflowId}")
    @Operation(summary = "删除工作流", description = "软删除工作流，存在运行中实例时不允许删除")
    public Result<Void> deleteWorkflow(@PathVariable String workflowId) {
        workflowService.deleteWorkflow(workflowId);
        return Result.success();
    }

    @PostMapping("/{workflowId}/publish")
    @Operation(summary = "发布工作流", description = "将工作流状态置为已发布并记录发布时间")
    public Result<WorkflowDTO> publishWorkflow(@PathVariable String workflowId) {
        return Result.success(workflowService.publishWorkflow(workflowId));
    }

    @PostMapping("/{workflowId}/archive")
    @Operation(summary = "归档工作流", description = "将工作流状态置为已归档")
    public Result<WorkflowDTO> archiveWorkflow(@PathVariable String workflowId) {
        return Result.success(workflowService.archiveWorkflow(workflowId));
    }

    @GetMapping("/{workflowId}/graph")
    @Operation(summary = "获取工作流图", description = "获取工作流的节点与流转定义")
    public Result<SaveWorkflowGraphCommand> getWorkflowGraph(@PathVariable String workflowId) {
        return Result.success(workflowService.getWorkflowGraph(workflowId));
    }

    @PutMapping("/{workflowId}/graph")
    @Operation(summary = "保存工作流图", description = "覆盖保存工作流的节点与流转定义")
    public Result<Void> saveWorkflowGraph(@PathVariable String workflowId,
                                          @Valid @RequestBody SaveWorkflowGraphCommand command) {
        workflowService.saveWorkflowGraph(workflowId, command);
        return Result.success();
    }

    @GetMapping("/{workflowId}/nodes")
    @Operation(summary = "工作流节点列表", description = "获取工作流下所有节点定义")
    public Result<List<WorkflowNodeDTO>> getWorkflowNodes(@PathVariable String workflowId) {
        return Result.success(workflowService.getWorkflowNodes(workflowId));
    }
}
