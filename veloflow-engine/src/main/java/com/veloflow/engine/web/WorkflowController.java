package com.veloflow.engine.web;

import com.veloflow.engine.definition.WorkflowService;
import com.veloflow.engine.definition.dto.*;
import com.veloflow.engine.commons.FlowPage;
import com.veloflow.engine.commons.FlowPageResult;
import com.veloflow.engine.commons.FlowResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 工作流接口 — 流程定义的创建、查询、发布、归档及图编排.
 */
@Slf4j
@RestController
@ConditionalOnProperty(name = "veloflow.rest.enabled", havingValue = "true", matchIfMissing = false)
@RequestMapping("/api/v1/workflows")
@RequiredArgsConstructor
@Tag(name = "工作流管理", description = "工作流定义的创建、发布、归档及图编排")
public class WorkflowController {

    private final WorkflowService workflowService;

    @PostMapping
    @Operation(summary = "创建工作流", description = "创建草稿状态的工作流，版本初始化为 1")
    public FlowResult<WorkflowDTO> createWorkflow(@Valid @RequestBody CreateWorkflowCommand command) {
        return FlowResult.success(workflowService.createWorkflow(command));
    }

    @GetMapping("/{workflowId}")
    @Operation(summary = "获取工作流详情", description = "根据工作流ID获取工作流详细信息")
    public FlowResult<WorkflowDTO> getWorkflow(@PathVariable String workflowId) {
        return FlowResult.success(workflowService.getWorkflow(workflowId));
    }

    @GetMapping
    @Operation(summary = "工作流列表", description = "分页查询工作流列表")
    public FlowResult<FlowPageResult<WorkflowDTO>> listWorkflows(@Valid FlowPage query) {
        return FlowResult.success(workflowService.listWorkflows(query));
    }

    @PutMapping("/{workflowId}")
    @Operation(summary = "更新工作流", description = "更新工作流信息，仅草稿状态可编辑")
    public FlowResult<WorkflowDTO> updateWorkflow(@PathVariable String workflowId,
                                              @Valid @RequestBody UpdateWorkflowCommand command) {
        return FlowResult.success(workflowService.updateWorkflow(workflowId, command));
    }

    @DeleteMapping("/{workflowId}")
    @Operation(summary = "删除工作流", description = "软删除工作流，存在运行中实例时不允许删除")
    public FlowResult<Void> deleteWorkflow(@PathVariable String workflowId) {
        workflowService.deleteWorkflow(workflowId);
        return FlowResult.success();
    }

    @PostMapping("/{workflowId}/publish")
    @Operation(summary = "发布工作流", description = "将工作流状态置为已发布并记录发布时间")
    public FlowResult<WorkflowDTO> publishWorkflow(@PathVariable String workflowId) {
        return FlowResult.success(workflowService.publishWorkflow(workflowId));
    }

    @PostMapping("/{workflowId}/archive")
    @Operation(summary = "归档工作流", description = "将工作流状态置为已归档")
    public FlowResult<WorkflowDTO> archiveWorkflow(@PathVariable String workflowId) {
        return FlowResult.success(workflowService.archiveWorkflow(workflowId));
    }

    @GetMapping("/{workflowId}/graph")
    @Operation(summary = "获取工作流图", description = "获取工作流的节点与流转定义")
    public FlowResult<SaveWorkflowGraphCommand> getWorkflowGraph(@PathVariable String workflowId) {
        return FlowResult.success(workflowService.getWorkflowGraph(workflowId));
    }

    @PutMapping("/{workflowId}/graph")
    @Operation(summary = "保存工作流图", description = "覆盖保存工作流的节点与流转定义")
    public FlowResult<Void> saveWorkflowGraph(@PathVariable String workflowId,
                                          @Valid @RequestBody SaveWorkflowGraphCommand command) {
        workflowService.saveWorkflowGraph(workflowId, command);
        return FlowResult.success();
    }

    // ==================== 版本快照（51 号 T4.4） ====================

    @GetMapping("/{workflowId}/versions")
    @Operation(summary = "版本列表", description = "定义版本快照元数据（新→旧）")
    public FlowResult<List<WorkflowVersionDTO>> listVersions(@PathVariable String workflowId) {
        return FlowResult.success(workflowService.listVersions(workflowId));
    }

    @GetMapping("/{workflowId}/versions/{version}")
    @Operation(summary = "版本快照详情", description = "指定版本的图结构（设计器加载对比）")
    public FlowResult<SaveWorkflowGraphCommand> getVersionSnapshot(
            @PathVariable String workflowId, @PathVariable int version) {
        return FlowResult.success(workflowService.getVersionSnapshot(workflowId, version));
    }

    @PostMapping("/{workflowId}/versions/{version}/rollback")
    @Operation(summary = "回滚到历史版本", description = "存在运行中实例时拒绝；快照写回活表并置回草稿，重新发布产生新版本号")
    public FlowResult<WorkflowDTO> rollbackToVersion(
            @PathVariable String workflowId, @PathVariable int version) {
        return FlowResult.success(workflowService.rollbackToVersion(workflowId, version));
    }

    @GetMapping("/{workflowId}/nodes")
    @Operation(summary = "工作流节点列表", description = "获取工作流下所有节点定义")
    public FlowResult<List<WorkflowNodeDTO>> getWorkflowNodes(@PathVariable String workflowId) {
        return FlowResult.success(workflowService.getWorkflowNodes(workflowId));
    }
}
