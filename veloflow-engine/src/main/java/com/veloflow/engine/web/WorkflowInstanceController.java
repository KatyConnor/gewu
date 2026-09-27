package com.veloflow.engine.web;

import com.veloflow.engine.definition.WorkflowInstanceService;
import com.veloflow.engine.definition.dto.*;
import com.veloflow.engine.commons.FlowPage;
import com.veloflow.engine.commons.FlowPageResult;
import com.veloflow.engine.commons.FlowResult;
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
    public FlowResult<WorkflowInstanceDTO> startInstance(@PathVariable String workflowId,
                                                     @Valid @RequestBody StartInstanceCommand command) {
        return FlowResult.success(workflowInstanceService.startInstance(workflowId, command));
    }

    @GetMapping("/{instanceId}")
    @Operation(summary = "获取实例详情", description = "根据实例ID获取工作流实例详细信息")
    public FlowResult<WorkflowInstanceDTO> getInstance(@PathVariable String instanceId) {
        return FlowResult.success(workflowInstanceService.getInstance(instanceId));
    }

    @GetMapping
    @Operation(summary = "实例列表", description = "分页查询工作流实例，可按 workflowId 过滤")
    public FlowResult<FlowPageResult<WorkflowInstanceDTO>> listInstances(
            @RequestParam(required = false) String workflowId,
            @Valid FlowPage query) {
        return FlowResult.success(workflowInstanceService.listInstances(workflowId, query));
    }

    @GetMapping("/my")
    @Operation(summary = "我的实例", description = "分页查询当前用户发起的工作流实例")
    public FlowResult<FlowPageResult<WorkflowInstanceDTO>> listMyInstances(@Valid FlowPage query) {
        return FlowResult.success(workflowInstanceService.listMyInstances(query));
    }

    @PutMapping("/{instanceId}/nodes/{nodeInstanceId}/complete")
    @Operation(summary = "完成指定节点", description = "按节点实例 ID 精确完成并推进（幂等）；审批节点可携带 approved 字段（P1 内核重构）")
    public FlowResult<WorkflowNodeInstanceDTO> completeNode(@PathVariable String instanceId,
                                                       @PathVariable String nodeInstanceId,
                                                       @Valid @RequestBody CompleteNodeCommand command) {
        return FlowResult.success(workflowInstanceService.completeNode(instanceId, nodeInstanceId, command));
    }

    @PutMapping("/{instanceId}/suspend")
    @Operation(summary = "挂起实例", description = "将运行中的实例置为挂起状态")
    public FlowResult<Void> suspendInstance(@PathVariable String instanceId) {
        workflowInstanceService.suspendInstance(instanceId);
        return FlowResult.success();
    }

    @PutMapping("/{instanceId}/resume")
    @Operation(summary = "恢复实例", description = "将挂起的实例恢复为运行状态")
    public FlowResult<Void> resumeInstance(@PathVariable String instanceId) {
        workflowInstanceService.resumeInstance(instanceId);
        return FlowResult.success();
    }

    @PutMapping("/{instanceId}/terminate")
    @Operation(summary = "终止实例", description = "将实例置为终止状态")
    public FlowResult<Void> terminateInstance(@PathVariable String instanceId) {
        workflowInstanceService.terminateInstance(instanceId);
        return FlowResult.success();
    }

    @GetMapping("/{instanceId}/nodes")
    @Operation(summary = "实例节点列表", description = "获取工作流实例的全部节点执行记录")
    public FlowResult<List<WorkflowNodeInstanceDTO>> getInstanceNodes(@PathVariable String instanceId) {
        return FlowResult.success(workflowInstanceService.getInstanceNodes(instanceId));
    }

    @GetMapping("/notifications")
    @Operation(summary = "我的通知", description = "分页查询当前用户的工作流通知，按发送时间倒序")
    public FlowResult<FlowPageResult<WorkflowNotificationDTO>> getMyNotifications(@Valid FlowPage query) {
        return FlowResult.success(workflowInstanceService.getMyNotifications(query));
    }

    @PutMapping("/notifications/{notificationId}/read")
    @Operation(summary = "标记通知已读", description = "将指定通知标记为已读")
    public FlowResult<Void> markNotificationRead(@PathVariable String notificationId) {
        workflowInstanceService.markNotificationRead(notificationId);
        return FlowResult.success();
    }
}
