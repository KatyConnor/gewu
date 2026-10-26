package com.gewu.interfaceapi.controller;

import com.gewu.domain.longtask.AgentLongTask;
import com.gewu.domain.longtask.AgentLongTaskReview;
import com.gewu.domain.longtask.AgentLongTaskSegment;
import com.gewu.application.longtask.LongTaskService;
import com.gewu.application.longtask.dto.CreateLongTaskCommand;
import com.gewu.application.longtask.dto.ReviewRequest;
import com.gewu.application.longtask.dto.SteerRequest;
import com.gewu.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 持久化目标驱动长任务接口（docs/design/45）：创建/查询/人工控制/审核/补充指令。
 */
@RestController
@RequestMapping("/api/v1/long-tasks")
@RequiredArgsConstructor
@Tag(name = "长任务", description = "持久化目标驱动任务：小时级～按天、目标达标即止、人工审核闭环")
public class LongTaskController {

    private final LongTaskService longTaskService;

    @PostMapping
    @Operation(summary = "创建长任务", description = "基于会话创建目标驱动的持久化长任务，按冷却节奏分段推进")
    public Result<AgentLongTask> create(@Valid @RequestBody CreateLongTaskCommand command) {
        return Result.success(longTaskService.create(command));
    }

    @GetMapping
    @Operation(summary = "我的长任务列表", description = "可按状态过滤")
    public Result<List<AgentLongTask>> list(@RequestParam(required = false) String status) {
        return Result.success(longTaskService.listMy(
                com.gewu.common.context.UserContext.currentUserId(), status));
    }

    @GetMapping("/{id}")
    @Operation(summary = "长任务详情", description = "状态机 + 段历史 + 审核记录")
    public Result<java.util.Map<String, Object>> detail(@PathVariable String id) {
        String userId = com.gewu.common.context.UserContext.currentUserId();
        AgentLongTask task = longTaskService.getOwned(id, userId);
        return Result.success(java.util.Map.of(
                "task", task,
                "segments", longTaskService.segmentsOf(id),
                "reviews", longTaskService.reviewsOf(id)));
    }

    @PostMapping("/{id}/pause")
    @Operation(summary = "暂停", description = "当前段执行完毕后暂停推进")
    public Result<Void> pause(@PathVariable String id) {
        longTaskService.pause(id, com.gewu.common.context.UserContext.currentUserId());
        return Result.success();
    }

    @PostMapping("/{id}/resume")
    @Operation(summary = "恢复", description = "从最近检查点恢复推进")
    public Result<Void> resume(@PathVariable String id) {
        longTaskService.resume(id, com.gewu.common.context.UserContext.currentUserId());
        return Result.success();
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "取消", description = "取消任务（不可恢复）")
    public Result<Void> cancel(@PathVariable String id) {
        longTaskService.cancel(id, com.gewu.common.context.UserContext.currentUserId());
        return Result.success();
    }

    @PostMapping("/{id}/review")
    @Operation(summary = "人工审核", description = "通过=任务完成；驳回=意见注入下一段继续执行")
    public Result<Void> review(@PathVariable String id, @Valid @RequestBody ReviewRequest request) {
        longTaskService.review(id, request.getVerdict(), request.getComment(),
                com.gewu.common.context.UserContext.currentUserId());
        return Result.success();
    }

    @PostMapping("/{id}/steer")
    @Operation(summary = "补充指令", description = "向长任务注入补充指令（下一段种子生效）")
    public Result<Void> steer(@PathVariable String id, @RequestBody SteerRequest request) {
        longTaskService.steer(id, request.getContent(),
                com.gewu.common.context.UserContext.currentUserId());
        return Result.success();
    }

    @GetMapping("/{id}/segments")
    @Operation(summary = "段历史", description = "分段执行记录（含检查点与评估）")
    public Result<List<AgentLongTaskSegment>> segments(@PathVariable String id) {
        return Result.success(longTaskService.segmentsOf(id));
    }
}
