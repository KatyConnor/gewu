package com.gewu.interfaceapi.controller;

import com.gewu.application.workflow.WorkflowInstanceService;
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
 * 工作流实例接口 — 实例启动、节点流转、挂起/恢复/终止及通知.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/workflows/instances")
@RequiredArgsConstructor
@Tag(name = "工作流实例", description = "工作流实例的启动、流转、挂起恢复及通知")
public class WorkflowInstanceController {

    private final WorkflowInstanceService workflowInstanceService;

    @PostMapping("/{workflowId}/start")
    @Operation(summary = "启动工作流实例", description = "根据工作流定义启动新实例并推进至首个任务节点")
    public Result<WorkflowInstanceDTO> startInstance(@PathVariable String workflowId,
                                                     @Valid @RequestBody StartInstanceCommand command) {
        return Result.success(workflowInstanceService.startInstance(workflowId, command));
    }

    @GetMapping("/{instanceId}")
    @Operation(summary = "获取实例详情", description = "根据实例ID获取工作流实例详细信息")
    public Result<WorkflowInstanceDTO> getInstance(@PathVariable String instanceId) {
        return Result.success(workflowInstanceService.getInstance(instanceId));
    }

    @GetMapping
    @Operation(summary = "实例列表", description = "分页查询工作流实例，可按 workflowId 过滤")
    public Result<PageResult<WorkflowInstanceDTO>> listInstances(
            @RequestParam(required = false) String workflowId,
            @Valid PageQuery query) {
        return Result.success(workflowInstanceService.listInstances(workflowId, query));
    }

    @GetMapping("/my")
    @Operation(summary = "我的实例", description = "分页查询当前用户发起的工作流实例")
    public Result<PageResult<WorkflowInstanceDTO>> listMyInstances(@Valid PageQuery query) {
        return Result.success(workflowInstanceService.listMyInstances(query));
    }

    @PutMapping("/{instanceId}/complete")
    @Operation(summary = "完成当前节点", description = "完成当前运行节点并推进至下一节点，审批节点可携带 approved 字段")
    public Result<WorkflowNodeInstanceDTO> completeNode(@PathVariable String instanceId,
                                                       @Valid @RequestBody CompleteNodeCommand command) {
        return Result.success(workflowInstanceService.completeNode(instanceId, command));
    }

    @PutMapping("/{instanceId}/suspend")
    @Operation(summary = "挂起实例", description = "将运行中的实例置为挂起状态")
    public Result<Void> suspendInstance(@PathVariable String instanceId) {
        workflowInstanceService.suspendInstance(instanceId);
        return Result.success();
    }

    @PutMapping("/{instanceId}/resume")
    @Operation(summary = "恢复实例", description = "将挂起的实例恢复为运行状态")
    public Result<Void> resumeInstance(@PathVariable String instanceId) {
        workflowInstanceService.resumeInstance(instanceId);
        return Result.success();
    }

    @PutMapping("/{instanceId}/terminate")
    @Operation(summary = "终止实例", description = "将实例置为终止状态")
    public Result<Void> terminateInstance(@PathVariable String instanceId) {
        workflowInstanceService.terminateInstance(instanceId);
        return Result.success();
    }

    @GetMapping("/{instanceId}/nodes")
    @Operation(summary = "实例节点列表", description = "获取工作流实例的全部节点执行记录")
    public Result<List<WorkflowNodeInstanceDTO>> getInstanceNodes(@PathVariable String instanceId) {
        return Result.success(workflowInstanceService.getInstanceNodes(instanceId));
    }

    @GetMapping("/notifications")
    @Operation(summary = "我的通知", description = "分页查询当前用户的工作流通知，按发送时间倒序")
    public Result<PageResult<WorkflowNotificationDTO>> getMyNotifications(@Valid PageQuery query) {
        return Result.success(workflowInstanceService.getMyNotifications(query));
    }

    @PutMapping("/notifications/{notificationId}/read")
    @Operation(summary = "标记通知已读", description = "将指定通知标记为已读")
    public Result<Void> markNotificationRead(@PathVariable String notificationId) {
        workflowInstanceService.markNotificationRead(notificationId);
        return Result.success();
    }
}
